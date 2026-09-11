<template>
  <view class="page">
    <!-- 顶部工具条 -->
    <view class="toolbar">
      <view class="brand">
        <text class="logo">🤖</text>
        <text class="brand-text title">燕山大学智能客服</text>
      </view>
      <view class="actions">
        <text
          v-if="(isAgent || isAdmin) && isLoggedIn"
          class="pill-btn card workbench-pill"
          @click="goWorkbench"
          >工作台</text
        >
        <text v-if="!isLoggedIn" class="pill-btn grad-btn login-pill" @click="goLogin">登录</text>
        <view v-else class="user-chip" @click="openMenu">
          <view class="avatar brand-grad">{{ avatarText }}</view>
          <text class="nick">{{ displayName }}</text>
        </view>
      </view>
    </view>

    <!-- ==================== 首页空态（预设问题） ==================== -->
    <view v-if="messages.length === 0" class="hero">
      <view class="hero-emoji">🤖</view>
      <view class="hero-title">您好，我是智能客服小助手</view>
      <view class="hero-sub">可解答招生政策、专业设置、校园生活等学校问题</view>

      <view class="suggest-grid">
        <view
          v-for="q in suggestions"
          :key="q.text"
          class="suggest-card card"
          @click="send(q.text)"
        >
          <text class="suggest-icon">{{ q.icon }}</text>
          <text class="suggest-text">{{ q.text }}</text>
        </view>
      </view>
    </view>

    <!-- ==================== 消息区 ==================== -->
    <scroll-view
      v-else
      class="msg-scroll"
      scroll-y
      :scroll-into-view="anchor"
      :scroll-with-animation="true"
    >
      <view class="msg-inner">
        <view
          v-for="m in messages"
          :id="`m${m.id}`"
          :key="m.id"
          class="msg-row"
          :class="m.role === 'user' ? 'is-user' : 'is-bot'"
        >
          <view v-if="m.role !== 'user'" class="avatar-wrap"><view class="bot-avatar">🤖</view></view>
          <view class="msg-body">
            <view v-if="m.role !== 'user'" class="meta">
              <text class="meta-name">{{ roleName(m.role) }}</text>
              <text v-if="m.fromCache" class="chip">命中缓存</text>
              <text v-if="m.intent" class="chip intent-chip">意图 {{ m.intent }}</text>
            </view>
            <view class="bubble" :class="m.role === 'user' ? 'bubble-user' : 'bubble-bot'">
              <view v-if="m.streaming" class="streaming">
                <text v-if="m.content" class="plain stream-text">{{ m.content }}</text>
                <view class="typing"><text class="dot"></text><text class="dot"></text><text class="dot"></text></view>
              </view>
              <MdText v-else-if="m.role === 'ai' && !m.error && m.content" :text="m.content" />
              <text v-else class="plain" :class="m.error ? 'is-error' : ''">{{ m.content }}</text>
            </view>

            <view v-if="m.sources && m.sources.length" class="sources">
              <view class="src-title">参考来源（{{ m.sources.length }}）</view>
              <view v-for="(s, i) in m.sources" :key="i" class="src-item">
                <text class="src-name">{{ s.title || '知识片段' }}</text>
                <text v-if="s.category" class="src-cat">{{ s.category }}</text>
                <text v-if="s.score != null" class="src-score">{{ Math.round(s.score * 100) }}%</text>
              </view>
            </view>
          </view>
        </view>
        <view id="anchor-bottom" class="anchor"></view>
      </view>
    </scroll-view>

    <!-- ==================== 底部输入区 ==================== -->
    <view class="footer safe-bottom">
      <!-- 人工会话状态条 -->
      <view v-if="humanStatus !== 'idle'" class="human-banner">
        <view class="hb-left">
          <text class="hb-dot" :class="humanStatus === 'connected' ? 'on' : ''"></text>
          <text v-if="humanStatus === 'queuing'">排队接入人工客服，前方 {{ queuePos }} 位…</text>
          <text v-else>已接入人工客服，可开始对话</text>
        </view>
        <view class="hb-right">
          <view v-if="humanStatus === 'connected'" class="stars">
            <text
              v-for="i in 5"
              :key="i"
              class="star"
              :class="{ lit: i <= rating }"
              @click="rate(i)"
              >★</text
            >
          </view>
          <text class="exit" @click="exitHuman">结束会话</text>
        </view>
      </view>

      <!-- 动作行 -->
      <view v-if="humanStatus === 'idle'" class="action-row">
        <text v-if="messages.length" class="act" @click="goHome">🏠 首页</text>
        <text v-if="humanStatus === 'idle'" class="act transfer" @click="transferToHuman">🎧 转人工</text>
      </view>

      <view class="input-bar">
        <input
          v-model="input"
          class="input"
          :placeholder="inputPlaceholder"
          :disabled="humanStatus === 'queuing'"
          :confirm-type="humanStatus === 'connected' ? 'send' : 'send'"
          :adjust-position="true"
          @confirm="send()"
        />
        <text class="send grad-btn" :class="{ disabled: !canSend }" @click="send()">发送</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import MdText from '@/components/MdText.vue'
import { ask, getHistoryMessages } from '@/api/chat'
import { transfer, closeConversation, submitSatisfaction } from '@/api/agent'
import {
  auth,
  isLoggedIn,
  isAgent,
  isAdmin,
  displayName,
  fetchProfile,
  logout as storeLogout
} from '@/stores/auth'
import { openWs, wsSend, wsClose } from '@/utils/ws'
import { CONV_KEY, REOPEN_KEY } from '@/utils/config'
import type { Source } from '@/types'

interface ChatMsg {
  id: number
  role: 'user' | 'ai' | 'agent'
  content: string
  streaming?: boolean
  fromCache?: boolean
  sources?: Source[]
  intent?: string
  error?: boolean
}

const suggestions = [
  { icon: '📚', text: '燕山大学的招生政策是什么？' },
  { icon: '🎯', text: '学校有哪些特色专业？' },
  { icon: '🏠', text: '宿舍条件怎么样？' },
  { icon: '🎫', text: '如何办理校园一卡通？' },
  { icon: '🚌', text: '新生报到流程是怎样的？' },
  { icon: '🎓', text: '奖学金和助学金政策有哪些？' }
]

// ===== 状态 =====
const messages = ref<ChatMsg[]>([])
const input = ref('')
const loading = ref(false)
const anchor = ref('')
let msgId = 0

let conversationId = loadConv()
function loadConv(): number | null {
  const n = uni.getStorageSync(CONV_KEY)
  const id = Number(n)
  return Number.isFinite(id) && id > 0 ? id : null
}

const humanStatus = ref<'idle' | 'queuing' | 'connected'>('idle')
const humanConversationId = ref<number | null>(null)
const queuePos = ref(0)
const rating = ref(0)

// ===== computed =====
const avatarText = computed(() => (displayName.value || '客').slice(0, 1).toUpperCase())
const canSend = computed(() => input.value.trim().length > 0 && !loading.value && humanStatus.value !== 'queuing')
const inputPlaceholder = computed(() => {
  if (humanStatus.value === 'queuing') return '排队中，请稍候…'
  if (humanStatus.value === 'connected') return '请输入您要咨询的问题…'
  return '请输入您的问题，例如：燕山大学的招生政策？'
})

function roleName(role: string) {
  if (role === 'agent') return '人工客服'
  if (role === 'ai') return '智能助手'
  return '用户'
}

function pushMsg(role: ChatMsg['role'], content: string, extra: Partial<ChatMsg> = {}) {
  // 必须用 reactive 包裹：推入 ref 数组的元素是数组内部经代理的副本，
  // 若持裸对象，typewrite 逐字更新 ai.content/ai.streaming 不会触发响应式，
  // 气泡会永远停在“正在输入…”直到页面重渲染。
  const m = reactive<ChatMsg>({ id: ++msgId, role, content, ...extra })
  messages.value.push(m)
  scrollTo(m)
  return m
}
function scrollTo(m: ChatMsg) {
  anchor.value = `m${m.id}`
}

// ===== 页面生命周期 =====
onShow(async () => {
  if (isLoggedIn.value && !auth.profile) await fetchProfile().catch(() => {})

  // 客服/管理员不需要用户问答页，直接跳工作台
  if (isAgent.value || isAdmin.value) {
    uni.reLaunch({ url: '/pages/agent/workbench' })
    return
  }

  // 从历史会话进入：加载该会话消息回放
  const reopen = uni.getStorageSync(REOPEN_KEY)
  if (reopen) {
    uni.removeStorageSync(REOPEN_KEY)
    await loadConversationMessages(Number(reopen))
  }
  // 登录后（转人工引导）自动转人工
  const pending = uni.getStorageSync('mp_pending_transfer')
  if (pending && isLoggedIn.value) {
    uni.removeStorageSync('mp_pending_transfer')
    transferToHuman()
  }
})

// ==================== 问答（非流式 + 打字机） ====================
let typer: ReturnType<typeof setInterval> | null = null

function send(text?: string) {
  const content = (text ?? input.value).trim()
  if (!content || loading.value) return
  input.value = ''

  if (humanStatus.value === 'connected') {
    sendHuman(content)
    return
  }

  pushMsg('user', content)
  loading.value = true
  const ai = pushMsg('ai', '', { streaming: true })

  ask(content, conversationId).then(
    (res) => {
      conversationId = res.conversationId ?? conversationId
      if (conversationId) uni.setStorageSync(CONV_KEY, String(conversationId))
      typewrite(ai, res.answer, {
        fromCache: !!res.fromCache,
        sources: res.sources,
        intent: res.intentCategory
      })
    },
    () => {
      ai.content = ai.content || '（回答失败，请稍后重试）'
      ai.error = true
      ai.streaming = false
    }
  ).finally(() => {
    loading.value = false
  })
}

/** 打字机逐字显示，完成后落最终格式 */
function typewrite(
  ai: ChatMsg,
  full: string,
  meta: { fromCache?: boolean; sources?: Source[]; intent?: string }
) {
  if (typer) clearInterval(typer)
  let i = 0
  ai.streaming = true
  const step = Math.max(1, Math.ceil(full.length / 80)) // ~80 帧播完
  typer = setInterval(() => {
    i = Math.min(full.length, i + step)
    ai.content = full.slice(0, i)
    anchor.value = `m${ai.id}`
    if (i >= full.length) {
      if (typer) clearInterval(typer)
      typer = null
      ai.streaming = false
      ai.fromCache = meta.fromCache
      ai.sources = meta.sources
      ai.intent = meta.intent
    }
  }, 24)
}

async function loadConversationMessages(convId: number) {
  try {
    const msgs = await getHistoryMessages(convId)
    messages.value = []
    msgId = 0
    conversationId = convId
    for (const m of msgs) {
      let role: ChatMsg['role'] = 'user'
      if (m.senderType === 'AI') role = 'ai'
      else if (m.senderType === 'AGENT') role = 'agent'
      pushMsg(role, m.content || '')
    }
  } catch (e) {
    uni.showToast({ title: '加载会话失败', icon: 'none' })
  }
}

function goHome() {
  if (typer) clearInterval(typer)
  wsClose()
  humanStatus.value = 'idle'
  humanConversationId.value = null
  queuePos.value = 0
  messages.value = []
  // 保留 conversationId：再提问继续同一条会话（超时后由后端自动结束并新开）
}

// ==================== 人工客服（WS） ====================
async function transferToHuman() {
  if (humanStatus.value === 'connected' || humanStatus.value === 'queuing') return

  if (!isLoggedIn.value) {
    uni.showModal({
      title: '提示',
      content: '转人工客服需要登录，是否前往登录？',
      confirmText: '去登录',
      success: (r) => {
        if (r.confirm) {
          uni.setStorageSync('mp_pending_transfer', '1')
          uni.navigateTo({ url: '/pages/login/login' })
        }
      }
    })
    return
  }

  const lastUser = [...messages.value].reverse().find((m) => m.role === 'user')
  try {
    uni.showLoading({ title: '正在转接…' })
    const res = await transfer({
      conversationId,
      summary: lastUser?.content?.slice(0, 50)
    })
    uni.hideLoading()
    humanConversationId.value = res.conversationId
    conversationId = res.conversationId
    uni.setStorageSync(CONV_KEY, String(res.conversationId))
    openHumanSocket(res.conversationId)

    if (res.assigned) {
      humanStatus.value = 'connected'
      pushMsg('ai', '已接入人工客服，请描述您的问题。')
    } else {
      humanStatus.value = 'queuing'
      queuePos.value = res.queuePosition
      pushMsg('ai', `已进入排队，前方 ${res.queuePosition} 位用户，请稍候…`)
    }
  } catch (e) {
    uni.hideLoading()
    uni.showToast({ title: (e as Error)?.message || '转接人工失败', icon: 'none' })
  }
}

function openHumanSocket(convId: number) {
  wsClose()
  openWs({
    onOpen: () => {
      wsSend({ type: 'register', role: 'user', conversationId: convId })
    },
    onMessage: (msg: any) => {
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
    },
    onClose: () => {
      if (humanStatus.value === 'connected') {
        pushMsg('ai', '客服已结束本次会话，感谢您的咨询。')
      }
      humanStatus.value = 'idle'
    }
  })
}

function sendHuman(content: string) {
  if (humanStatus.value !== 'connected' || !humanConversationId.value) {
    uni.showToast({ title: '客服连接已断开，请重新转接', icon: 'none' })
    return
  }
  pushMsg('user', content)
  wsSend({ type: 'message', conversationId: humanConversationId.value, content })
}

async function rate(val: number) {
  rating.value = val
  if (!humanConversationId.value) return
  try {
    await submitSatisfaction({ conversationId: humanConversationId.value, rating: val })
    uni.showToast({ title: '感谢您的评价！', icon: 'success' })
  } catch (e) {
    /* ignore */
  }
}

async function exitHuman() {
  const convId = humanConversationId.value
  if (convId) {
    try {
      await closeConversation(convId)
    } catch (e) {
      /* ignore */
    }
  }
  wsClose()
  humanStatus.value = 'idle'
  humanConversationId.value = null
  messages.value = []
}

// ==================== 顶部菜单 ====================
function goWorkbench() {
  uni.reLaunch({ url: '/pages/agent/workbench' })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function openMenu() {
  const items = isAgent.value || isAdmin.value ? ['个人中心', '历史对话', '客服工作台', '退出登录'] : ['个人中心', '历史对话', '退出登录']
  uni.showActionSheet({
    itemList: items,
    success: (res) => {
      const it = items[res.tapIndex]
      if (it === '个人中心') uni.navigateTo({ url: '/pages/profile/profile' })
      else if (it === '历史对话') uni.navigateTo({ url: '/pages/history/history' })
      else if (it === '客服工作台') uni.reLaunch({ url: '/pages/agent/workbench' })
      else if (it === '退出登录') {
        goHome()
        storeLogout()
        uni.showToast({ title: '已退出登录', icon: 'none' })
      }
    }
  })
}
</script>

<style lang="scss" scoped>
.page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f6f7fb;
  box-sizing: border-box;
}

/* 工具条 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18rpx 32rpx;
  background: #fff;
}

.brand {
  display: flex;
  align-items: center;
}

.logo {
  font-size: 40rpx;
  margin-right: 12rpx;
}

.title {
  font-size: 32rpx;
  font-weight: 700;
}

.actions {
  display: flex;
  align-items: center;
}

.workbench-pill {
  color: #5b7cfa;
  margin-right: 16rpx;
  font-weight: 600;
  border: 1rpx solid rgba(91, 124, 250, 0.3);
}

.login-pill {
  font-weight: 600;
}

.user-chip {
  display: flex;
  align-items: center;
}

.avatar {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  color: #fff;
  font-size: 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nick {
  margin-left: 10rpx;
  font-size: 26rpx;
  color: #4a5163;
  max-width: 120rpx;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 首页空态 */
.hero {
  flex: 1;
  overflow-y: auto;
  padding: 40rpx 32rpx;
}

.hero-emoji {
  font-size: 96rpx;
  text-align: center;
}

.hero-title {
  margin-top: 16rpx;
  text-align: center;
  font-size: 38rpx;
  font-weight: 700;
  color: #2b2f3a;
}

.hero-sub {
  margin-top: 12rpx;
  text-align: center;
  font-size: 26rpx;
  color: #8a91a3;
}

.suggest-grid {
  margin-top: 40rpx;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
}

.suggest-card {
  width: 48%;
  box-sizing: border-box;
  padding: 28rpx 24rpx;
  margin-bottom: 20rpx;
  border-radius: 20rpx;
  display: flex;
  align-items: center;
}

.suggest-icon {
  font-size: 40rpx;
  margin-right: 14rpx;
}

.suggest-text {
  font-size: 26rpx;
  color: #3c4152;
  line-height: 1.4;
  flex: 1;
}

/* 消息 */
.msg-scroll {
  flex: 1;
  overflow: hidden;
}

.msg-inner {
  padding: 24rpx 28rpx 12rpx;
}

.msg-row {
  display: flex;
  margin-bottom: 24rpx;
}

.msg-row.is-user {
  justify-content: flex-end;
}

.avatar-wrap {
  margin-right: 14rpx;
  align-self: flex-end;
}

.bot-avatar {
  width: 64rpx;
  height: 64rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30rpx;
}

.msg-body {
  max-width: 76%;
}

.msg-row.is-user .msg-body {
  max-width: 82%;
}

.meta {
  display: flex;
  align-items: center;
  margin-bottom: 8rpx;
  flex-wrap: wrap;
}

.meta-name {
  font-size: 22rpx;
  color: #9aa0b0;
  margin-right: 12rpx;
}

.chip {
  font-size: 20rpx;
  padding: 2rpx 12rpx;
  border-radius: 8rpx;
  background: #eef1ff;
  color: #8a94e8;
  margin-right: 8rpx;
}

.intent-chip {
  background: #e8f7ee;
  color: #37a067;
}

.bubble {
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  box-sizing: border-box;
  word-break: break-all;
}

.bubble-user {
  background: linear-gradient(135deg, #5b7cfa, #7a5cf0);
  color: #fff;
  border-radius: 24rpx 8rpx 24rpx 24rpx;
}

.bubble-bot {
  background: #fff;
  border-radius: 8rpx 24rpx 24rpx 24rpx;
  box-shadow: 0 4rpx 16rpx rgba(90, 110, 200, 0.08);
}

.plain {
  font-size: 28rpx;
  line-height: 1.6;
}

.is-error {
  color: #e5594c;
}

.streaming .stream-text {
  display: block;
  margin-bottom: 10rpx;
}

.typing {
  display: inline-flex;
  align-items: center;
}

.typing .dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background: #9aa4c8;
  margin-right: 8rpx;
  animation: blink 1s infinite both;
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
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}

.sources {
  margin-top: 12rpx;
  background: #fafbff;
  border-radius: 16rpx;
  padding: 14rpx 18rpx;
}

.src-title {
  font-size: 22rpx;
  color: #7a86b8;
  margin-bottom: 8rpx;
}

.src-item {
  display: flex;
  align-items: center;
  font-size: 24rpx;
  margin: 4rpx 0;
}

.src-name {
  flex: 1;
  color: #3c4152;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.src-cat {
  font-size: 20rpx;
  background: #eef1ff;
  color: #8a94e8;
  border-radius: 6rpx;
  padding: 0 10rpx;
  margin: 0 10rpx;
}

.src-score {
  font-size: 20rpx;
  color: #9aa0b0;
}

.anchor {
  height: 4rpx;
}

/* 底部 */
.footer {
  background: #fff;
  border-top: 1rpx solid #eef0f6;
  padding: 12rpx 24rpx 20rpx;
}

.human-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f6f8ff;
  border-radius: 14rpx;
  padding: 12rpx 20rpx;
  margin-bottom: 12rpx;
  font-size: 24rpx;
  color: #6b7280;
}

.hb-left {
  display: flex;
  align-items: center;
}

.hb-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #f0ad4e;
  margin-right: 12rpx;
}

.hb-dot.on {
  background: #4cd964;
}

.stars {
  display: flex;
  margin-right: 12rpx;
}

.star {
  color: #d9deec;
  font-size: 30rpx;
  margin: 0 2rpx;
}

.star.lit {
  color: #f5b50a;
}

.exit {
  color: #e5594c;
}

.action-row {
  display: flex;
  padding: 4rpx 6rpx 12rpx;
}

.act {
  font-size: 26rpx;
  color: #6b7280;
  margin-right: 28rpx;
}

.act.transfer {
  color: #5b7cfa;
  font-weight: 600;
}

.input-bar {
  display: flex;
  align-items: center;
}

.input {
  flex: 1;
  height: 76rpx;
  background: #f3f5fa;
  border-radius: 40rpx;
  padding: 0 30rpx;
  font-size: 28rpx;
}

.send {
  margin-left: 16rpx;
  padding: 14rpx 36rpx;
  font-size: 28rpx;
  font-weight: 600;
}

.send.disabled {
  opacity: 0.5;
}
</style>
