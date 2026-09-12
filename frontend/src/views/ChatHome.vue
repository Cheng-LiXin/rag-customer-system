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
              <!-- 机器人回答按 Markdown 渲染（加粗/列表/代码等），用户与人工客服消息保持原文。
                   正文里的行内引用角标 [n] 由 MarkdownText 转成可点击的 sup，点击后展开第 n 条来源 -->
              <MarkdownText
                v-else-if="m.role === 'ai' && !m.error"
                :text="m.content"
                @cite="(n) => onCite(m, n)"
              />
              <span v-else class="bubble-text" :class="{ 'is-error': m.error }">{{ m.content }}</span>
            </div>

            <!-- 低置信：答案照给，提示可转人工核实（不打断阅读，故用弱提示） -->
            <div v-if="m.role === 'ai' && m.lowConfidence && !m.rejected" class="answer-note">
              <el-icon><Warning /></el-icon>
              该回答依据的相关度偏低，仅供参考，必要时可转人工核实。
            </div>

            <!-- 拒答：由后端 rejected 标志位驱动（不靠文案匹配），直接给转人工入口。
                 注入拦截与「相似度过低」的文案要分开：前者是安全策略，后者是资料不足 -->
            <div v-if="m.role === 'ai' && m.rejected" class="answer-cta" :class="{ 'is-guard': isGuardBlock(m) }">
              <div class="answer-cta-text">
                <el-icon><Warning /></el-icon>
                {{ isGuardBlock(m) ? '该请求已被安全策略拦截' : '未找到可靠依据，建议转人工客服核实' }}
              </div>
              <el-button
                size="small"
                type="primary"
                :disabled="humanStatus === 'connected' || humanStatus === 'queuing'"
                @click="transferToHuman"
              >
                转人工客服
              </el-button>
            </div>

            <!-- 消息级反馈（数据飞轮入口）：登录用户才能提交（接口需 JWT，且要防匿名刷） -->
            <div
              v-if="m.role === 'ai' && !m.streaming && !m.error && m.messageId && auth.isLoggedIn"
              class="msg-feedback"
            >
              <span class="fb-label">这条回答有帮助吗？</span>
              <button
                class="fb-btn"
                :class="{ 'is-on': m.feedback === 1 }"
                :disabled="!!m.feedback"
                title="有帮助"
                @click="onFeedback(m, 1)"
              >
                👍
              </button>
              <button
                class="fb-btn"
                :class="{ 'is-on': m.feedback === 2 }"
                :disabled="!!m.feedback"
                title="没帮助"
                @click="onFeedback(m, 2)"
              >
                👎
              </button>
              <span v-if="m.feedback" class="fb-done">已反馈，感谢！</span>
            </div>

            <div v-if="m.sources && m.sources.length" class="sources">
              <div class="sources-title">
                <el-icon><Document /></el-icon>
                参考来源（{{ m.sources.length }}）
                <span class="sources-hint">点击条目或正文中的 [n] 可展开原文</span>
              </div>
              <div
                v-for="(s, i) in m.sources"
                :key="i"
                class="source-item"
                :class="{ 'is-open': isSourceOpen(m, i) }"
                :data-src-key="srcKey(m, i)"
              >
                <div class="source-head" @click="toggleSource(m, i)">
                  <span class="source-badge">{{ s.index ?? i + 1 }}</span>
                  <span class="source-title">{{ s.title || s.sourceTitle || '知识片段' }}</span>
                  <el-tag v-if="s.category" size="small" effect="plain">{{ s.category }}</el-tag>
                  <span v-if="s.score != null" class="source-score">相似度 {{ (s.score * 100).toFixed(0) }}%</span>
                  <el-icon class="source-caret" :class="{ 'is-open': isSourceOpen(m, i) }">
                    <ArrowDown />
                  </el-icon>
                </div>
                <!-- 展开后展示「原条目」：条目 ID、更新时间、来源链接与逐字原文（默认折叠，不打扰阅读） -->
                <div v-if="isSourceOpen(m, i)" class="source-detail">
                  <div class="source-meta">
                    <span>条目 ID：{{ s.chunkId || '-' }}</span>
                    <span v-if="s.updateTime">更新时间：{{ fmtTime(s.updateTime) }}</span>
                    <a v-if="s.sourceUrl" :href="s.sourceUrl" target="_blank" rel="noopener noreferrer">
                      查看原文链接
                    </a>
                  </div>
                  <div v-if="s.sourceTitle" class="source-file">来源文件：{{ s.sourceTitle }}</div>
                  <MarkdownText v-if="s.content" class="source-content" :text="s.content" />
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
import { Setting, ArrowDown, Service, Document, HomeFilled, Clock, User, Warning } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import MarkdownText from '@/components/MarkdownText.vue'
import { streamUrl, getHistory, getHistoryMessages } from '@/api/chat'
import { transfer, submitSatisfaction, closeConversation } from '@/api/agent'
import { submitMessageFeedback } from '@/api/unresolved'
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
  /** 后端拒答标志（相似度过低/命中防护）：据此展示「转人工客服」入口，不靠文案匹配 */
  rejected?: boolean
  /** 拒答原因：low-score | injection-input | injection-context | injection-output */
  rejectReason?: string
  /** 低置信提示 */
  lowConfidence?: boolean
  /** 该机器人回答在 message 表的 id（提交 👍/👎 用） */
  messageId?: number
  /** 已提交的反馈：1-有帮助 2-没帮助 */
  feedback?: number
  /** 本次检索最高相似度，供排查 */
  maxScore?: number | null
}

const router = useRouter()
const auth = useAuthStore()

const mode = ref<'home' | 'chat'>('home')
const messages = ref<ChatMsg[]>([])
const input = ref('')
const loading = ref(false)
const scrollRef = ref<HTMLElement>()

// ===== 参考来源展开 + 行内引用联动 =====
// 用 `${消息id}-${来源下标}` 作键，一条回复内的多个条目可各自独立展开
const openSources = ref<Set<string>>(new Set())
function srcKey(m: ChatMsg, i: number) {
  return `${m.id}-${i}`
}
function isSourceOpen(m: ChatMsg, i: number) {
  return openSources.value.has(srcKey(m, i))
}
function toggleSource(m: ChatMsg, i: number) {
  const k = srcKey(m, i)
  const next = new Set(openSources.value)
  next.has(k) ? next.delete(k) : next.add(k)
  openSources.value = next
}

/** 点击正文里的行内引用角标 [n]：展开第 n 条来源（sources[n-1]）并滚动到它 */
function onCite(m: ChatMsg, n: number) {
  const i = n - 1
  if (!m.sources || !m.sources[i]) return
  const k = srcKey(m, i)
  const next = new Set(openSources.value)
  next.add(k)
  openSources.value = next
  nextTick(() => {
    document.querySelector(`[data-src-key="${k}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

/** 时间显示：后端 LocalDateTime 形如 2026-09-12T13:35:18 */
function fmtTime(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(0, 19)
}

/** 是否为注入防护拦截（与「相似度过低」的拒答区分展示） */
function isGuardBlock(m: ChatMsg) {
  return (m.rejectReason || '').startsWith('injection-')
}

/**
 * 提交消息级反馈（数据飞轮入口）。
 * 点踩时可选填原因 —— 「答非所问」和「信息过时」对应完全不同的补救动作，
 * 光有一个 👎 说明不了问题。
 */
async function onFeedback(m: ChatMsg, fb: 1 | 2) {
  if (!m.messageId || m.feedback) return
  let comment = ''
  if (fb === 2) {
    try {
      const r = await ElMessageBox.prompt('可以补充说明哪里不对吗？（可留空）', '反馈', {
        confirmButtonText: '提交',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：答非所问 / 信息过时 / 漏了关键条件',
        inputValue: ''
      })
      comment = (r as { value?: string }).value || ''
    } catch {
      return // 用户取消
    }
  }
  try {
    await submitMessageFeedback(m.messageId, fb, comment || undefined)
    m.feedback = fb
    ElMessage.success(fb === 1 ? '感谢反馈！' : '已记录，我们会尽快补充相关知识')
  } catch {
    // 拦截器已统一提示
  }
}

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
    ai.rejected = !!obj.rejected
    ai.rejectReason = obj.rejectReason || ''
    ai.messageId = obj.messageId ?? undefined
    ai.lowConfidence = !!obj.lowConfidence
    ai.maxScore = obj.maxScore ?? null
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
        intentCategory: m.intentCategory,
        // 历史回放要把已评状态带出来，否则用户会以为自己的反馈丢了
        messageId: m.senderType === 'AI' ? m.id : undefined,
        feedback: m.senderType === 'AI' && m.feedback ? m.feedback : undefined
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

.sources-hint {
  font-weight: 400;
  font-size: 12px;
  color: #9aa0ac;
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
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}

.source-head:hover {
  background: #eef2ff;
}

/* 引用序号徽标：与正文里的 [n] 一一对应 */
.source-badge {
  flex-shrink: 0;
  min-width: 20px;
  height: 20px;
  padding: 0 5px;
  border-radius: 6px;
  background: #4c6fff;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  line-height: 20px;
  text-align: center;
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

.source-caret {
  font-size: 13px;
  color: #9aa0ac;
  transition: transform 0.2s;
}

.source-caret.is-open {
  transform: rotate(180deg);
}

.source-item.is-open {
  background: #f2f5ff;
  border-radius: 8px;
  padding: 8px 10px;
}

/* 展开后的「原条目」：条目 ID / 更新时间 / 来源链接 + 逐字原文 */
.source-detail {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #dfe4f2;
}

.source-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #7a8090;
  margin-bottom: 6px;
}

.source-meta a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.source-meta a:hover {
  text-decoration: underline;
}

.source-file {
  font-size: 12px;
  color: #7a8090;
  margin-bottom: 6px;
}

.source-content {
  max-height: 320px;
  overflow-y: auto;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #eef0f6;
  border-radius: 8px;
  font-size: 13px;
}

/* 拒答：给明确的转人工出口 */
.answer-cta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: #fff8f0;
  border: 1px solid #ffe0bf;
}

.answer-cta-text {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #b57100;
}

/* 安全策略拦截：用红色系与「资料不足」的橙色区分开 */
.answer-cta.is-guard {
  background: #fff1f1;
  border-color: #ffd0d0;
}
.answer-cta.is-guard .answer-cta-text {
  color: #c0392b;
}

/* 消息级反馈（👍/👎）*/
.msg-feedback {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.fb-label {
  margin-right: 2px;
}

.fb-btn {
  border: 1px solid #e4e7ed;
  background: #fff;
  border-radius: 14px;
  padding: 1px 9px;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
  transition: all 0.15s;
}

.fb-btn:hover:not(:disabled) {
  border-color: var(--el-color-primary);
  background: #f2f5ff;
}

.fb-btn.is-on {
  border-color: var(--el-color-primary);
  background: #eef2ff;
}

.fb-btn:disabled {
  cursor: default;
  opacity: 0.75;
}

.fb-done {
  color: var(--el-color-primary);
}

/* 低置信：弱提示，不打断阅读 */
.answer-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: #9a7b3f;
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
