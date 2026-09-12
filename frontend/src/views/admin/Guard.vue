<template>
  <div>
    <!-- 开关卡片：答辩演示的核心操作区（关 → 复现漏洞 → 开 → 拦截） -->
    <el-card class="mb-16">
      <div class="guard-head">
        <div class="guard-head-left">
          <div class="guard-title">
            <el-icon><Lock /></el-icon>
            注入防护
            <el-tag :type="status.enabled ? 'success' : 'danger'" effect="dark" size="small">
              {{ status.enabled ? '已开启' : '已关闭' }}
            </el-tag>
            <el-tag size="small" effect="plain" type="info">
              取值来源：{{ status.source === 'runtime' ? '运行时切换' : '配置文件' }}
            </el-tag>
          </div>
          <div class="guard-sub">
            三层检测：输入层（用户问题）→ 上下文层（检索到的片段）→ 输出层（最终答案）。
            关闭后三层全部失效，用于演示「无防护时被诱导承诺全额退款」。
          </div>
        </div>
        <div class="guard-head-right">
          <el-switch
            v-model="status.enabled"
            :loading="switching"
            size="large"
            active-text="开启"
            inactive-text="关闭"
            @change="onToggle"
          />
          <el-button v-if="status.source === 'runtime'" link type="primary" @click="onResetOverride">
            恢复配置默认值
          </el-button>
        </div>
      </div>

      <el-descriptions :column="3" border size="small" class="guard-levels">
        <el-descriptions-item label="输入层">
          <el-tag size="small" :type="status.inputLevel === 'block' ? 'danger' : 'info'">
            {{ status.inputLevel === 'block' ? '拦截' : '仅记录' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上下文层">
          <el-tag size="small" :type="status.contextLevel === 'block' ? 'danger' : 'info'">
            {{ status.contextLevel === 'block' ? '拦截' : '仅记录' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="输出层">
          <el-tag size="small" :type="status.outputLevel === 'block' ? 'danger' : 'info'">
            {{ status.outputLevel === 'block' ? '拦截' : '仅记录' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="mb-16"
      title="演示提醒：间接注入演示依赖知识库里的「DEMO-」演示条目。演示结束后请执行 tools/guard/demo_cleanup.ps1 清除，否则毒片段会一直留在知识库中。"
    />

    <!-- 审计事件 -->
    <el-card>
      <div class="toolbar">
        <el-select v-model="query.layer" placeholder="全部层级" clearable style="width: 160px" @change="load">
          <el-option label="输入层 input" value="input" />
          <el-option label="上下文层 context" value="context" />
          <el-option label="输出层 output" value="output" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <div style="flex: 1"></div>
        <span class="hint">共 {{ total }} 条命中记录（含「仅记录」档）</span>
      </div>

      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="层级" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="layerType(row.layer)">{{ layerText(row.layer) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.action === 'BLOCK' ? 'danger' : 'info'" effect="plain">
              {{ row.action === 'BLOCK' ? '拦截' : '仅记录' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ruleId" label="命中规则" width="170" />
        <el-table-column prop="question" label="触发问题" min-width="200" show-overflow-tooltip />
        <el-table-column label="命中原文" min-width="260">
          <template #default="{ row }">
            <span class="hit-text">{{ row.hitText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="污染片段" width="100">
          <template #default="{ row }">
            <span v-if="row.chunkId">{{ row.chunkId }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110">
          <template #default="{ row }">
            <span v-if="row.username">{{ row.username }}</span>
            <span v-else class="muted">游客</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, Refresh } from '@element-plus/icons-vue'
import { guardStatus, toggleGuard, pageGuardEvents, type GuardEvent, type GuardStatus } from '@/api/guard'

const loading = ref(false)
const switching = ref(false)
const records = ref<GuardEvent[]>([])
const total = ref(0)
const status = ref<GuardStatus>({
  enabled: false,
  source: 'config',
  configEnabled: false,
  runtimeSwitch: true,
  inputLevel: 'block',
  contextLevel: 'block',
  outputLevel: 'block'
})

const query = reactive({ pageNum: 1, pageSize: 10, layer: '' })

const LAYER_TEXT: Record<string, string> = { input: '输入层', context: '上下文层', output: '输出层' }
const LAYER_TYPE: Record<string, 'danger' | 'warning' | 'success'> = {
  input: 'danger',
  context: 'warning',
  output: 'success'
}
const layerText = (l: string) => LAYER_TEXT[l] || l
const layerType = (l: string) => LAYER_TYPE[l] || 'info'

async function loadStatus() {
  status.value = await guardStatus()
}

async function load() {
  loading.value = true
  try {
    const res = await pageGuardEvents({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      layer: query.layer || undefined
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function onToggle(val: boolean) {
  switching.value = true
  try {
    await ElMessageBox.confirm(
      val
        ? '开启后，命中注入规则的请求会被拦截并落入审计。'
        : '关闭后三层防护全部失效，注入攻击可直接生效（演示用）。确认关闭？',
      val ? '开启注入防护' : '关闭注入防护',
      { type: val ? 'info' : 'warning' }
    )
    status.value = await toggleGuard(val)
    ElMessage.success(val ? '注入防护已开启' : '注入防护已关闭')
  } catch {
    // 取消或失败：回读真实状态，避免开关视觉与后端不一致
    await loadStatus()
  } finally {
    switching.value = false
  }
}

async function onResetOverride() {
  status.value = await toggleGuard(null)
  ElMessage.success('已恢复为配置文件默认值')
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

onMounted(async () => {
  await loadStatus()
  await load()
})
</script>

<style scoped>
.mb-16 {
  margin-bottom: 16px;
}
.guard-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.guard-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}
.guard-sub {
  margin-top: 6px;
  font-size: 13px;
  color: #7a8090;
  max-width: 720px;
  line-height: 1.6;
}
.guard-head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.guard-levels {
  margin-top: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.hint {
  font-size: 13px;
  color: #909399;
}
.hit-text {
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}
.muted {
  color: #c0c4cc;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
