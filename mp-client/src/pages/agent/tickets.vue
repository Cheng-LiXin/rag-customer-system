<template>
  <view class="page">
    <view class="top">
      <view class="tabs">
        <view
          v-for="(label, k) in statusMap"
          :key="k"
          class="tab"
          :class="{ active: filterStatus === Number(k) }"
          @click="changeFilter(Number(k))"
        >
          {{ label }}
        </view>
      </view>
      <text class="new grad-btn" @click="ticketVisible = true">＋新建</text>
    </view>

    <scroll-view class="list" scroll-y @scrolltolower="loadMore">
      <!-- 加载中 -->
      <view v-if="loading && !list.length" class="state-box">
        <text>加载中…</text>
      </view>

      <!-- 工单列表 -->
      <view v-for="t in list" :key="t.id" class="item card" @click="expandId = expandId === t.id ? 0 : t.id">
        <view class="r1">
          <text class="t-title">{{ t.title }}</text>
          <text class="st" :class="'s' + (t.status || 0)">{{ statusMap[t.status || 0] || '未知' }}</text>
        </view>
        <view class="r2">
          <text class="sub">{{ t.category || '未分类' }}</text>
          <text class="sub" v-if="t.priority">优先级{{ priorityMap[t.priority] || t.priority }}</text>
          <text class="sub">#{{ t.id }}</text>
        </view>
        <view class="r2">
          <text class="sub">{{ fmtTime(t.createTime) }}</text>
          <text v-if="t.conversationId" class="sub">会话 #{{ t.conversationId }}</text>
        </view>

        <!-- 展开详情 + 流转 -->
        <view v-if="expandId === t.id" class="detail">
          <view v-if="t.description" class="desc">{{ t.description }}</view>
          <view v-else class="desc grey">（无描述）</view>
          <view class="btns">
            <text v-if="t.status === 1" class="btn grad" @click.stop="doStatus(t, 2)">开始处理</text>
            <text v-if="t.status === 2" class="btn grad" @click.stop="doStatus(t, 3)">标记解决</text>
            <text v-if="t.status && t.status < 4" class="btn plain" @click.stop="doStatus(t, 4)">关闭工单</text>
          </view>
        </view>
      </view>

      <!-- 空状态 -->
      <view v-if="!list.length && !loading" class="empty">
        <text class="empty-icon">📋</text>
        <text>暂无工单</text>
      </view>

      <!-- 底部提示 -->
      <view v-if="list.length > 0 && finished" class="foot-text">— 到底了 —</view>
      <view v-if="loading && list.length > 0" class="foot-text">加载中…</view>
    </scroll-view>

    <!-- 新建工单 -->
    <view v-if="ticketVisible" class="mask" @click="ticketVisible = false">
      <view class="tk card" @click.stop>
        <view class="tk-title">新建工单</view>
        <input v-model="form.title" class="in" placeholder="工单标题（必填）" :maxlength="60" />
        <input v-model="form.category" class="in" placeholder="分类，如：招生咨询（选填）" :maxlength="20" />
        <textarea v-model="form.description" class="area" placeholder="问题描述（选填）" :maxlength="300" />
        <view class="tk-btns">
          <text class="cancel" @click="ticketVisible = false">取消</text>
          <text class="ok grad-btn" @click="submit">创建</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { myTickets, createTicket, updateTicketStatus } from '@/api/ticket'
import { auth } from '@/stores/auth'
import type { Ticket } from '@/types'

const statusMap: Record<number, string> = { 1: '待处理', 2: '处理中', 3: '已解决', 4: '已关闭' }
const priorityMap: Record<number, string> = { 1: '低', 2: '中', 3: '高' }

const list = ref<Ticket[]>([])
const filterStatus = ref<number | undefined>(undefined)
const page = ref(1)
const finished = ref(false)
const loading = ref(false)
const expandId = ref(0)

const ticketVisible = ref(false)
const form = ref({ title: '', category: '', description: '' })

function fmtTime(s?: string) {
  if (!s) return ''
  return s.replace('T', ' ').slice(0, 16) || s
}

function changeFilter(s: number) {
  filterStatus.value = s === filterStatus.value ? undefined : s
  load(true)
}

async function load(reset = false) {
  if (loading.value) return
  if (reset) {
    page.value = 1
    finished.value = false
    list.value = []
  }
  loading.value = true
  try {
    const params: Record<string, unknown> = { pageNum: page.value, pageSize: 15 }
    if (filterStatus.value != null) params.status = filterStatus.value
    if (auth.profile?.id) params.assigneeId = auth.profile.id
    const res = await myTickets(params)
    if (reset) {
      list.value = res.records || []
    } else {
      list.value = list.value.concat(res.records || [])
    }
    if (list.value.length >= (res.total || 0)) finished.value = true
  } catch (e: any) {
    uni.showToast({ title: e?.message || '加载工单失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (finished.value || loading.value) return
  page.value += 1
  load()
}

async function doStatus(t: Ticket, s: number) {
  try {
    await updateTicketStatus(t.id, s)
    uni.showToast({ title: '状态已更新', icon: 'success' })
    // 更新本地状态
    t.status = s
    if (s === 4) {
      t.closeTime = new Date().toISOString()
    }
  } catch (e: any) {
    uni.showToast({ title: e?.message || '操作失败', icon: 'none' })
  }
}

async function submit() {
  const title = form.value.title.trim()
  if (!title) return uni.showToast({ title: '请填写工单标题', icon: 'none' })
  try {
    await createTicket({
      title,
      category: form.value.category.trim(),
      description: form.value.description.trim()
    })
    ticketVisible.value = false
    form.value = { title: '', category: '', description: '' }
    uni.showToast({ title: '工单已创建', icon: 'success' })
    load(true)
  } catch (e: any) {
    uni.showToast({ title: e?.message || '创建失败', icon: 'none' })
  }
}

onShow(() => load(true))
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  box-sizing: border-box;
  background: #f6f7fb;
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  background: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}

.tabs {
  display: flex;
  flex: 1;
}

.tab {
  font-size: 24rpx;
  color: #6b7280;
  padding: 8rpx 16rpx;
  margin-right: 8rpx;
  border-radius: 16rpx;
}

.tab.active {
  color: #5b7cfa;
  font-weight: 700;
  background: #eef1ff;
}

.new {
  padding: 10rpx 26rpx;
  font-size: 24rpx;
  font-weight: 600;
}

.list {
  height: calc(100vh - 100rpx);
  padding: 0 28rpx;
  box-sizing: border-box;
}

.state-box {
  text-align: center;
  padding: 120rpx 0;
  color: #b6bccb;
  font-size: 26rpx;
}

.item {
  padding: 24rpx;
  margin-top: 20rpx;
}

.r1 {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.t-title {
  flex: 1;
  font-size: 29rpx;
  font-weight: 600;
  color: #2b2f3a;
  margin-right: 16rpx;
}

.st {
  flex-shrink: 0;
  font-size: 20rpx;
  border-radius: 8rpx;
  padding: 2rpx 12rpx;
}

.s1 { background: #fdeceb; color: #e5594c; }
.s2 { background: #eef1ff; color: #5b7cfa; }
.s3 { background: #e8f7ee; color: #37a067; }
.s4 { background: #f0f1f4; color: #8a91a3; }

.r2 {
  display: flex;
  flex-wrap: wrap;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #9aa0b0;
}

.sub {
  margin-right: 20rpx;
}

.detail {
  border-top: 1rpx solid #f1f3f9;
  margin-top: 14rpx;
  padding-top: 14rpx;
}

.desc {
  font-size: 26rpx;
  color: #4a5163;
  margin-bottom: 14rpx;
  line-height: 1.6;
}

.desc.grey {
  color: #b6bccb;
}

.btns {
  display: flex;
  justify-content: flex-end;
}

.btn {
  font-size: 24rpx;
  padding: 10rpx 28rpx;
  border-radius: 30rpx;
  margin-left: 14rpx;
}

.btn.grad {
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
  color: #fff;
}

.btn.plain {
  background: #f0f1f4;
  color: #4a5163;
}

.empty {
  text-align: center;
  color: #b6bccb;
  padding: 120rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.empty-icon {
  font-size: 64rpx;
  margin-bottom: 16rpx;
}

.foot-text {
  text-align: center;
  color: #c0c6d4;
  font-size: 24rpx;
  padding: 20rpx 0;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(30, 34, 50, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 99;
}

.tk {
  width: 82%;
  padding: 32rpx;
}

.tk-title {
  font-size: 30rpx;
  font-weight: 700;
  margin-bottom: 20rpx;
}

.in,
.area {
  width: 100%;
  box-sizing: border-box;
  background: #f3f5fa;
  border-radius: 14rpx;
  padding: 0 22rpx;
  height: 76rpx;
  font-size: 26rpx;
  margin-bottom: 18rpx;
}

.area {
  height: 160rpx;
  padding: 16rpx 22rpx;
}

.tk-btns {
  display: flex;
  justify-content: flex-end;
}

.cancel {
  color: #8a91a3;
  margin-right: 26rpx;
  padding: 12rpx 0;
}

.ok {
  padding: 12rpx 40rpx;
  font-size: 26rpx;
  font-weight: 600;
}
</style>
