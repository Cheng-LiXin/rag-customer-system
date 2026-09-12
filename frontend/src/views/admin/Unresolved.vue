<template>
  <div>
    <el-card class="mb-16">
      <div class="stat-row">
        <div class="stat-card">
          <div class="stat-num">{{ stats.pending }}</div>
          <div class="stat-label">待处理</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ stats.total }}</div>
          <div class="stat-label">累计问题</div>
        </div>
        <div class="stat-card" v-for="(label, key) in SOURCE_LABEL" :key="key">
          <div class="stat-num small">{{ stats.bySource[key] || 0 }}</div>
          <div class="stat-label">{{ label }}</div>
        </div>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="闭环：机器人答不上来 → 这里出现 → 补知识 → 标记已补知识 → 导出复测清单重跑评估"
        class="mt-12"
      />
    </el-card>

    <el-card>
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="(t, k) in STATUS_LABEL" :key="k" :label="t" :value="Number(k)" />
        </el-select>
        <el-select v-model="query.source" placeholder="全部来源" clearable style="width: 150px" @change="load">
          <el-option v-for="(t, k) in SOURCE_LABEL" :key="k" :label="t" :value="Number(k)" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="搜索问题"
          clearable
          style="width: 220px"
          @keyup.enter="load"
        />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
        <div style="flex: 1"></div>
        <el-button :icon="Download" @click="exportRetest">导出复测清单</el-button>
      </div>

      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column label="问题" min-width="260">
          <template #default="{ row }">
            <div class="q-text">{{ row.question }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceType(row.source)">{{ SOURCE_LABEL[row.source] || '未知' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" label="次数" width="80" sortable />
        <el-table-column label="最高相似度" width="120">
          <template #default="{ row }">
            <span v-if="row.topScore != null">{{ Number(row.topScore).toFixed(4) }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ STATUS_LABEL[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="补充知识" width="100">
          <template #default="{ row }">
            <span v-if="row.knowledgeChunkId">#{{ row.knowledgeChunkId }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="最近出现" width="170" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openHandle(row)">处理</el-button>
            <el-button link type="primary" @click="copyQuestion(row)">复制</el-button>
            <el-popover placement="left" :width="320" trigger="click">
              <template #reference>
                <el-button link>详情</el-button>
              </template>
              <div class="pop">
                <p><b>问题：</b>{{ row.question }}</p>
                <p><b>来源：</b>{{ SOURCE_LABEL[row.source] }}</p>
                <p><b>相似度：</b>{{ row.topScore ?? '-' }}</p>
                <p><b>会话：</b>{{ row.conversationId ?? '-' }}</p>
                <p><b>处理备注：</b>{{ row.remark || '-' }}</p>
              </div>
            </el-popover>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPageChange"
      />
    </el-card>

    <!-- 处理弹窗 -->
    <el-dialog v-model="dialog.visible" title="处理未解决问题" width="560px" destroy-on-close>
      <div class="dialog-q">{{ dialog.question }}</div>
      <el-form label-width="110px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="dialog.status">
            <el-radio :value="2">已补知识</el-radio>
            <el-radio :value="3">已忽略</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="dialog.status === 2" label="知识片段 ID">
          <el-input v-model.number="dialog.knowledgeChunkId" placeholder="补充完成后，填该知识片段的 ID" />
          <div class="tip">
            先到「知识库管理」新增片段，再回来填它的 ID —— 这样池子里的记录能追到具体补了哪条知识。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialog.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="goKnowledge">去知识库新增片段</el-button>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Download } from '@element-plus/icons-vue'
import {
  pageUnresolved,
  unresolvedStats,
  handleUnresolved,
  type UnresolvedQuestion,
  type UnresolvedStats
} from '@/api/unresolved'

const router = useRouter()
const loading = ref(false)
const records = ref<UnresolvedQuestion[]>([])
const total = ref(0)
const stats = ref<UnresolvedStats>({ total: 0, pending: 0, bySource: {}, byStatus: {} })

const SOURCE_LABEL: Record<number, string> = {
  1: '兜底未答',
  2: '用户点踩',
  3: '相似度过低',
  4: '防护拦截'
}
const STATUS_LABEL: Record<number, string> = { 1: '待处理', 2: '已补知识', 3: '已忽略' }
const sourceType = (s: number) => (s === 4 ? 'danger' : s === 3 ? 'warning' : s === 2 ? 'info' : 'primary')
const statusType = (s: number) => (s === 1 ? 'danger' : s === 2 ? 'success' : 'info')

const query = reactive({ pageNum: 1, pageSize: 10, status: undefined as number | undefined, source: undefined as number | undefined, keyword: '' })

async function load() {
  loading.value = true
  try {
    const res = await pageUnresolved({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status,
      source: query.source,
      keyword: query.keyword || undefined
    })
    records.value = res.records
    total.value = res.total
    stats.value = await unresolvedStats()
  } finally {
    loading.value = false
  }
}

function reset() {
  query.status = undefined
  query.source = undefined
  query.keyword = ''
  query.pageNum = 1
  load()
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

function copyQuestion(row: UnresolvedQuestion) {
  navigator.clipboard?.writeText(row.question)
  ElMessage.success('已复制问题，可直接粘贴到知识库新增片段')
}

/** 导出「待处理」问题清单：由脚本喂给 run_eval 重跑评估，验证补完知识是否真的答上了 */
function exportRetest() {
  const pending = records.value.filter((r) => r.status === 1)
  if (!pending.length) {
    ElMessage.warning('当前页没有待处理的问题')
    return
  }
  const header = 'id,type,question,gold_chunk_ids,gold_source_urls,gold_keywords,expect,notes\n'
  const body = pending
    .map((r, i) => `R${String(i + 1).padStart(2, '0')},normal,"${r.question.replace(/"/g, '""')}",,,,answer,复测：来自未解决问题池`)
    .join('\n')
  const blob = new Blob(['﻿' + header + body], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `复测清单_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出，用 tools/eval/run_eval.py --set <该文件> 重跑即可')
}

const dialog = reactive({
  visible: false,
  id: 0,
  question: '',
  status: 2,
  knowledgeChunkId: undefined as number | undefined,
  remark: '',
  saving: false
})

function openHandle(row: UnresolvedQuestion) {
  dialog.id = row.id
  dialog.question = row.question
  dialog.status = row.status === 1 ? 2 : row.status
  dialog.knowledgeChunkId = row.knowledgeChunkId ?? undefined
  dialog.remark = row.remark ?? ''
  dialog.visible = true
}

function goKnowledge() {
  navigator.clipboard?.writeText(dialog.question)
  ElMessage.success('问题已复制，到知识库新增片段后回来填片段 ID')
  router.push('/admin/knowledge')
}

async function save() {
  dialog.saving = true
  try {
    await handleUnresolved(dialog.id, {
      status: dialog.status,
      knowledgeChunkId: dialog.status === 2 ? dialog.knowledgeChunkId : undefined,
      remark: dialog.remark
    })
    ElMessage.success('已保存')
    dialog.visible = false
    load()
  } finally {
    dialog.saving = false
  }
}

onMounted(load)
</script>

<style scoped>
.mb-16 {
  margin-bottom: 16px;
}
.mt-12 {
  margin-top: 12px;
}
.stat-row {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}
.stat-card {
  flex: 1;
  min-width: 110px;
  padding: 12px 16px;
  border-radius: 10px;
  background: #f7f8fc;
  border: 1px solid #eef0f6;
  text-align: center;
}
.stat-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.2;
}
.stat-num.small {
  font-size: 19px;
}
.stat-label {
  margin-top: 2px;
  font-size: 12px;
  color: #7a8090;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.q-text {
  font-weight: 500;
  line-height: 1.5;
}
.muted {
  color: #c0c4cc;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.dialog-q {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f7f8fc;
  font-weight: 600;
}
.tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.pop p {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}
</style>
