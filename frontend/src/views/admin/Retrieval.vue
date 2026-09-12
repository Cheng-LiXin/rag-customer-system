<template>
  <div>
    <el-card class="mb-16">
      <div class="head">
        <div>
          <div class="title">
            <el-icon><Search /></el-icon>
            检索策略
            <el-tag size="small" effect="dark">{{ MODE_LABEL[status.mode] || status.mode }}</el-tag>
            <el-tag size="small" effect="plain" type="info">
              取值来源：{{ SOURCE_LABEL[status.source] || status.source }}
            </el-tag>
          </div>
          <div class="sub">
            四种模式对应四组对比实验：纯向量 / BM25 词面 / RRF 混合融合 / bge-reranker 重排。
            切换**立即生效**，无需重启 —— 答辩可现场切换对比。
          </div>
        </div>
        <div class="right">
          <el-radio-group v-model="selected" @change="onSwitch">
            <el-radio-button v-for="m in available" :key="m" :value="m">
              {{ MODE_LABEL[m] || m }}
            </el-radio-button>
          </el-radio-group>
          <el-button v-if="status.source === 'runtime'" link type="primary" @click="onClear">
            恢复配置默认值
          </el-button>
        </div>
      </div>

      <el-descriptions :column="3" border size="small" class="mt-16">
        <el-descriptions-item label="BM25 语料规模">
          {{ status.corpusSize }} 条
        </el-descriptions-item>
        <el-descriptions-item label="可选模式">
          {{ available.join(' / ') }}
        </el-descriptions-item>
        <el-descriptions-item label="配置打底">
          {{ status.configMode || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="mt-12"
        title="注意：BM25 独立模式下没有向量相似度，因此「拒答阈值」不生效 —— 超纲问题不会被拒答。混合与重排模式都能从向量那一路拿到该值。"
      />
    </el-card>

    <el-card>
      <div class="toolbar">
        <el-input v-model="probeQuery" placeholder="输入一个问题，看看四种模式各召回什么" style="width: 380px" @keyup.enter="probe" />
        <el-button type="primary" :icon="Search" @click="probe">检索预览</el-button>
        <div style="flex: 1"></div>
        <el-button :icon="Refresh" @click="rebuildCorpus">重建 BM25 语料</el-button>
      </div>

      <el-table :data="probeRows" v-loading="probing" stripe size="small">
        <el-table-column label="模式" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ MODE_LABEL[row.mode] || row.mode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column prop="chunkId" label="片段ID" width="90" />
        <el-table-column label="相似度" width="100">
          <template #default="{ row }">
            <span v-if="row.score != null">{{ Number(row.score).toFixed(4) }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="片段标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="contentPreview" label="内容预览" min-width="260" show-overflow-tooltip />
      </el-table>
      <div v-if="!probeRows.length" class="empty-tip">
        输入问题后点「检索预览」，会把四种模式各取 top-5 并排展示 —— 能直观看到 BM25 补了什么、
        重排改变了哪些顺序。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import http from '@/api/http'
import { retrievePreviewAll } from '@/api/retrieval'

const MODE_LABEL: Record<string, string> = {
  vector: '纯向量',
  bm25: 'BM25 词面',
  rrf: 'RRF 融合',
  rerank: 'reranker 重排'
}
const SOURCE_LABEL: Record<string, string> = {
  runtime: '运行时切换',
  config: '配置文件',
  request: '单次请求覆盖'
}

const status = reactive({
  mode: 'vector',
  source: 'config',
  configMode: '',
  corpusSize: 0
})
const available = ref<string[]>(['vector', 'bm25', 'rrf', 'rerank'])
const selected = ref('vector')

const probeQuery = ref('校园卡怎么充值')
const probing = ref(false)
const probeRows = ref<Array<Record<string, unknown>>>([])

async function loadStatus() {
  const data = await http.get<any, Record<string, any>>('/admin/retrieval/status')
  Object.assign(status, data)
  status.configMode = data.mode
  if (Array.isArray(data.available)) available.value = data.available
  selected.value = data.mode
}

async function onSwitch(val: string | number | boolean | undefined) {
  const mode = String(val)
  const res = await http.post<any, Record<string, any>>('/admin/retrieval/mode', { mode })
  Object.assign(status, res)
  ElMessage.success(`已切换到「${MODE_LABEL[mode] || mode}」`)
}

async function onClear() {
  const res = await http.post<any, Record<string, any>>('/admin/retrieval/mode', { mode: '' })
  Object.assign(status, res)
  ElMessage.success('已恢复配置默认模式')
}

async function rebuildCorpus() {
  const res = await http.post<any, Record<string, any>>('/admin/retrieval/rebuild', {})
  Object.assign(status, res)
  ElMessage.success(`BM25 语料已重建：${res.corpusSize} 条`)
}

async function probe() {
  if (!probeQuery.value.trim()) return
  probing.value = true
  try {
    const rows = await retrievePreviewAll(probeQuery.value.trim(), 5)
    probeRows.value = rows
  } finally {
    probing.value = false
  }
}

onMounted(async () => {
  await loadStatus()
  await probe()
})
</script>

<style scoped>
.mb-16 {
  margin-bottom: 16px;
}
.mt-12 {
  margin-top: 12px;
}
.mt-16 {
  margin-top: 16px;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}
.title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}
.sub {
  margin-top: 6px;
  font-size: 13px;
  color: #7a8090;
  max-width: 680px;
  line-height: 1.6;
}
.right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.muted {
  color: #c0c4cc;
}
.empty-tip {
  padding: 18px;
  color: #909399;
  font-size: 13px;
  line-height: 1.8;
  text-align: center;
}
</style>
