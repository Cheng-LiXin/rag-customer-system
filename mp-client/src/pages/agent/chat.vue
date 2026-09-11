<template>
  <view class="page">
    <!-- 头部信息条 -->
    <view class="head">
      <view class="h-left">
        <view class="h-name">{{ displayName }}</view>
        <view class="h-sub" @click="showCard = !showCard">
          <text>会话 #{{ convId }}</text>
          <text v-if="queueing" class="queuing"> · 排队待分配</text>
          <text v-else-if="ended" class="ended-tag"> · 已结束</text>
          <text v-else class="live"> · 进行中</text>
          <text class="arrow">{{ showCard ? '▲' : '▼' }}</text>
        </view>
      </view>
      <view class="h-right">
        <text class="act" @click="openTicket">＋工单</text>
        <text v-if="!ended && !queueing" class="act danger" @click="closeConv">结束</text>
        <text v-if="ended" class="act primary" @click="reopen">再次接待</text>
      </view>
    </view>

    <!-- 客户名片（可展开） -->
    <view v-if="showCard" class="card-sec">
      <view v-if="cust">
        <view v-if="cust.guest" class="card-row">
          <text class="k">客户</text>
          <text class="v">游客（未登录访客）</text>
        </view>
        <template v-else>
          <view class="card-row"><text class="k">昵称</text><text class="v">{{ cust.nickname || '—' }}</text></view>
          <view class="card-row"><text class="k">@账号</text><text class="v">{{ cust.username || '—' }}</text></view>
          <view class="card-row" v-if="cust.identity"><text class="k">身份</text><text class="v">{{ cust.identity }}</text></view>
          <view class="card-row" v-if="cust.province || cust.city"><text class="k">地区</text><text class="v">{{ [cust.province, cust.city].filter(Boolean).join(' ') }}</text></view>
          <view class="card-row" v-if="cust.phone"><text class="k">手机</text><text class="v">{{ cust.phone }}</text></view>
          <view class="card-row" v-if="cust.email"><text class="k">邮箱</text><text class="v">{{ cust.email }}</text></view>
          <view class="card-row"><text class="k">账号ID</text><text class="v">{{ cust.userId }}</text></view>
          <view class="card-row"><text class="k">状态</text><text class="v">{{ cust.status === 1 ? '正常' : cust.status === 0 ? '已禁用' : '—' }}</text></view>
        </template>
      </view>
      <view v-else class="card-row"><text class="k">客户资料</text><text class="v">加载中…</text></view>
    </view>

    <!-- 消息区域 -->
    <scroll-view class="msgs" scroll-y :scroll-into-view="anchor" :scroll-with-animation="true">
      <view class="inner">
        <view v-if="msgLoading" class="msg-state">
          <text>加载消息中…</text>
        </view>
        <view v-else-if="msgError" class="msg-state" @click="loadAll">
          <text>{{ msgError }}</text>
          <text class="retry-text">点击重试</text>
        </view>
        <template v-else>
          <view
            v-for="m in msgs"
            :id="`am${m.id}`"
            :key="m.id"
            class="row"
            :class="m.self ? 'self' : ''"
          >
            <view class="bubble" :class="m.self ? 'b-self' : 'b-other'">
              <view v-if="m.sender === '机器人'" class="ai">
                <MdText v-if="m.content" :text="m.content" />
              </view>
              <text v-else>{{ m.content }}</text>
            </view>
            <text class="sender">{{ m.sender }}</text>
          </view>
          <view id="anchor-bottom"></view>
        </template>
      </view>
    </scroll-view>

    <!-- 输入 -->
    <view class="input-bar safe-bottom">
      <input
        v-model="draft"
        class="input"
        :disabled="ended || queueing"
        :placeholder="ended ? '会话已结束，可点击「再次接待」继续' : queueing ? '等待系统分配后即可回复…' : '输入回复内容，发送'"
        confirm-type="send"
        @confirm="sendMsg"
      />
      <text class="send grad-btn" :class="{ disabled: ended || queueing || !draft.trim() }" @click="sendMsg">发送</text>
    </view>

    <!-- 建工单浮层 -->
    <view v-if="ticketVisible" class="mask" @click="ticketVisible = false">
      <view class="ticket card" @click.stop>
        <view class="t-title">为会话 #{{ convId }} 创建工单</view>
        <input v-model="tForm.title" class="t-input" placeholder="工单标题（必填）" :maxlength="60" />
        <input v-model="tForm.category" class="t-input" placeholder="分类，如：招生咨询（选填）" :maxlength="20" />
        <textarea v-model="tForm.description" class="t-area" placeholder="问题描述（选填）" :maxlength="300" />
        <view class="t-btns">
          <text class="t-cancel" @click="ticketVisible = false">取消</text>
          <text class="t-ok grad-btn" @click="submitTicket">创建</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import MdText from '@/components/MdText.vue'
import { conversationMessages, markRead, closeConversation, reopenConversation } from '@/api/agent'
import { conversationCustomer } from '@/api/conversation'
import { createTicket } from '@/api/ticket'
import { on, off } from '@/utils/bus'
import { wsSend, wsOpen } from '@/utils/ws'
import type { Message, CustomerProfile } from '@/types'

interface MsgView {
  id: number
  sender: string // 用户 | 我 | 机器人
  self: boolean
  content: string
}

const convId = ref(0)
const customerName = ref('')
const queueing = ref(false)
const ended = ref(false)

const msgs = ref<MsgView[]>([])
const msgLoading = ref(false)
const msgError = ref('')
const draft = ref('')
const anchor = ref('')
let seed = 0

const showCard = ref(false)
const cust = ref<CustomerProfile | null>(null)
const ticketVisible = ref(false)
const tForm = reactive({ title: '', category: '', description: '' })

const displayName = computed(() => {
  if (cust.value && !cust.value.guest) {
    return cust.value.nickname || cust.value.username || customerName.value
  }
  return customerName.value || `会话 #${convId.value}`
})

function pushMsg(sender: string, self: boolean, content: string) {
  const m: MsgView = { id: ++seed, sender, self, content }
  msgs.value.push(m)
  // 延迟滚动到底部
  setTimeout(() => {
    anchor.value = `am${m.id}`
  }, 50)
  return m
}

function mapMsg(m: Message) {
  if (m.senderType === 'USER') return pushMsg('用户', false, m.content || '')
  if (m.senderType === 'AGENT') return pushMsg('我', true, m.content || '')
  return pushMsg('机器人', false, m.content || '')
}

async function loadAll() {
  if (!convId.value) return
  msgLoading.value = true
  msgError.value = ''
  try {
    const list = await conversationMessages(convId.value)
    msgs.value = []
    seed = 0
    list.forEach(mapMsg)
    // 标记已读（不阻塞）
    markRead(convId.value).catch(() => {})
  } catch (e: any) {
    msgError.value = e?.message || '加载消息失败'
  } finally {
    msgLoading.value = false
  }
  loadCustomer()
}

async function loadCustomer() {
  if (!convId.value) return
  try {
    cust.value = await conversationCustomer(convId.value)
  } catch {
    cust.value = null
  }
}

function sendMsg() {
  const text = draft.value.trim()
  if (!text || ended.value || queueing.value) return
  if (!wsOpen()) {
    uni.showToast({ title: '客服连接已断开，请返回工作台重连', icon: 'none' })
    return
  }
  draft.value = ''
  pushMsg('我', true, text)
  wsSend({ type: 'message', conversationId: convId.value, content: text })
}

async function closeConv() {
  try {
    await closeConversation(convId.value)
    ended.value = true
    queueing.value = false
    uni.showToast({ title: '已结束会话', icon: 'none' })
  } catch (e: any) {
    uni.showToast({ title: e?.message || '结束失败', icon: 'none' })
  }
}

async function reopen() {
  try {
    await reopenConversation(convId.value)
    ended.value = false
    queueing.value = false
    uni.showToast({ title: '已再次接待', icon: 'success' })
  } catch (e: any) {
    uni.showToast({ title: e?.message || '操作失败', icon: 'none' })
  }
}

async function submitTicket() {
  const title = tForm.title.trim()
  if (!title) return uni.showToast({ title: '请填写工单标题', icon: 'none' })
  try {
    await createTicket({
      conversationId: convId.value,
      title,
      category: tForm.category.trim(),
      description: tForm.description.trim()
    })
    ticketVisible.value = false
    tForm.title = ''
    tForm.category = ''
    tForm.description = ''
    uni.showToast({ title: '工单已创建', icon: 'success' })
  } catch (e: any) {
    uni.showToast({ title: e?.message || '创建失败', icon: 'none' })
  }
}

function openTicket() {
  ticketVisible.value = true
}

// bus：工作台 WS 常驻，本页监听实时推送
function onMsg(p: any) {
  if (!p || p.conversationId !== convId.value) return
  pushMsg('用户', false, p.content || '')
  markRead(convId.value).catch(() => {})
  // 用户发来新消息时刷新名片
  loadCustomer()
}

function onClosed(p: any) {
  if (!p || p.conversationId !== convId.value) return
  ended.value = true
  queueing.value = false
  uni.showToast({
    title: p.reason === 'timeout' ? '会话因长时间未对话已自动结束' : '会话已结束',
    icon: 'none'
  })
}

function onAssigned(p: any) {
  if (!p || p.conversationId !== convId.value) return
  ended.value = false
  queueing.value = false
  uni.showToast({ title: '会话已分配，可开始回复', icon: 'none' })
}

onLoad((q: any) => {
  convId.value = Number(q?.convId || 0)
  customerName.value = decodeURIComponent(q?.name || '')
  queueing.value = q?.queue === '1' || q?.queue === 1
  ended.value = q?.ended === '1' || q?.ended === 1
  on('conv:msg', onMsg)
  on('conv:closed', onClosed)
  on('conv:assigned', onAssigned)
  loadAll()
})

onUnload(() => {
  off('conv:msg', onMsg)
  off('conv:closed', onClosed)
  off('conv:assigned', onAssigned)
})
</script>

<style lang="scss" scoped>
.page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f6f7fb;
  box-sizing: border-box;
}

/* ===== 头部 ===== */
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18rpx 28rpx;
  background: #fff;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}

.h-left {
  min-width: 0;
  flex: 1;
}

.h-name {
  font-size: 32rpx;
  font-weight: 700;
  color: #2b2f3a;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.h-sub {
  font-size: 22rpx;
  color: #8a91a3;
  margin-top: 4rpx;
  display: flex;
  align-items: center;
}

.live { color: #37a067; }
.ended-tag { color: #e5594c; }
.queuing { color: #d58a1f; }

.arrow {
  margin-left: 8rpx;
  font-size: 18rpx;
  color: #b6bccb;
}

.h-right {
  display: flex;
  flex-shrink: 0;
  margin-left: 16rpx;
}

.act {
  font-size: 24rpx;
  margin-left: 12rpx;
  padding: 8rpx 18rpx;
  border-radius: 30rpx;
  background: #eef1ff;
  color: #5b7cfa;
}

.act.danger {
  background: #fdeceb;
  color: #e5594c;
}

.act.primary {
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  color: #fff;
}

/* ===== 客户名片 ===== */
.card-sec {
  background: #fff;
  margin: 16rpx 20rpx 0;
  border-radius: 18rpx;
  padding: 8rpx 26rpx;
  max-height: 40vh;
  overflow-y: auto;
  box-shadow: 0 4rpx 16rpx rgba(90, 110, 200, 0.08);
}

.card-row {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
  border-bottom: 1rpx solid #f1f3f9;
  font-size: 25rpx;
}

.card-row:last-child {
  border-bottom: none;
}

.k { color: #8a91a3; }
.v {
  color: #2b2f3a;
  max-width: 430rpx;
  text-align: right;
}

/* ===== 消息区域 ===== */
.msgs {
  flex: 1;
  overflow: hidden;
}

.inner {
  padding: 24rpx 28rpx 12rpx;
}

.msg-state {
  text-align: center;
  padding: 80rpx 0;
  color: #b6bccb;
  font-size: 26rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.retry-text {
  margin-top: 12rpx;
  color: #5b7cfa;
  font-weight: 600;
}

.row {
  margin-bottom: 22rpx;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.row.self {
  align-items: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 18rpx 22rpx;
  border-radius: 18rpx;
  font-size: 28rpx;
  line-height: 1.55;
  word-break: break-word;
}

.b-other {
  background: #fff;
  border-radius: 6rpx 20rpx 20rpx 20rpx;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}

.b-self {
  background: linear-gradient(135deg, #5b7cfa, #7a5cf0);
  color: #fff;
  border-radius: 20rpx 6rpx 20rpx 20rpx;
}

.ai {
  max-width: 560rpx;
}

.sender {
  font-size: 20rpx;
  color: #b6bccb;
  margin-top: 6rpx;
}

/* ===== 输入栏 ===== */
.input-bar {
  display: flex;
  align-items: center;
  background: #fff;
  border-top: 1rpx solid #eef0f6;
  padding: 14rpx 24rpx 18rpx;
}

.input {
  flex: 1;
  height: 76rpx;
  background: #f3f5fa;
  border-radius: 40rpx;
  padding: 0 28rpx;
  font-size: 28rpx;
}

.send {
  margin-left: 16rpx;
  padding: 14rpx 36rpx;
  font-size: 28rpx;
  font-weight: 600;
}

.send.disabled {
  opacity: 0.45;
}

/* ===== 建工单浮层 ===== */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(30, 34, 50, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 99;
}

.ticket {
  width: 82%;
  padding: 34rpx;
}

.t-title {
  font-size: 30rpx;
  font-weight: 700;
  margin-bottom: 20rpx;
}

.t-input,
.t-area {
  width: 100%;
  box-sizing: border-box;
  background: #f3f5fa;
  border-radius: 14rpx;
  padding: 0 22rpx;
  height: 76rpx;
  font-size: 26rpx;
  margin-bottom: 18rpx;
}

.t-area {
  height: 160rpx;
  padding: 16rpx 22rpx;
}

.t-btns {
  display: flex;
  justify-content: flex-end;
  margin-top: 6rpx;
}

.t-cancel {
  color: #8a91a3;
  margin-right: 26rpx;
  padding: 12rpx 0;
}

.t-ok {
  padding: 12rpx 40rpx;
  font-size: 26rpx;
  font-weight: 600;
}
</style>
