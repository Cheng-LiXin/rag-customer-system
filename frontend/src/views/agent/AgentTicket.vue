<template>
  <div class="agent-ticket">
    <div class="ticket-toolbar">
      <el-select v-model="filter.status" placeholder="全部状态" clearable size="small" style="width: 100px" @change="loadTickets">
        <el-option v-for="(v, k) in statusMap" :key="k" :label="v" :value="Number(k)" />
      </el-select>
      <el-button size="small" type="primary" :icon="Plus" @click="openCreate">新建工单</el-button>
    </div>

    <div class="ticket-list" v-loading="loading">
      <div
        v-for="t in tickets"
        :key="t.id"
        class="ticket-item"
        :class="{ active: activeTicketId === t.id }"
        @click="openTicket(t)"
      >
        <div class="ticket-title">
          <span class="ticket-title-text">{{ t.title }}</span>
          <el-tag size="small" :type="statusTagType(t.status)">{{ statusMap[t.status || 0] }}</el-tag>
        </div>
        <div class="ticket-meta">
          <span>工单 #{{ t.id }}</span>
          <el-tag v-if="t.priority" size="small" :type="priorityTagType(t.priority)" effect="plain">
            {{ priorityMap[t.priority] }}
          </el-tag>
          <span v-if="t.category">{{ t.category }}</span>
        </div>
      </div>
      <el-empty v-if="!loading && tickets.length === 0" description="暂无工单" :image-size="60" />
    </div>

    <!-- 工单详情 -->
    <el-dialog v-model="detailVisible" title="工单详情" width="520px" destroy-on-close>
      <template v-if="activeTicket">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="工单ID">{{ activeTicket.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTagType(activeTicket.status)">{{ statusMap[activeTicket.status || 0] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag size="small" :type="priorityTagType(activeTicket.priority)">{{ priorityMap[activeTicket.priority || 0] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ activeTicket.category || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联会话" :span="2">
            {{ activeTicket.conversationId ? '#' + activeTicket.conversationId : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ activeTicket.createTime }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="activeTicket.description" class="ticket-desc">
          <div class="desc-label">问题描述</div>
          <div class="desc-content">{{ activeTicket.description }}</div>
        </div>
      </template>
      <template #footer>
        <el-button v-if="activeTicket?.status === 1" type="primary" size="small" @click="doStatus(2)">开始处理</el-button>
        <el-button v-if="activeTicket?.status === 2" type="success" size="small" @click="doStatus(3)">标记解决</el-button>
        <el-button v-if="activeTicket?.status && activeTicket.status < 4" type="info" size="small" @click="doStatus(4)">关闭工单</el-button>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新建工单 -->
    <el-dialog v-model="createVisible" title="新建工单" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入工单标题" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如：招生咨询、教务问题" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority" style="width: 100%">
            <el-option v-for="(v, k) in priorityMap" :key="k" :label="v" :value="Number(k)" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联会话">
          <el-input-number v-model="form.conversationId" :min="0" placeholder="可选" style="width: 100%" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="问题详细描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { pageTickets, createTicket, updateTicketStatus } from '@/api/ticket'
import { useAuthStore } from '@/stores/auth'
import type { Ticket } from '@/types'

const props = defineProps<{
  conversationId?: number | null
}>()

const auth = useAuthStore()
const loading = ref(false)
const tickets = ref<Ticket[]>([])
const filter = reactive({ status: undefined as number | undefined })

const statusMap: Record<number, string> = { 1: '待处理', 2: '处理中', 3: '已解决', 4: '已关闭' }
const priorityMap: Record<number, string> = { 1: '低', 2: '中', 3: '高' }

function statusTagType(s?: number) {
  if (s === 1) return 'danger'
  if (s === 2) return 'warning'
  if (s === 3) return 'success'
  return 'info'
}

function priorityTagType(p?: number) {
  if (p === 3) return 'danger'
  if (p === 2) return 'warning'
  return 'info'
}

async function loadTickets() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: 1, pageSize: 50, assigneeId: auth.profile?.id }
    if (filter.status != null) params.status = filter.status
    const res = await pageTickets(params)
    tickets.value = res.records
  } finally {
    loading.value = false
  }
}

// 详情
const detailVisible = ref(false)
const activeTicketId = ref<number | null>(null)
const activeTicket = ref<Ticket | null>(null)

function openTicket(t: Ticket) {
  activeTicketId.value = t.id
  activeTicket.value = t
  detailVisible.value = true
}

async function doStatus(status: number) {
  if (!activeTicket.value) return
  await updateTicketStatus(activeTicket.value.id, status)
  ElMessage.success('状态已更新')
  detailVisible.value = false
  loadTickets()
}

// 新建
const createVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  title: '',
  category: '',
  priority: 2,
  description: '',
  conversationId: undefined as number | undefined
})
const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }]
}

function openCreate() {
  form.title = ''
  form.category = ''
  form.priority = 2
  form.description = ''
  form.conversationId = props.conversationId || undefined
  createVisible.value = true
}

async function doCreate() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      await createTicket({
        title: form.title,
        category: form.category,
        priority: form.priority,
        description: form.description,
        conversationId: form.conversationId || undefined,
        userId: auth.profile?.id
      })
      ElMessage.success('工单已创建')
      createVisible.value = false
      loadTickets()
    } finally {
      saving.value = false
    }
  })
}

// 当 conversationId 变化时预填
watch(() => props.conversationId, (val) => {
  if (val) form.conversationId = val
})

onMounted(loadTickets)

defineExpose({ loadTickets, openCreate })
</script>

<style scoped>
.agent-ticket {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.ticket-toolbar {
  display: flex;
  gap: 8px;
  padding: 0 0 10px 0;
}

.ticket-list {
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ticket-item {
  padding: 10px 12px;
  border: 1px solid #eef0f6;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.ticket-item:hover {
  border-color: var(--el-color-primary);
}

.ticket-item.active {
  border-color: var(--el-color-primary);
  background: #eef1ff;
}

.ticket-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}

.ticket-title-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ticket-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 4px;
}

.ticket-desc {
  margin-top: 12px;
}

.desc-label {
  font-size: 13px;
  font-weight: 600;
  color: #5b6069;
  margin-bottom: 4px;
}

.desc-content {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: #333;
}
</style>
