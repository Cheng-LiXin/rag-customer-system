<template>
  <div class="chat-page">
    <!-- 顶部导航 -->
    <header class="chat-header">
      <div class="header-left">
        <div class="logo">🎓</div>
        <div class="header-title">
          <span class="title-main">燕山大学智能客服</span>
          <span class="title-sub">基于 RAG 的智能客服系统</span>
        </div>
      </div>

      <div class="header-right">
        <el-button v-if="auth.isAdmin" text @click="router.push('/admin/statistics')">
          <el-icon style="margin-right: 4px"><Setting /></el-icon>
          管理后台
        </el-button>
        <el-button v-else-if="auth.isAgent" text @click="router.push('/agent')">
          <el-icon style="margin-right: 4px"><Service /></el-icon>
          客服工作台
        </el-button>

        <el-button v-if="!auth.isLoggedIn" type="primary" round @click="router.push('/login')">
          登录
        </el-button>

        <el-dropdown v-else @command="onCommand">
          <span class="user-chip">
            <el-avatar :size="32" class="user-avatar">{{ avatarText }}</el-avatar>
            <span class="user-name">{{ auth.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <el-icon style="margin-right: 4px"><User /></el-icon>
                个人信息
              </el-dropdown-item>
              <el-dropdown-item command="history">
                <el-icon style="margin-right: 4px"><Clock /></el-icon>
                历史对话
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- ==================== 首页（预设问题） ==================== -->
    <main v-if="mode === 'home'" class="home-body">
      <div class="home-hero">
        <div class="hero-emoji">🤖</div>
        <h1>您好，我是智能客服小助手</h1>
        <p class="hero-sub">可以解答关于招生政策、专业设置、校园生活等方面的问题</p>

        <div class="suggest-grid">
          <div
            v-for="q in suggestions"
            :key="q.text"
            class="suggest-card"
            @click="sendMessage(q.text)"
          >
            <span class="suggest-icon">{{ q.icon }}</span>
            <span class="suggest-text">{{ q.text }}</span>
          </div>
        </div>

        <div class="home-input">
          <div class="home-input-bar">
            <el-button class="transfer-btn" :icon="Service" @click="transferToHuman">
              转人工
            </el-button>
            <el-input
              v-model="input"
              type="textarea"
              :rows="1"
              :autosize="{ minRows: 1, maxRows: 5 }"
              resize="none"
              class="input-text"
              placeholder="也可以直接输入您的问题…"
              @keydown.enter.exact.prevent="sendMessage()"
            />
            <el-button
              type="primary"
              class="send-btn"
              :disabled="!canSend"
              :loading="loading"
              @click="sendMessage()"
            >
              发送
            </el-button>
          </div>
          <div class="input-tip">
            <template v-if="!auth.isLoggedIn">（转人工需登录，普通问答无需登录）· </template>
            Enter 发送，Shift+Enter 换行
          </div>
        </div>
      </div>
    </main>

    <!-- ==================== 聊天模式 ==================== -->
    <main v-else ref="scrollRef" class="chat-body">
      <div class="chat-inner">
        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row"
          :class="m.role === 'user' ? 'is-user' : 'is-bot'"
        >
          <div v-if="m.role !== 'user'" class="msg-avatar bot-avatar">🤖</div>
          <div class="msg-body">
            <div class="msg-meta" v-if="m.role !== 'user'">
              <span class="meta-name">{{ roleName(m.role) }}</span>
              <el-tag v-if="m.fromCache" size="small" type="info" effect="plain">命中缓存</el-tag>
              <el-tag v-if="m.intentCategory" size="small" type="success" effect="plain">
                意图：{{ m.intentCategory }}
              </el-tag>
            </div>
            <div class="bubble" :class="m.role === 'user' ? 'bubble-user' : 'bubble-bot'">
              <span v-if="m.streaming" class="typing">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              </span>
              <!-- 机器人回答按 Markdown 渲染（加粗/列表/代码等），用户与人工客服消息保持原文 -->
              <MarkdownText v-else-if="m.role === 'ai' && !m.error" :text="m.content" />
              <span v-else class="bubble-text" :class="{ 'is-error': m.error }">{{ m.content }}</span>
            </div>

            <div v-if="m.sources && m.sources.length" class="sources">
              <div class="sources-title">
                <el-icon><Document /></el-icon>
                参考来源（{{ m.sources.length }}）
              </div>
              <div v-for="(s, i) in m.sources" :key="i" class="source-item">
                <div class="source-head">
                  <span class="source-title">{{ s.title || '知识片段' }}</span>
                  <el-tag v-if="s.category" size="small" effect="plain">{{ s.category }}</el-tag>
                  <span v-if="s.score != null" class="source-score">相似度 {{ (s.score * 100).toFixed(0) }}%</span>
                </div>
              </div>
            </div>
          </div>
          <div v-if="m.role === 'user'" class="msg-avatar user-avatar-bg">{{ avatarText }}</div>
        </div>
      </div>
    </main>

    <!-- 底部输入区（仅聊天模式） -->
    <footer v-if="mode === 'chat'" class="chat-input-wrap">
      <div v-if="humanStatus !== 'idle'" class="human-banner">
        <div class="human-banner-left">
          <el-icon><Service /></el-icon>
          <span v-if="humanStatus === 'queuing'">正在排队接入人工客服，前方 {{ queuePos }} 位用户…</span>
          <span v-else-if="humanStatus === 'connected'">已接入人工客服，可开始对话</span>
        </div>
        <div class="human-banner-right">
          <el-rate
            v-if="humanStatus === 'connected'"
            v-model="rating"
            :max="5"
            size="small"
            @change="onRate"
          />
          <el-button size="small" text type="danger" @click="exitHuman">结束对话并返回首页</el-button>
        </div>
      </div>

      <div class="input-bar">
        <el-button class="home-btn" text @click="goHome">
          <el-icon style="margin-right: 4px"><HomeFilled /></el-icon>
          首页
        </el-button>
        <el-button
          class="transfer-btn"
          :disabled="humanStatus === 'connected' || humanStatus === 'queuing'"
          @click="transferToHuman"
        >
          <el-icon style="margin-right: 4px"><Service /></el-icon>
          转人工
        </el-button>
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 5 }"
          resize="none"
          class="input-text"
          :placeholder="inputPlaceholder"
          :disabled="humanStatus === 'queuing'"
          @keydown.enter.exact.prevent="sendMessage()"
        />
        <el-button
          type="primary"
          class="send-btn"
          :disabled="!canSend"
          :loading="loading"
          @click="sendMessage()"
        >
          发送
        </el-button>
      </div>
      <div class="input-tip">内容由 AI 生成，仅供参考 · Enter 发送，Shift+Enter 换行</div>
    </footer>

    <!-- 历史对话抽屉 -->
    <el-drawer v-model="historyVisible" title="历史对话" size="400px" direction="rtl">
      <div v-loading="historyLoading" class="history-list">
        <div
          v-for="conv in historyList"
          :key="conv.id"
          class="history-item"
          @click="viewHistory(conv)"
        >
          <div class="history-title">{{ conv.title || '（无标题）' }}</div>
          <div class="history-meta">
            <el-tag size="small" :type="conv.sessionType === 'HUMAN' ? 'warning' : 'primary'">
              {{ conv.sessionType === 'HUMAN' ? '人工' : '机器人' }}
            </el-tag>
            <el-tag size="small" :type="conv.status === 1 ? 'success' : 'info'">
              {{ conv.status === 1 ? '进行中' : '已结束' }}
            </el-tag>
            <span class="history-time">{{ conv.createTime }}</span>
          </div>
        </div>
        <el-empty v-if="!historyLoading && historyList.length === 0" description="暂无历史对话" />
      </div>
      <el-pagination
        v-if="historyTotal > 15"
        class="history-pagination"
        small
        background
        layout="prev, pager, next"
        :total="historyTotal"
        :page-size="15"
        :current-page="historyPage"
        @current-change="onHistoryPage"
      />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting, ArrowDown, Service, Document, HomeFilled, Clock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import MarkdownText from '@/components/MarkdownText.vue'
import { streamUrl, getHistory, getHistoryMessages } from '@/api/chat'
import { transfer, submitSatisfaction, closeConversation } from '@/api/agent'
import type { Source, Conversation, Message } from '@/types'

interface ChatMsg {
  id: number
  role: 'user' | 'ai' | 'agent'
  content: string
  streaming?: boolean
  fromCache?: boolean
  sources?: Source[]
  intentCategory?: string
  error?: boolean
}

const router = useRouter()
const auth = useAuthStore()

const mode = ref<'home' | 'chat'>('home')
const messages = ref<ChatMsg[]>([])
const input = ref('')
const loading = ref(false)
const scrollRef = ref<HTMLElement>()

// 会话 id 持久化到 localStorage：刷新/重进页面沿用同一条会话（登录用户后端还会按 userId 复用）
function loadConvId(): number | null {
  const v = localStorage.getItem('convId')
  const n = v ? Number(v) : NaN
  return Number.isFinite(n) && n > 0 ? n : null
}
const conversationId = ref<number | null>(loadConvId())

const humanStatus = ref<'idle' | 'queuing' | 'connected'>('idle')
const humanConversationId = ref<number | null>(null)
const queuePos = ref(0)
const rating = ref(0)
let ws: WebSocket | null = null
let msgId = 0

const suggestions = [
  { icon: '📚', text: '燕山大学的招生政策是什么？' },
  { icon: '🎯', text: '学校有哪些特色专业？' },
  { icon: '🏠', text: '宿舍条件怎么样？' },
  { icon: '🎫', text: '如何办理校园一卡通？' },
  { icon: '🚌', text: '新生报到流程是怎样的？' },
  { icon: '🎓', text: '奖学金和助学金政策有哪些？' }
]

const avatarText = computed(() => (auth.displayName || '客').slice(0, 1).toUpperCase())
const canSend = computed(
  () => input.value.trim().length > 0 && !loading.value && humanStatus.value !== 'queuing'
)
const inputPlaceholder = computed(() => {
  if (humanStatus.value === 'queuing') return '排队中，请稍候…'
  if (humanStatus.value === 'connected') return '已接入人工客服，请输入您的问题…'
  return '请输入您的问题，例如：燕山大学的招生政策是什么？'
})

function roleName(role: string) {
  if (role === 'agent') return '人工客服'
  if (role === 'ai') return '智能助手'
  return '用户'
}

function pushMsg(role: ChatMsg['role'], content: string, extra: Partial<ChatMsg> = {}) {
  const m: ChatMsg = { id: ++msgId, role, content, ...extra }
  messages.value.push(m)
  scrollToBottom()
  return m
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  })
}

function goHome() {
  mode.value = 'home'
  messages.value = []
  // 保留 conversationId：回首页再问继续同一条会话（超时后由后端自动结束并开新会话）
  ws?.close()
  ws = null
  humanStatus.value = 'idle'
  humanConversationId.value = null
}

// ==================== RAG 问答（SSE 流式） ====================

async function sendMessage(text?: string) {
  const content = (text ?? input.value).trim()
  if (!content || loading.value) return
  input.value = ''

  if (humanStatus.value === 'connected') {
    sendHumanMessage(content)
    return
  }

  mode.value = 'chat'
  pushMsg('user', content)
  loading.value = true
  const ai = pushMsg('ai', '', { streaming: true })

  try {
    await streamAsk(content, ai)
  } catch {
    ai.content = ai.content || '（回答失败，请稍后重试）'
    ai.error = true
    ai.streaming = false
    ElMessage.error('生成回答失败')
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

async function streamAsk(text: string, ai: ChatMsg) {
  const url = streamUrl(text, conversationId.value)
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  const token = localStorage.getItem('token')
  if (token) headers.Authorization = `Bearer ${token}`

  const resp = await fetch(url, { headers })
  if (!resp.ok || !resp.body) {
    throw new Error('HTTP ' + resp.status)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let idx: number
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim()
      buffer = buffer.slice(idx + 1)
      if (!line) continue

      let data = line
      if (data.startsWith('data:')) data = data.slice(5).trim()
      if (!data || data.startsWith(':')) continue

      let obj: any
      try {
        obj = JSON.parse(data)
      } catch {
        continue
      }
      handleSse(obj, ai)
    }
  }

  ai.streaming = false
  scrollToBottom()
}

function handleSse(obj: any, ai: ChatMsg) {
  if (obj.type === 'token') {
    ai.content += obj.content || ''
    scrollToBottom()
  } else if (obj.type === 'end') {
    ai.content = obj.answer ?? ai.content
    ai.fromCache = !!obj.fromCache
    ai.sources = obj.sources || []
    ai.intentCategory = obj.intentCategory
    ai.streaming = false
    if (obj.conversationId != null) {
      conversationId.value = obj.conversationId
      localStorage.setItem('convId', String(obj.conversationId))
    }
  } else if (obj.type === 'error') {
    ai.content = obj.message || '（回答失败）'
    ai.error = true
    ai.streaming = false
  }
}

// ==================== 人工客服（WebSocket） ====================

async function transferToHuman() {
  if (humanStatus.value === 'connected' || humanStatus.value === 'queuing') return

  if (!auth.isLoggedIn) {
    sessionStorage.setItem('pendingTransfer', '1')
    await ElMessageBox.confirm('转人工客服需要登录，是否前往登录？', '提示', {
      confirmButtonText: '去登录',
      cancelButtonText: '取消',
      type: 'info'
    })
    router.push('/login?redirect=/chat')
    return
  }

  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  try {
    const res = await transfer({
      conversationId: conversationId.value,
      summary: lastUser?.content?.slice(0, 50)
    })
    mode.value = 'chat'
    humanConversationId.value = res.conversationId
    conversationId.value = res.conversationId
    openHumanSocket(res.conversationId)

    if (res.assigned) {
      humanStatus.value = 'connected'
      pushMsg('ai', '已接入人工客服，请描述您的问题。')
    } else {
      humanStatus.value = 'queuing'
      queuePos.value = res.queuePosition
      pushMsg('ai', `已进入排队，前方 ${res.queuePosition} 位用户，请稍候…`)
    }
  } catch {
    ElMessage.error('转接人工失败，请重试')
  }
}

function openHumanSocket(convId: number) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/ws/customer-service`
  ws = new WebSocket(url)

  ws.onopen = () => {
    ws?.send(JSON.stringify({ type: 'register', role: 'user', conversationId: convId }))
  }
  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data)
      if (msg.type === 'message' && msg.senderType === 'AGENT') {
        humanStatus.value = 'connected'
        pushMsg('agent', msg.content)
      } else if (msg.type === 'assigned') {
        humanStatus.value = 'connected'
        queuePos.value = 0
      } else if (msg.type === 'position') {
        humanStatus.value = 'queuing'
        queuePos.value = msg.position
      } else if (msg.type === 'closed') {
        if (humanStatus.value === 'connected' || humanStatus.value === 'queuing') {
          pushMsg(
            'ai',
            msg.reason === 'timeout'
              ? '长时间未对话，本次会话已自动结束，感谢您的咨询。'
              : '客服已结束本次会话，感谢您的咨询。'
          )
        }
        humanStatus.value = 'idle'
        humanConversationId.value = null
      }
    } catch {
      /* ignore */
    }
  }
  ws.onclose = () => {
    if (humanStatus.value === 'connected') {
      pushMsg('ai', '客服已结束本次会话，感谢您的咨询。')
      humanStatus.value = 'idle'
    }
  }
}

function sendHumanMessage(content: string) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    ElMessage.warning('客服连接已断开，请重新转接')
    return
  }
  pushMsg('user', content)
  ws.send(JSON.stringify({ type: 'message', conversationId: humanConversationId.value, content }))
}

async function onRate(val: number) {
  if (!humanConversationId.value) return
  try {
    await submitSatisfaction({ conversationId: humanConversationId.value, rating: val })
    ElMessage.success('感谢您的评价！')
  } catch {
    /* ignore */
  }
}

async function exitHuman() {
  const convId = humanConversationId.value
  if (convId) {
    try {
      await closeConversation(convId)
    } catch {
      /* ignore */
    }
  }
  goHome()
}

// ==================== 其他 ====================

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    localStorage.removeItem('convId')
    conversationId.value = null
    auth.logout()
    router.push('/login')
  } else if (cmd === 'history') {
    openHistory()
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}

// ==================== 历史对话 ====================

const historyVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<Conversation[]>([])
const historyTotal = ref(0)
const historyPage = ref(1)

async function openHistory() {
  historyVisible.value = true
  historyPage.value = 1
  await loadHistory()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await getHistory(historyPage.value, 15)
    historyList.value = res.records
    historyTotal.value = res.total
  } catch {
    ElMessage.error('加载历史对话失败')
  } finally {
    historyLoading.value = false
  }
}

function onHistoryPage(p: number) {
  historyPage.value = p
  loadHistory()
}

async function viewHistory(conv: Conversation) {
  historyVisible.value = false
  mode.value = 'chat'
  messages.value = []
  conversationId.value = conv.id
  localStorage.setItem('convId', String(conv.id))

  // 加载该会话的消息
  try {
    const msgs: Message[] = await getHistoryMessages(conv.id)
    for (const m of msgs) {
      let role: ChatMsg['role'] = 'user'
      if (m.senderType === 'AI') role = 'ai'
      else if (m.senderType === 'AGENT') role = 'agent'
      pushMsg(role, m.content, {
        fromCache: m.fromCache === 1,
        intentCategory: m.intentCategory
      })
    }
    if (msgs.length === 0) {
      pushMsg('ai', '该会话暂无消息记录。')
    }
  } catch {
    pushMsg('ai', '加载消息失败，请重试。')
  }
  scrollToBottom()
}

onMounted(() => {
  // 游客转人工 → 登录后自动续接转人工
  if (auth.isLoggedIn && sessionStorage.getItem('pendingTransfer') === '1') {
    sessionStorage.removeItem('pendingTransfer')
    transferToHuman()
  }
})

onBeforeUnmount(() => {
  ws?.close()
  ws = null
})
</script>

<style scoped>
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f0f3ff 0%, #f7f8fc 100%);
}

/* 顶部 */
.chat-header {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #eef0f6;
  box-shadow: 0 1px 4px rgba(31, 35, 41, 0.04);
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  background: var(--brand-gradient);
  border-radius: 12px;
}

.header-title {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.title-main {
  font-size: 16px;
  font-weight: 700;
}

.title-sub {
  font-size: 12px;
  color: var(--text-sub);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text-main);
}

.user-avatar {
  background: var(--brand-gradient);
  color: #fff;
  font-weight: 600;
}

.user-name {
  font-size: 14px;
}

/* ===== 首页 ===== */
.home-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.home-hero {
  text-align: center;
  max-width: 720px;
  width: 100%;
}

.hero-emoji {
  font-size: 68px;
}

.home-hero h1 {
  margin: 18px 0 8px;
  font-size: 26px;
}

.hero-sub {
  color: var(--text-sub);
  margin: 0 0 36px;
}

.suggest-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.suggest-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 22px 16px;
  background: #fff;
  border: 1px solid #e8ebf5;
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.2s;
}

.suggest-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 8px 24px rgba(76, 111, 255, 0.16);
  transform: translateY(-3px);
}

.suggest-icon {
  font-size: 30px;
}

.suggest-text {
  font-size: 14px;
  color: #4a5160;
  line-height: 1.5;
}

.home-input {
  margin-top: 28px;
}

.home-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

/* ===== 聊天 ===== */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 0;
}

.chat-inner {
  max-width: 820px;
  margin: 0 auto;
  padding: 0 20px;
}

.msg-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.msg-row.is-user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}

.bot-avatar {
  background: #fff;
  border: 1px solid #eef0f6;
}

.user-avatar-bg {
  background: var(--brand-gradient);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.msg-body {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.is-user .msg-body {
  align-items: flex-end;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px;
}

.meta-name {
  font-size: 13px;
  color: var(--text-sub);
}

.bubble {
  padding: 12px 16px;
  border-radius: 14px;
  font-size: 15px;
  line-height: 1.7;
  word-break: break-word;
}

.bubble-bot {
  background: #fff;
  border: 1px solid #eef0f6;
  border-top-left-radius: 4px;
  box-shadow: 0 1px 4px rgba(31, 35, 41, 0.04);
}

.bubble-user {
  background: var(--brand-gradient);
  color: #fff;
  border-top-right-radius: 4px;
  box-shadow: 0 4px 14px rgba(76, 111, 255, 0.28);
}

.bubble-text {
  white-space: pre-wrap;
}

.bubble-text.is-error {
  color: #f56c6c;
}

.typing {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 0;
}

.typing .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #b6c0da;
  animation: blink 1.2s infinite ease-in-out;
}

.typing .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.typing .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.sources {
  background: #f7f8fc;
  border-radius: 10px;
  padding: 12px 14px;
  border: 1px solid #eef0f6;
}

.sources-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #5b6069;
  margin-bottom: 10px;
}

.source-item {
  padding: 8px 0;
  border-top: 1px dashed #e5e8f0;
}

.source-item:first-of-type {
  border-top: none;
}

.source-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.source-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.source-score {
  font-size: 12px;
  color: var(--el-color-primary);
}

.chat-input-wrap {
  flex-shrink: 0;
  padding: 12px 24px 16px;
  background: #fff;
  border-top: 1px solid #eef0f6;
}

.human-banner {
  max-width: 820px;
  margin: 0 auto 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff7e8;
  border: 1px solid #ffe0b0;
  color: #b4732a;
  padding: 8px 14px;
  border-radius: 10px;
  font-size: 13px;
}

.human-banner-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.human-banner-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.input-bar {
  max-width: 820px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.input-text :deep(.el-textarea__inner) {
  border-radius: 12px;
  padding: 12px 14px;
  font-size: 15px;
  line-height: 1.5;
  box-shadow: none;
}

.home-btn {
  flex-shrink: 0;
  height: 44px;
}

.transfer-btn {
  flex-shrink: 0;
  height: 44px;
}

.send-btn {
  flex-shrink: 0;
  height: 44px;
  padding: 0 26px;
  border-radius: 12px;
  font-size: 15px;
}

.input-tip {
  max-width: 820px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 12px;
  color: #b6bcc9;
}

@media (max-width: 680px) {
  .suggest-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* 历史对话 */
.history-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.history-item {
  padding: 12px 14px;
  background: #f7f8fc;
  border: 1px solid #eef0f6;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.history-item:hover {
  border-color: var(--el-color-primary);
  background: #eef1ff;
}

.history-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.history-time {
  font-size: 12px;
  color: var(--text-sub);
  margin-left: auto;
}

.history-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
