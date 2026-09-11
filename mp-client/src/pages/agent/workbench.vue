<template>
  <view class="page">
    <!-- 顶部 -->
    <view class="head">
      <view class="h-brand">
        <view class="h-logo-wrap">🎧</view>
        <view>
          <view class="h-title">客服工作台</view>
          <view class="h-sub">{{ displayName }} · 会话 {{ result.mine.length }}</view>
        </view>
      </view>
      <view class="h-right">
        <view class="status-dot" :class="connected ? 'on' : ''"></view>
        <text class="sta">{{ connected ? '在线' : '连接中' }}</text>
        <text class="logout-btn" @click="handleLogout">退出</text>
      </view>
    </view>

    <!-- Tab -->
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: tab === t.key }"
        @click="tab = t.key"
      >
        <text>{{ t.label }}</text>
        <text v-if="count(t.key)" class="badge">{{ count(t.key) }}</text>
      </view>
    </view>

    <!-- 加载中 -->
    <view v-if="loading" class="state-box">
      <text class="state-icon">⏳</text>
      <text class="state-text">加载中…</text>
    </view>

    <!-- 加载失败 -->
    <view v-else-if="loadError" class="state-box" @click="loadWorkbench">
      <text class="state-icon">😵</text>
      <text class="state-text">{{ loadError }}</text>
      <text class="state-retry">点击重试</text>
    </view>

    <!-- 会话列表 -->
    <scroll-view v-else class="list" scroll-y :refresher-enabled="true" :refresher-triggered="refreshing" @refresherrefresh="onRefresh">
      <view v-if="!curList.length" class="empty">
        <text class="empty-icon">{{ emptyIcon }}</text>
        <text class="empty-text">{{ emptyText }}</text>
      </view>

      <view
        v-for="c in curList"
        :key="c.id"
        class="conv card"
        :class="isEndedTab ? 'ended' : ''"
        @click="open(c)"
      >
        <view class="c-top">
          <text class="c-name">{{ convLabel(c) }}</text>
          <text v-if="c.unread && !isEndedTab" class="unread">{{ c.unread }}</text>
        </view>
        <view class="c-mid">
          <text class="c-title" v-if="c.title">{{ c.title }}</text>
        </view>
        <view class="c-sub">
          <text class="c-id">会话 #{{ c.id }}</text>
          <text v-if="!isEndedTab && c.sessionType" class="stype">{{ c.sessionType === 'HUMAN' ? '人工' : '机器人' }}</text>
          <text v-if="isEndedTab" class="again" @click.stop="reopen(c)">再次接待</text>
        </view>
      </view>
    </scroll-view>

    <view class="foot safe-bottom">
      <text class="f-btn" @click="goTickets">📋 我的工单</text>
      <text class="f-btn danger" @click="handleLogout">🚪 退出登录</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { workbench, reopenConversation } from '@/api/agent'
import { auth, fetchProfile, logout as storeLogout, displayName } from '@/stores/auth'
import { openWs, wsSend, wsClose, wsOpen } from '@/utils/ws'
import { emit } from '@/utils/bus'
import type { WorkbenchConversation, WorkbenchResult } from '@/types'

const tab = ref<'mine' | 'queue' | 'ended'>('mine')
const connected = ref(false)
const loading = ref(true)
const loadError = ref('')
const refreshing = ref(false)
const result = reactive<WorkbenchResult>({ agentId: 0, onlineAgents: [], queue: [], mine: [], ended: [] })

const tabs = [
  { key: 'mine', label: '进行中' },
  { key: 'queue', label: '排队' },
  { key: 'ended', label: '已结束' }
] as const

const curList = computed<WorkbenchConversation[]>(() => {
  if (tab.value === 'mine') return result.mine
  if (tab.value === 'queue') return result.queue
  return result.ended
})

const isEndedTab = computed(() => tab.value === 'ended')

const emptyIcon = computed(() => {
  if (tab.value === 'mine') return '📭'
  if (tab.value === 'queue') return '⏳'
  return '📂'
})

const emptyText = computed(() => {
  if (tab.value === 'mine') return '暂无进行中的会话'
  if (tab.value === 'queue') return '暂无排队中的会话'
  return '暂无已结束的会话'
})

function count(k: string) {
  if (k === 'mine') return result.mine.length
  if (k === 'queue') return result.queue.length
  return result.ended.length
}

function convLabel(c: WorkbenchConversation) {
  if (c.userName) return c.userName
  return c.userId === 0 ? '游客' : '用户'
}

async function loadWorkbench() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await workbench()
    result.agentId = res.agentId || 0
    result.onlineAgents = res.onlineAgents || []
    result.queue = res.queue || []
    result.mine = res.mine || []
    result.ended = res.ended || []
  } catch (e: any) {
    loadError.value = e?.message || '加载工作台失败'
  } finally {
    loading.value = false
  }
}

async function onRefresh() {
  refreshing.value = true
  await loadWorkbench()
  refreshing.value = false
}

function connectWs() {
  if (wsOpen()) return
  const agentId = auth.profile?.id
  if (!agentId) {
    connected.value = false
    return
  }
  openWs({
    onOpen: () => {
      connected.value = true
      wsSend({ type: 'register', role: 'agent', agentId })
      // 连接成功后刷新数据
      loadWorkbench()
    },
    onMessage: (msg: any) => {
      if (msg.type === 'assigned') {
        uni.showToast({ title: `新会话 #${msg.conversationId} 已分配给你`, icon: 'none' })
        loadWorkbench()
        emit('conv:assigned', { conversationId: msg.conversationId })
      } else if (msg.type === 'closed') {
        uni.showToast({
          title:
            msg.reason === 'timeout'
              ? `会话 #${msg.conversationId} 因长时间未对话已自动结束`
              : `会话 #${msg.conversationId} 已结束`,
          icon: 'none'
        })
        loadWorkbench()
        emit('conv:closed', { conversationId: msg.conversationId, reason: msg.reason })
      } else if (msg.type === 'message') {
        loadWorkbench()
        emit('conv:msg', { conversationId: msg.conversationId, content: msg.content })
      }
    },
    onClose: () => {
      connected.value = false
    },
    onError: () => {
      connected.value = false
    }
  }, true) // 开启自动重连
}

function open(c: WorkbenchConversation) {
  uni.navigateTo({
    url:
      `/pages/agent/chat?convId=${c.id}` +
      `&name=${encodeURIComponent(convLabel(c))}` +
      `&queue=${tab.value === 'queue' ? 1 : 0}` +
      `&ended=${tab.value === 'ended' ? 1 : 0}`
  })
}

async function reopen(c: WorkbenchConversation) {
  try {
    await reopenConversation(c.id)
    uni.showToast({ title: '已重新接待', icon: 'success' })
    await loadWorkbench()
  } catch (e: any) {
    uni.showToast({ title: e?.message || '操作失败', icon: 'none' })
  }
}

function goTickets() {
  uni.navigateTo({ url: '/pages/agent/tickets' })
}

function handleLogout() {
  // 先关 WS（设 autoReconnect=false 阻止重连）
  wsClose()
  // 清登录态
  storeLogout()
  // 延迟跳转确保 WS 和存储操作完成
  setTimeout(() => {
    uni.reLaunch({ url: '/pages/login/login' })
  }, 50)
}

onMounted(async () => {
  // 确保有 profile
  if (!auth.profile?.id) {
    try {
      await fetchProfile()
    } catch { /* ignore */ }
  }
  if (auth.profile?.id) {
    await loadWorkbench()
    connectWs()
  } else {
    loadError.value = '登录已失效，请重新登录'
    setTimeout(() => uni.reLaunch({ url: '/pages/login/login' }), 1500)
  }
})

onUnmounted(() => {
  // 页面被卸载（reLaunch）时关 WS
  wsClose()
})
</script>

<style lang="scss" scoped>
.page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f0f4ff 0%, #f6f7fb 200rpx);
  box-sizing: border-box;
}

/* ===== 顶部 ===== */
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 32rpx;
  background: rgba(255, 255, 255, 0.92);
}

.h-brand {
  display: flex;
  align-items: center;
}

.h-logo-wrap {
  width: 72rpx;
  height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 36rpx;
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  border-radius: 20rpx;
  margin-right: 18rpx;
  box-shadow: 0 6rpx 20rpx rgba(140, 92, 255, 0.3);
}

.h-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #2b2f3a;
}

.h-sub {
  font-size: 22rpx;
  color: #8a91a3;
  margin-top: 2rpx;
}

.h-right {
  display: flex;
  align-items: center;
}

.status-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background: #f0ad4e;
  margin-right: 8rpx;
}
.status-dot.on {
  background: #37c870;
}
.sta {
  font-size: 24rpx;
  color: #6b7280;
  margin-right: 20rpx;
}
.logout-btn {
  font-size: 24rpx;
  color: #e5594c;
  padding: 8rpx 16rpx;
}

/* ===== Tab ===== */
.tabs {
  display: flex;
  padding: 8rpx 32rpx 0;
  background: rgba(255, 255, 255, 0.6);
}

.tab {
  margin-right: 40rpx;
  padding: 16rpx 4rpx;
  font-size: 30rpx;
  color: #6b7280;
  position: relative;
  display: flex;
  align-items: center;
}

.tab.active {
  color: #5b7cfa;
  font-weight: 700;
}

.tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 4rpx;
  height: 6rpx;
  border-radius: 6rpx;
  background: linear-gradient(90deg, #5b7cfa, #9a5cf5);
}

.badge {
  margin-left: 8rpx;
  background: #f05c5c;
  color: #fff;
  border-radius: 16rpx;
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  text-align: center;
  font-size: 20rpx;
  padding: 0 8rpx;
}

/* ===== 加载/错误状态 ===== */
.state-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120rpx 0;
}

.state-icon {
  font-size: 72rpx;
  margin-bottom: 20rpx;
}

.state-text {
  font-size: 28rpx;
  color: #6b7280;
  text-align: center;
  padding: 0 48rpx;
}

.state-retry {
  margin-top: 20rpx;
  font-size: 26rpx;
  color: #5b7cfa;
  font-weight: 600;
}

/* ===== 会话列表 ===== */
.list {
  flex: 1;
  overflow: hidden;
  padding: 20rpx 28rpx 0;
  box-sizing: border-box;
}

.conv {
  padding: 24rpx 26rpx;
  margin-bottom: 18rpx;
  position: relative;
  overflow: hidden;
}

.conv::before {
  content: '';
  position: absolute;
  left: 0;
  top: 20rpx;
  bottom: 20rpx;
  width: 6rpx;
  border-radius: 6rpx;
  background: linear-gradient(180deg, #5b7cfa, #9a5cf5);
}

.conv.ended {
  opacity: 0.75;
}

.conv.ended::before {
  background: #b9c2d4;
}

.c-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.c-name {
  font-size: 30rpx;
  font-weight: 600;
  color: #2b2f3a;
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.unread {
  background: linear-gradient(135deg, #f05c5c, #f78b3a);
  color: #fff;
  border-radius: 18rpx;
  min-width: 36rpx;
  height: 36rpx;
  line-height: 36rpx;
  text-align: center;
  padding: 0 12rpx;
  font-size: 20rpx;
  flex-shrink: 0;
}

.c-mid {
  margin-top: 6rpx;
}

.c-title {
  font-size: 24rpx;
  color: #4a5163;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  display: block;
}

.c-sub {
  margin-top: 10rpx;
  display: flex;
  align-items: center;
  font-size: 24rpx;
  color: #9aa0b0;
}

.c-id {
  margin-right: 16rpx;
}

.stype {
  font-size: 20rpx;
  color: #5b7cfa;
  background: #eef1ff;
  border-radius: 6rpx;
  padding: 2rpx 10rpx;
}

.again {
  margin-left: 16rpx;
  color: #5b7cfa;
  font-weight: 600;
  padding: 4rpx 16rpx;
  background: #eef1ff;
  border-radius: 20rpx;
}

.empty {
  text-align: center;
  padding: 120rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.empty-icon {
  font-size: 72rpx;
  margin-bottom: 16rpx;
}

.empty-text {
  color: #b6bccb;
  font-size: 28rpx;
}

/* ===== 底部 ===== */
.foot {
  display: flex;
  justify-content: space-around;
  background: #fff;
  border-top: 1rpx solid #eef0f6;
  padding: 18rpx 20rpx 24rpx;
}

.f-btn {
  font-size: 26rpx;
  color: #4a5163;
  background: #f3f5fa;
  border-radius: 40rpx;
  padding: 12rpx 36rpx;
}

.f-btn.danger {
  color: #e5594c;
  background: #fdeceb;
}
</style>
