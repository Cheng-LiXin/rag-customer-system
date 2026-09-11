<template>
  <div class="wb-page">
    <!-- 顶部 -->
    <header class="wb-header">
      <div class="wb-brand">
        <span class="wb-logo">🎧</span>
        <div>
          <div class="wb-title">客服工作台</div>
          <div class="wb-sub">{{ auth.displayName }} · 会话数 {{ mine.length }}</div>
        </div>
      </div>
      <div class="wb-actions">
        <el-tag :type="wsConnected ? 'success' : 'danger'" effect="light" size="small">
          {{ wsConnected ? '在线' : '连接中' }}
        </el-tag>
        <el-button text :icon="User" @click="router.push('/profile')">个人信息</el-button>
        <el-button text :icon="DataLine" @click="router.push('/admin/statistics')">数据统计</el-button>
        <el-button text type="danger" @click="logout">退出</el-button>
      </div>
    </header>

    <!-- 主体 -->
    <div class="wb-body">
      <!-- 左：会话列表 -->
      <aside class="wb-aside">
        <div class="aside-section sec-progress">
          <div class="aside-title">
            <span>进行中（{{ mine.length }}）</span>
            <el-button link :icon="Refresh" @click="loadWorkbench" />
          </div>
          <div class="conv-list">
            <div
              v-for="c in mine"
              :key="c.id"
              class="conv-item"
              :class="{ active: activeConvId === c.id }"
              @click="openConversation(c)"
            >
              <div class="conv-title">
                <span class="conv-title-text">{{ c.title || '（无标题）' }}</span>
                <span v-if="c.unread" class="unread-badge">{{ c.unread }}</span>
              </div>
              <div class="conv-meta">会话 #{{ c.id }} · {{ convLabel(c) }}</div>
            </div>
            <el-empty v-if="mine.length === 0" description="暂无进行中会话" :image-size="60" />
          </div>
        </div>

        <div class="aside-section sec-queue">
          <div class="aside-title"><span>排队中（{{ queue.length }}）</span></div>
          <div class="conv-list">
            <div
              v-for="c in queue"
              :key="c.id"
              class="conv-item queue-item"
              :class="{ active: activeConvId === c.id }"
              @click="openConversation(c)"
            >
              <div class="conv-title">{{ c.title || '（无标题）' }}</div>
              <div class="conv-meta">会话 #{{ c.id }}</div>
            </div>
            <el-empty v-if="queue.length === 0" description="无排队" :image-size="60" />
          </div>
        </div>

        <div class="aside-section sec-ended">
          <div class="aside-title"><span>已结束（{{ ended.length }}）</span></div>
          <div class="conv-list">
            <div
              v-for="c in ended"
              :key="c.id"
              class="conv-item ended-item"
              :class="{ active: activeConvId === c.id }"
              @click="openConversation(c)"
            >
              <div class="conv-title">
                <span class="conv-title-text">{{ c.title || '（无标题）' }}</span>
                <el-button link type="primary" size="small" @click.stop="reopenEnded(c)">
                  再次接待
                </el-button>
              </div>
              <div class="conv-meta">会话 #{{ c.id }} · {{ convLabel(c) }}</div>
            </div>
            <el-empty v-if="ended.length === 0" description="暂无已结束会话" :image-size="60" />
          </div>
        </div>

        <el-divider />

        <div class="aside-section">
          <div class="aside-title">
            <span>我的工单</span>
            <el-button link type="primary" size="small" :icon="Plus" @click="ticketRef?.openCreate()">新建</el-button>
          </div>
          <AgentTicket ref="ticketRef" :conversation-id="activeConvId" />
        </div>
      </aside>

      <!-- 右：聊天窗口 -->
      <section class="wb-chat">
        <template v-if="activeConvId">
          <div class="chat-head">
            <div class="chat-head-left">
              <div class="chat-title">{{ activeCustomerName || ('会话 #' + activeConvId) }}</div>
              <div class="chat-sub" v-if="activeCustomerName">会话 #{{ activeConvId }}</div>
            </div>
            <div class="chat-head-actions">
              <el-button size="small" type="warning" plain @click="ticketRef?.openCreate()">创建工单</el-button>
              <el-button v-if="!activeEnded" size="small" type="danger" plain @click="closeActive">结束会话</el-button>
              <el-button v-else size="small" type="primary" @click="reopenActive">再次接待</el-button>
            </div>
          </div>
          <div ref="msgRef" class="chat-msgs">
            <div
              v-for="m in activeMessages"
              :key="m.id"
              class="c-msg"
              :class="m.self ? 'self' : ''"
            >
              <div class="c-sender">{{ m.sender }}</div>
              <div class="c-bubble">
                <MarkdownText v-if="m.sender === '机器人'" :text="m.content" />
                <template v-else>{{ m.content }}</template>
              </div>
              <div class="c-time">{{ m.time }}</div>
            </div>
          </div>
          <div class="chat-input">
            <!-- 文本框上方的小气泡：点开选择 emoji 插入回复 -->
            <div class="emoji-tool">
              <!-- 必须显式给 width：el-popover 的 width 默认 150，且会被内联到 popper 上（不是自适应！）。
                   emoji 字形 22px 下宽 30.2px + 6px×2 内边距 = 每格最小 42.2px，8 列需 351.6px，
                   加 10px×2 内边距与 1px×2 边框（border-box）→ 最小 374px；取 380 留余量。 -->
              <el-popover placement="top-start" trigger="click" :width="380" popper-class="emoji-popper">
                <template #reference>
                  <button type="button" class="emoji-bubble" :disabled="activeEnded" title="插入表情">😊</button>
                </template>
                <div class="emoji-grid">
                  <button
                    v-for="e in emojis"
                    :key="e"
                    type="button"
                    class="emoji-cell"
                    @click="appendEmoji(e)"
                  >{{ e }}</button>
                </div>
              </el-popover>
            </div>
            <div class="chat-input-row">
              <el-input
                v-model="draft"
                type="textarea"
                :rows="1"
                :autosize="{ minRows: 1, maxRows: 4 }"
                resize="none"
                :disabled="activeEnded"
                :placeholder="activeEnded ? '该会话已结束，点击「再次接待」继续' : '输入回复内容，Enter 发送'"
                @keydown.enter.exact.prevent="sendAgentMessage"
              />
              <el-button type="primary" :disabled="activeEnded || !draft.trim()" @click="sendAgentMessage">发送</el-button>
            </div>
          </div>
        </template>
        <el-empty v-else description="点击左侧会话开始接待" />
      </section>

      <!-- 最右：客户名片 -->
      <aside v-if="activeConvId" class="wb-profile">
        <div class="prof-head">客户名片</div>
        <div v-loading="customerLoading" class="prof-body">
          <template v-if="customer">
            <template v-if="customer.guest">
              <div class="prof-user">
                <el-avatar :size="64" class="prof-avatar">{{ avatarTextFor('客') }}</el-avatar>
                <div class="prof-name">游客</div>
                <div class="prof-sub">未登录访客会话</div>
              </div>
              <div class="prof-empty-tip">该会话为游客发起，暂无账号资料</div>
            </template>
            <template v-else>
              <div class="prof-user">
                <el-avatar :size="64" class="prof-avatar">{{ avatarTextFor(customer.nickname || customer.username || '') }}</el-avatar>
                <div class="prof-name">{{ customer.nickname || customer.username }}</div>
                <div class="prof-sub">@{{ customer.username }}</div>
              </div>
              <div class="prof-tags">
                <el-tag v-if="customer.identity" size="small" type="warning" effect="light">
                  {{ customer.identity }}
                </el-tag>
                <el-tag v-if="customer.status === 1" size="small" type="success" effect="light">正常</el-tag>
                <el-tag v-else-if="customer.status === 0" size="small" type="danger" effect="light">已禁用</el-tag>
              </div>
              <div class="prof-list">
                <div v-if="customer.province || customer.city" class="prof-item">
                  <span class="k">所在地区</span>
                  <span class="v">{{ customer.province }}{{ customer.city ? ' ' + customer.city : '' }}</span>
                </div>
                <div v-if="customer.email" class="prof-item">
                  <span class="k">邮箱</span>
                  <span class="v">{{ customer.email }}</span>
                </div>
                <div v-if="customer.phone" class="prof-item">
                  <span class="k">手机号</span>
                  <span class="v">{{ customer.phone }}</span>
                </div>
                <div class="prof-item">
                  <span class="k">账号ID</span>
                  <span class="v">{{ customer.userId }}</span>
                </div>
                <div class="prof-item">
                  <span class="k">注册时间</span>
                  <span class="v">{{ fmtDate(customer.createTime) }}</span>
                </div>
              </div>
            </template>
          </template>
          <el-empty v-else-if="!customerLoading" description="暂无客户资料" :image-size="50" />
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, DataLine, Plus, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { workbench, closeConversation, markRead, reopenConversation, type WorkbenchConversation } from '@/api/agent'
import { conversationMessages, conversationCustomer, type CustomerProfile } from '@/api/conversation'
import type { Message } from '@/types'
import MarkdownText from '@/components/MarkdownText.vue'
import AgentTicket from './AgentTicket.vue'

interface ViewMsg {
  id: number
  content: string
  sender: string
  self: boolean
  time: string
}

const router = useRouter()
const auth = useAuthStore()

const agentId = ref(0)
const onlineAgents = ref<number[]>([])
const mine = ref<WorkbenchConversation[]>([])
const queue = ref<WorkbenchConversation[]>([])
const ended = ref<WorkbenchConversation[]>([])
const activeConvId = ref<number | null>(null)
const activeEnded = ref(false)
const activeMessages = ref<ViewMsg[]>([])
const activeCustomerName = ref('')
const customer = ref<CustomerProfile | null>(null)
const customerLoading = ref(false)
const draft = ref('')
const msgRef = ref<HTMLElement>()
const emojis = [
  '😀', '😁', '😂', '🤣', '😊', '😇', '🙂', '😉', '😍', '🥰', '😘', '😜',
  '🤗', '🤔', '🙃', '😴', '😅', '😆', '😢', '😭', '😤', '👍', '👌', '🙏',
  '👏', '💪', '🤝', '✌️', '🫡', '🎉', '❤️', '💙', '🔥', '✨', '✅', '❌',
  '❗', '❓', '🎓', '📚', '🏫', '💡', '⏰', '📞', '🆗', '💰', '🚌', '📢'
]

/** 点表情把 emoji 追加到回复草稿末尾。 */
function appendEmoji(e: string) {
  if (activeEnded.value) return
  draft.value += e
}
const wsConnected = ref(false)
const ticketRef = ref<InstanceType<typeof AgentTicket> | null>(null)

let ws: WebSocket | null = null

async function loadWorkbench() {
  try {
    const res = await workbench()
    agentId.value = res.agentId
    onlineAgents.value = res.onlineAgents
    mine.value = res.mine
    queue.value = res.queue
    ended.value = res.ended || []
  } catch {
    /* ignore */
  }
}

function senderOf(senderType: string) {
  if (senderType === 'USER') return '用户'
  if (senderType === 'AGENT') return '我'
  return '机器人'
}

/** 会话归属客户显示名：优先昵称/用户名（后端已 join 回填），游客退化为「游客」 */
function convLabel(c: WorkbenchConversation) {
  if (c.userName) return c.userName
  return c.userId === 0 || !c.userId ? '游客' : `用户 #${c.userId}`
}

function fmtDate(s?: string) {
  if (!s) return '—'
  return s.replace('T', ' ').slice(0, 10) || '—'
}

function avatarTextFor(name?: string) {
  return (name || '客').slice(0, 1).toUpperCase()
}

async function loadCustomer(convId: number) {
  customerLoading.value = true
  try {
    const res = await conversationCustomer(convId)
    customer.value = res
    if (!res.guest) {
      activeCustomerName.value = res.nickname || res.username || activeCustomerName.value
    }
  } catch {
    customer.value = null
  } finally {
    customerLoading.value = false
  }
}

async function openConversation(c: WorkbenchConversation) {
  activeConvId.value = c.id
  activeEnded.value = c.status === 2
  activeMessages.value = []
  activeCustomerName.value = convLabel(c)
  customer.value = null
  c.unread = 0
  markRead(c.id).catch(() => {})
  loadCustomer(c.id)
  try {
    const msgs: Message[] = await conversationMessages(c.id)
    activeMessages.value = msgs.map((m) => ({
      id: m.id,
      content: m.content,
      sender: senderOf(m.senderType),
      self: m.senderType === 'AGENT',
      time: m.createTime || ''
    }))
  } catch {
    /* ignore */
  }
  nextTick(() => {
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  })
}

function sendAgentMessage() {
  const content = draft.value.trim()
  if (!content || !activeConvId.value || activeEnded.value) return
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    ElMessage.warning('连接已断开，请刷新页面')
    return
  }
  ws.send(JSON.stringify({ type: 'message', conversationId: activeConvId.value, content }))
  activeMessages.value.push({
    id: Date.now(),
    content,
    sender: '我',
    self: true,
    time: '刚刚'
  })
  draft.value = ''
  nextTick(() => {
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  })
}

async function closeActive() {
  if (!activeConvId.value) return
  await closeConversation(activeConvId.value)
  ElMessage.success('会话已结束')
  activeConvId.value = null
  activeEnded.value = false
  activeMessages.value = []
  activeCustomerName.value = ''
  customer.value = null
  loadWorkbench()
}

async function reopenEnded(c: WorkbenchConversation) {
  await reopenConversation(c.id)
  ElMessage.success('已重新接待')
  c.status = 1
  loadWorkbench()
  openConversation(c)
}

async function reopenActive() {
  if (!activeConvId.value) return
  await reopenConversation(activeConvId.value)
  ElMessage.success('已重新接待')
  activeEnded.value = false
  loadWorkbench()
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/ws/customer-service`
  ws = new WebSocket(url)
  ws.onopen = () => {
    wsConnected.value = true
    ws?.send(JSON.stringify({ type: 'register', role: 'agent', agentId: agentId.value }))
  }
  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data)
      if (msg.type === 'assigned') {
        ElMessage.success(`新会话 #${msg.conversationId} 已分配给你`)
        loadWorkbench()
      } else if (msg.type === 'closed') {
        ElMessage.info(
          msg.reason === 'timeout'
            ? `会话 #${msg.conversationId} 因长时间未对话已自动结束`
            : `会话 #${msg.conversationId} 已结束`
        )
        if (activeConvId.value === msg.conversationId) {
          activeConvId.value = null
          activeEnded.value = false
          activeMessages.value = []
          activeCustomerName.value = ''
          customer.value = null
        }
        loadWorkbench()
      } else if (msg.type === 'message') {
        if (msg.conversationId === activeConvId.value) {
          activeMessages.value.push({
            id: Date.now(),
            content: msg.content,
            sender: '用户',
            self: false,
            time: '刚刚'
          })
          markRead(msg.conversationId).catch(() => {})
          // 用户发来新消息时刷新名片（其昵称/地区等信息可能刚在个人中心更新过）
          loadCustomer(msg.conversationId)
          nextTick(() => {
            if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
          })
        } else {
          ElMessage.info(`会话 #${msg.conversationId} 有新消息`)
          loadWorkbench()
        }
      }
    } catch {
      /* ignore */
    }
  }
  ws.onclose = () => {
    wsConnected.value = false
  }
}

function logout() {
  ws?.close()
  auth.logout()
  router.push('/login')
}

onMounted(async () => {
  await loadWorkbench()
  connectWs()
})

onBeforeUnmount(() => {
  ws?.close()
  ws = null
})
</script>

<style scoped>
.wb-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f6fb;
}

.wb-header {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #eef0f6;
}

.wb-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wb-logo {
  font-size: 26px;
}

.wb-title {
  font-size: 16px;
  font-weight: 700;
}

.wb-sub {
  font-size: 12px;
  color: var(--text-sub);
}

.wb-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wb-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.wb-aside {
  width: 300px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #eef0f6;
  overflow-y: auto;
  padding: 16px;
}

.aside-section {
  margin-bottom: 20px;
}

.aside-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: #5b6069;
  margin-bottom: 10px;
}

.conv-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.conv-item {
  padding: 10px 12px;
  border: 1px solid #eef0f6;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.conv-item:hover {
  border-color: var(--el-color-primary);
}

.conv-item.active {
  border-color: var(--el-color-primary);
  background: #eef1ff;
}

.queue-item {
  background: #fffbf0;
}

.ended-item {
  background: #fafbfc;
}

.conv-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}

.conv-title-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unread-badge {
  flex-shrink: 0;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: #f56c6c;
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

.conv-meta {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 4px;
}

.wb-chat {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chat-head {
  height: 52px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #eef0f6;
}

.chat-head-left {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.chat-title {
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-sub {
  font-size: 12px;
  color: var(--text-sub);
  font-weight: 400;
}

.chat-head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* 客户名片 */
.wb-profile {
  width: 270px;
  flex-shrink: 0;
  background: #fff;
  border-left: 1px solid #eef0f6;
  overflow-y: auto;
  padding: 16px;
}

.prof-head {
  font-size: 14px;
  font-weight: 600;
  color: #5b6069;
  margin-bottom: 14px;
}

.prof-body {
  min-height: 160px;
}

.prof-user {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 0 14px;
}

.prof-avatar {
  background: var(--brand-gradient);
  color: #fff;
  font-size: 26px;
  font-weight: 700;
}

.prof-name {
  font-size: 18px;
  font-weight: 700;
}

.prof-sub {
  font-size: 13px;
  color: var(--text-sub);
}

.prof-tags {
  display: flex;
  justify-content: center;
  gap: 6px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px dashed #eef0f6;
}

.prof-list {
  padding-top: 10px;
}

.prof-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 2px;
  font-size: 13px;
}

.prof-item + .prof-item {
  border-top: 1px dashed #f2f3f8;
}

.prof-item .k {
  flex-shrink: 0;
  color: var(--text-sub);
}

.prof-item .v {
  text-align: right;
  word-break: break-all;
}

.prof-empty-tip {
  margin-top: 10px;
  text-align: center;
  font-size: 12px;
  color: #b6bcc9;
}

.chat-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.c-msg {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.c-msg.self {
  align-items: flex-end;
}

.c-sender {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 4px;
}

.c-bubble {
  max-width: 70%;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid #eef0f6;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.c-msg.self .c-bubble {
  background: var(--brand-gradient);
  color: #fff;
  border: none;
}

.c-time {
  font-size: 12px;
  color: #b6bcc9;
  margin-top: 4px;
}

.chat-input {
  flex-shrink: 0;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 14px 20px;
  background: #fff;
  border-top: 1px solid #eef0f6;
}

.chat-input :deep(.el-textarea__inner) {
  border-radius: 10px;
}
</style>

<style scoped>
/* ============ 清新渐变活力风 · 视觉润色（后置覆盖，结构与逻辑不变） ============ */
.wb-page {
  --wb-indigo: #5b7cfa;
  --wb-violet: #9a5cf5;
  --wb-pink: #d05cf0;
  --wb-grad: linear-gradient(135deg, var(--wb-indigo) 0%, var(--wb-violet) 52%, var(--wb-pink) 100%);
  position: relative;
  background: linear-gradient(165deg, #eff4ff 0%, #f8f0ff 46%, #e9f8ff 100%);
}

.wb-page::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--wb-grad);
  z-index: 20;
}

.wb-header {
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(228, 232, 246, 0.9);
  box-shadow: 0 4px 18px rgba(90, 110, 200, 0.06);
}

.wb-logo {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 21px;
  background: var(--wb-grad);
  color: #fff;
  border-radius: 13px;
  box-shadow: 0 6px 16px rgba(140, 92, 255, 0.35);
}

.wb-title {
  letter-spacing: 0.3px;
}

.wb-sub {
  color: #8e96ad;
}

.wb-actions :deep(.el-button) {
  font-weight: 500;
}

.wb-actions :deep(.el-tag) {
  border-radius: 999px;
}

/* 三栏：半透明白「玻璃」卡片，让页面渐变透出 */
.wb-aside {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(6px);
  border-right: 1px solid rgba(228, 232, 246, 0.85);
}

.wb-profile {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(6px);
  border-left: 1px solid rgba(228, 232, 246, 0.85);
}

/* 分栏标题：带彩色指示点 */
.aside-title {
  letter-spacing: 0.2px;
}

.aside-title > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.aside-title > span:first-child::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #aab3c8;
}

.sec-progress .aside-title > span:first-child::before {
  background: var(--wb-indigo);
  box-shadow: 0 0 0 4px rgba(91, 124, 250, 0.14);
}

.sec-queue .aside-title > span:first-child::before {
  background: #ffa940;
  box-shadow: 0 0 0 4px rgba(255, 169, 64, 0.16);
}

.sec-ended .aside-title > span:first-child::before {
  background: #9aa3b5;
}

/* 会话项卡片 */
.conv-list {
  gap: 10px;
}

.conv-item {
  position: relative;
  padding: 12px 12px 12px 16px;
  background: #fff;
  border: 1px solid #eef1f8;
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(70, 90, 160, 0.05);
  overflow: hidden;
}

.conv-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 3px;
  background: transparent;
  transition: all 0.2s ease;
}

.conv-item:hover {
  transform: translateY(-1px);
  border-color: #dfe5fb;
  box-shadow: 0 6px 16px rgba(90, 110, 200, 0.1);
}

.sec-progress .conv-item::before {
  background: var(--wb-indigo);
}

.sec-queue .conv-item {
  background: linear-gradient(180deg, #fff 0%, #fffdf5 100%);
  border-color: #f3ead2;
}

.sec-queue .conv-item::before {
  background: #ffa940;
}

.sec-ended .conv-item {
  background: #fafbfd;
}

.sec-ended .conv-item::before {
  background: #b9c2d4;
}

.conv-item.active {
  border-color: transparent;
  background: linear-gradient(135deg, #eef2ff 0%, #f7edff 100%);
  box-shadow: 0 6px 18px rgba(122, 92, 255, 0.18);
}

.conv-item.active::before {
  background: var(--wb-grad);
}

.sec-queue .conv-item.active {
  background: linear-gradient(135deg, #fff6e8 0%, #fdeee0 100%);
}

.unread-badge {
  background: var(--wb-grad);
  box-shadow: 0 2px 6px rgba(208, 92, 240, 0.4);
}

.conv-meta {
  color: #98a0b4;
}

/* 聊天区 */
.chat-head {
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(228, 232, 246, 0.9);
}

.chat-title {
  color: #2f3648;
}

.chat-msgs {
  padding: 20px 22px;
}

.c-bubble {
  background: #fff;
  border: none;
  box-shadow: 0 2px 10px rgba(70, 90, 160, 0.08);
  border-radius: 14px 14px 14px 4px;
}

.c-msg.self .c-bubble {
  background: var(--wb-grad);
  box-shadow: 0 4px 14px rgba(140, 92, 255, 0.28);
  border-radius: 14px 14px 4px 14px;
}

.c-msg {
  animation: wbMsgIn 0.22s ease both;
}

@keyframes wbMsgIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 底部输入区：浮动圆角卡片 */
.chat-input {
  margin: 4px 18px 16px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid #eef1f8;
  box-shadow: 0 4px 16px rgba(70, 90, 160, 0.08);
  padding: 12px 14px 12px 16px;
  gap: 12px;
}

/* 客户名片 */
.prof-head {
  letter-spacing: 0.2px;
  color: #4d5468;
}

.prof-user {
  position: relative;
  padding: 12px 0 18px;
}

.prof-avatar {
  background: var(--wb-grad);
  box-shadow: 0 0 0 4px rgba(154, 92, 245, 0.16), 0 6px 16px rgba(140, 92, 255, 0.3);
}

.prof-name {
  color: #333a4d;
}

.prof-item .k {
  color: #98a0b4;
}

/* 输入卡改为两行：上行 emoji 小气泡，下行 = 输入框 + 发送 */
.chat-input {
  flex-direction: column;
  align-items: stretch;
}

.chat-input-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.emoji-tool {
  display: flex;
}

.emoji-bubble {
  width: 30px;
  height: 30px;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: var(--wb-grad);
  color: #fff;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 4px 10px rgba(140, 92, 255, 0.25);
  transition: transform 0.15s ease;
}

.emoji-bubble:hover {
  transform: scale(1.12);
}

.emoji-bubble:disabled {
  filter: grayscale(1);
  opacity: 0.5;
  cursor: not-allowed;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px;
}

.emoji-cell {
  border: none;
  background: transparent;
  font-size: 22px;
  line-height: 1;
  padding: 6px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.1s ease, background 0.1s ease;
}

.emoji-cell:hover {
  background: #eef1ff;
  transform: scale(1.2);
}
</style>

<style>
/* emoji 弹窗被 teleport 到 body，scoped 样式覆盖不到，故用全局样式（类名唯一，无污染）：
   加宽内边距 + 四角圆角，容纳 8 列 × 6 行 emoji 不裁切 */
.emoji-popper.el-popover.el-popper {
  border-radius: 16px;
  padding: 10px;
  border: 1px solid #e6ecff;
  box-shadow: 0 10px 28px rgba(93, 122, 255, 0.18);
}
</style>
