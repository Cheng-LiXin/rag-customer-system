<template>
  <view class="page">
    <view v-if="loading && list.length === 0" class="empty">
      <text class="empty-text">加载中…</text>
    </view>

    <scroll-view
      v-else
      class="list"
      scroll-y
      @scrolltolower="loadMore"
    >
      <view v-for="conv in list" :key="conv.id" class="item card" @click="open(conv)">
        <view class="row1">
          <text class="title">{{ conv.title || '（无标题）' }}</text>
          <view class="tags">
            <text class="tag" :class="conv.sessionType === 'HUMAN' ? 'human' : 'ai'">{{
              conv.sessionType === 'HUMAN' ? '人工' : '机器人'
            }}</text>
            <text class="tag" :class="conv.status === 1 ? 'live' : 'end'">{{
              conv.status === 1 ? '进行中' : '已结束'
            }}</text>
          </view>
        </view>
        <view class="row2">
          <text class="time">{{ conv.createTime }}</text>
          <text class="go">查看 ›</text>
        </view>
      </view>

      <view v-if="list.length === 0" class="empty">
        <text class="empty-text">暂无历史对话</text>
      </view>
      <view v-if="finished" class="foot-text">— 到底了 —</view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { getHistory } from '@/api/chat'
import { REOPEN_KEY } from '@/utils/config'
import type { Conversation } from '@/types'

const list = ref<Conversation[]>([])
const page = ref(1)
const total = ref(0)
const finished = ref(false)
const loading = ref(false)

async function load(reset = false) {
  if (loading.value) return
  if (reset) {
    page.value = 1
    finished.value = false
  }
  loading.value = true
  try {
    const res = await getHistory(page.value, 15)
    total.value = res.total
    if (reset) list.value = res.records
    else list.value = list.value.concat(res.records)
    if (list.value.length >= total.value) finished.value = true
  } catch (e) {
    uni.showToast({ title: '加载历史失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (finished.value || loading.value) return
  page.value += 1
  load()
}

function open(conv: Conversation) {
  // 记录会话 id，返回首页时回放
  uni.setStorageSync(REOPEN_KEY, String(conv.id))
  uni.navigateBack({
    fail: () => uni.reLaunch({ url: '/pages/index/index' })
  })
}

onShow(() => load(true))
onReachBottom(loadMore)
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 28rpx;
  box-sizing: border-box;
}

.list {
  height: calc(100vh - 40rpx);
}

.item {
  padding: 26rpx;
  margin-bottom: 20rpx;
}

.row1 {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 600;
  color: #2b2f3a;
  margin-right: 16rpx;
}

.tags {
  display: flex;
  flex-shrink: 0;
}

.tag {
  font-size: 20rpx;
  border-radius: 8rpx;
  padding: 2rpx 12rpx;
  margin-left: 8rpx;
}

.tag.ai {
  background: #eef1ff;
  color: #5b7cfa;
}

.tag.human {
  background: #fff4e5;
  color: #d58a1f;
}

.tag.live {
  background: #e8f7ee;
  color: #37a067;
}

.tag.end {
  background: #f0f1f4;
  color: #8a91a3;
}

.row2 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14rpx;
}

.time {
  font-size: 22rpx;
  color: #9aa0b0;
}

.go {
  font-size: 24rpx;
  color: #5b7cfa;
}

.empty {
  padding: 120rpx 0;
  text-align: center;
}

.empty-text {
  color: #a6adbf;
  font-size: 28rpx;
}

.foot-text {
  text-align: center;
  color: #c0c6d4;
  font-size: 24rpx;
  padding: 20rpx 0;
}
</style>
