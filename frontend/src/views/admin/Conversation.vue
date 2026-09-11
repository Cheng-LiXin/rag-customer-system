<template>
  <div>
    <el-card class="mb-16">
      <div class="toolbar">
        <el-select v-model="query.type" placeholder="全部类型" clearable style="width: 150px" @change="load">
          <el-option label="机器人" value="AUTO" />
          <el-option label="人工" value="HUMAN" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="load">
          <el-option label="进行中" :value="1" />
          <el-option label="已结束" :value="2" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
      </div>
    </el-card>

    <el-card>
      <el-table :data="records" v-loading="loading" stripe @sort-change="onSortChange">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <template v-if="row.userId === 0 || !row.userId">
              <el-tag size="small" type="info" effect="plain">游客</el-tag>
            </template>
            <template v-else>
              <div>{{ row.userName || ('用户 #' + row.userId) }}</div>
              <div class="user-id-sub">ID {{ row.userId }}</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sessionType === 'HUMAN' ? 'warning' : 'primary'">
              {{ row.sessionType === 'HUMAN' ? '人工' : '机器人' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '进行中' : '已结束' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理客服" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.agentId">{{ row.agentId }}</span>
            <span v-else style="color: #b6bcc9">-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="lastReplyTime"
          label="回复时间"
          width="170"
          sortable="custom"
          :sort-orders="['ascending', 'descending']"
        >
          <template #default="{ row }">
            <span v-if="row.lastReplyTime">{{ row.lastReplyTime }}</span>
            <span v-else style="color: #b6bcc9">-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="createTime"
          label="创建时间"
          width="170"
          sortable="custom"
          :sort-orders="['ascending', 'descending']"
        />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openMessages(row)">查看消息</el-button>
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

    <!-- 消息查看弹窗 -->
    <el-dialog v-model="msgDialog.visible" title="会话消息" width="720px">
      <div class="msg-list" v-loading="msgDialog.loading">
        <div
          v-for="m in msgDialog.messages"
          :key="m.id"
          class="msg-item"
          :class="{ 'is-self': m.senderType === 'AGENT' }"
        >
          <div class="msg-sender">{{ senderName(m.senderType) }}</div>
          <MarkdownText v-if="m.senderType === 'AI'" :text="m.content" />
          <div v-else class="msg-content">{{ m.content }}</div>
          <div class="msg-time">{{ m.createTime }}</div>
        </div>
        <el-empty v-if="!msgDialog.loading && msgDialog.messages.length === 0" description="暂无消息" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'
import { pageConversations, conversationMessages } from '@/api/conversation'
import type { Conversation, Message } from '@/types'
import MarkdownText from '@/components/MarkdownText.vue'

const loading = ref(false)
const records = ref<Conversation[]>([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  type: '',
  status: undefined as number | undefined,
  sortBy: '',
  sortOrder: 'desc'
})

async function load() {
  loading.value = true
  try {
    const res = await pageConversations({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      type: query.type || undefined,
      status: query.status,
      sortBy: query.sortBy || undefined,
      sortOrder: query.sortOrder
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.type = ''
  query.status = undefined
  query.pageNum = 1
  query.sortBy = ''
  query.sortOrder = 'desc'
  load()
}

function onPageChange(p: number) {
  query.pageNum = p
  load()
}

function onSortChange({ prop, order }: { prop: string; order: string | null }) {
  if (order === 'ascending') {
    query.sortBy = prop
    query.sortOrder = 'asc'
  } else if (order === 'descending') {
    query.sortBy = prop
    query.sortOrder = 'desc'
  } else {
    query.sortBy = ''
    query.sortOrder = 'desc'
  }
  query.pageNum = 1
  load()
}

const msgDialog = reactive({
  visible: false,
  loading: false,
  messages: [] as Message[]
})

function senderName(s: string) {
  if (s === 'USER') return '用户'
  if (s === 'AI') return '机器人'
  if (s === 'AGENT') return '客服'
  return s
}

async function openMessages(row: Conversation) {
  msgDialog.visible = true
  msgDialog.loading = true
  msgDialog.messages = []
  try {
    msgDialog.messages = await conversationMessages(row.id)
  } finally {
    msgDialog.loading = false
  }
}

onMounted(load)
</script>

<style scoped>
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.user-id-sub {
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.4;
}

.msg-list {
  max-height: 480px;
  overflow-y: auto;
}

.msg-item {
  margin-bottom: 14px;
  padding: 10px 14px;
  background: #f7f8fc;
  border-radius: 10px;
}

.msg-item.is-self {
  background: #eef1ff;
}

.msg-sender {
  font-size: 12px;
  color: var(--el-color-primary);
  font-weight: 600;
  margin-bottom: 4px;
}

.msg-content {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.msg-time {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 6px;
}
</style>
