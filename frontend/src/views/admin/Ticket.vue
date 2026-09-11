<template>
  <div>
    <el-card class="mb-16">
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="load">
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v" :value="Number(k)" />
        </el-select>
        <el-select v-model="query.priority" placeholder="全部优先级" clearable style="width: 150px" @change="load">
          <el-option v-for="(v, k) in priorityMap" :key="k" :label="v" :value="Number(k)" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
        <div style="flex: 1"></div>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建工单</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="120" show-overflow-tooltip />
        <el-table-column label="优先级" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="priorityTagType(row.priority)">
              {{ priorityMap[row.priority] || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">
              {{ statusMap[row.status] || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="assigneeId" label="处理人ID" width="90" align="center" />
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 1" link type="primary" @click="setStatus(row, 2)">处理</el-button>
            <el-button v-if="row.status === 2" link type="success" @click="setStatus(row, 3)">解决</el-button>
            <el-button v-if="row.status !== 4" link type="info" @click="setStatus(row, 4)">关闭</el-button>
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

    <!-- 工单详情弹窗 -->
    <el-dialog v-model="detailDialog.visible" title="工单详情" width="600px" destroy-on-close>
      <template v-if="detailDialog.ticket">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="工单ID">{{ detailDialog.ticket.id }}</el-descriptions-item>
          <el-descriptions-item label="关联会话">
            {{ detailDialog.ticket.conversationId ? '#' + detailDialog.ticket.conversationId : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTagType(detailDialog.ticket.status)">
              {{ statusMap[detailDialog.ticket.status || 0] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag size="small" :type="priorityTagType(detailDialog.ticket.priority)">
              {{ priorityMap[detailDialog.ticket.priority || 0] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ detailDialog.ticket.category || '-' }}</el-descriptions-item>
          <el-descriptions-item label="处理人ID">{{ detailDialog.ticket.assigneeId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="提交用户ID">{{ detailDialog.ticket.userId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detailDialog.ticket.createTime }}</el-descriptions-item>
          <el-descriptions-item label="更新时间" :span="2">{{ detailDialog.ticket.updateTime || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detailDialog.ticket.closeTime" label="关闭时间" :span="2">
            {{ detailDialog.ticket.closeTime }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detailDialog.ticket.description" class="detail-desc">
          <div class="desc-label">问题描述</div>
          <div class="desc-content">{{ detailDialog.ticket.description }}</div>
        </div>
      </template>
      <template #footer>
        <el-button v-if="detailDialog.ticket?.status === 1" type="primary" size="small" @click="setStatusAndClose(detailDialog.ticket!, 2)">开始处理</el-button>
        <el-button v-if="detailDialog.ticket?.status === 2" type="success" size="small" @click="setStatusAndClose(detailDialog.ticket!, 3)">标记解决</el-button>
        <el-button v-if="detailDialog.ticket?.status && detailDialog.ticket.status < 4" type="info" size="small" @click="setStatusAndClose(detailDialog.ticket!, 4)">关闭工单</el-button>
        <el-button @click="detailDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新建工单 -->
    <el-dialog v-model="createDialog.visible" title="新建工单" width="560px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="createForm.title" placeholder="请输入工单标题" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="createForm.category" placeholder="如：招生咨询" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="createForm.priority" style="width: 100%">
            <el-option v-for="(v, k) in priorityMap" :key="k" :label="v" :value="Number(k)" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="4" placeholder="问题描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import { pageTickets, createTicket, updateTicketStatus } from '@/api/ticket'
import type { Ticket } from '@/types'

const statusMap: Record<number, string> = {
  1: '待处理',
  2: '处理中',
  3: '已解决',
  4: '已关闭'
}

const priorityMap: Record<number, string> = {
  1: '低',
  2: '中',
  3: '高'
}

const loading = ref(false)
const records = ref<Ticket[]>([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  priority: undefined as number | undefined
})

function priorityTagType(p?: number) {
  if (p === 3) return 'danger'
  if (p === 2) return 'warning'
  return 'info'
}

function statusTagType(s?: number) {
  if (s === 1) return 'danger'
  if (s === 2) return 'warning'
  if (s === 3) return 'success'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await pageTickets({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status,
      priority: query.priority
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.status = undefined
  query.priority = undefined
  query.pageNum = 1
  load()
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

async function setStatus(row: Ticket, status: number) {
  await updateTicketStatus(row.id, status)
  ElMessage.success('状态已更新')
  load()
}

async function setStatusAndClose(row: Ticket, status: number) {
  await updateTicketStatus(row.id, status)
  ElMessage.success('状态已更新')
  detailDialog.visible = false
  load()
}

// 详情
const detailDialog = reactive({
  visible: false,
  ticket: null as Ticket | null
})

function openDetail(row: Ticket) {
  detailDialog.ticket = row
  detailDialog.visible = true
}

// 新建
const createFormRef = ref<FormInstance>()
const createDialog = reactive({ visible: false, saving: false })
const createForm = reactive({
  title: '',
  category: '',
  priority: 2,
  description: ''
})

const createRules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }]
}

function openCreate() {
  createForm.title = ''
  createForm.category = ''
  createForm.priority = 2
  createForm.description = ''
  createDialog.visible = true
}

async function saveCreate() {
  if (!createFormRef.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    createDialog.saving = true
    try {
      await createTicket({ ...createForm })
      ElMessage.success('工单已创建')
      createDialog.visible = false
      load()
    } finally {
      createDialog.saving = false
    }
  })
}

onMounted(load)
</script>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.detail-desc {
  margin-top: 16px;
}

.desc-label {
  font-size: 13px;
  font-weight: 600;
  color: #5b6069;
  margin-bottom: 6px;
}

.desc-content {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: #333;
  background: #f7f8fc;
  padding: 12px 14px;
  border-radius: 8px;
}
</style>
