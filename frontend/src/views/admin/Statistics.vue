<template>
  <div v-loading="loading">
    <!-- 总量指标卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col v-for="card in statCards" :key="card.label" :xs="12" :sm="8" :md="4">
        <el-card class="stat-card">
          <div class="stat-icon" :style="{ background: card.bg }">{{ card.icon }}</div>
          <div class="stat-value">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 趋势 + 满意度 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="16">
        <el-card>
          <div class="panel-title">近 7 天消息趋势</div>
          <div ref="trendRef" class="chart chart-lg"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <div class="panel-title">满意度评价</div>
          <div ref="satRef" class="chart chart-lg"></div>
          <div class="satisfy-count">共 {{ satisfaction?.count || 0 }} 条评价</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 三个分布饼图 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="8">
        <el-card>
          <div class="panel-title">工单状态分布</div>
          <div ref="ticketRef" class="chart chart-md"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <div class="panel-title">会话类型分布</div>
          <div ref="convRef" class="chart chart-md"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <div class="panel-title">向量化状态分布</div>
          <div ref="vectorRef" class="chart chart-md"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 意图分布 + 热门知识 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="12">
        <el-card>
          <div class="panel-title">用户意图分布</div>
          <div ref="intentRef" class="chart chart-md"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <div class="panel-title">热门知识 Top 10（按命中数）</div>
          <div ref="hotRef" class="chart chart-md"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getSummary, type StatisticsSummary } from '@/api/statistics'

const loading = ref(false)
const data = ref<StatisticsSummary | null>(null)

const trendRef = ref<HTMLElement>()
const satRef = ref<HTMLElement>()
const ticketRef = ref<HTMLElement>()
const convRef = ref<HTMLElement>()
const vectorRef = ref<HTMLElement>()
const intentRef = ref<HTMLElement>()
const hotRef = ref<HTMLElement>()

let charts: echarts.ECharts[] = []

const summary = computed(() => data.value)
const satisfaction = computed(() => data.value?.satisfaction)
const avgRating = computed(() => {
  const avg = data.value?.satisfaction?.avg
  return avg == null ? 0 : Math.round(avg * 10) / 10
})

const statCards = computed(() => [
  { label: '用户数', value: data.value?.userCount ?? 0, icon: '👤', bg: '#eef1ff' },
  { label: '会话数', value: data.value?.conversationCount ?? 0, icon: '💬', bg: '#e8f7ef' },
  { label: '消息数', value: data.value?.messageCount ?? 0, icon: '📩', bg: '#eaf3ff' },
  { label: '知识片段', value: data.value?.chunkCount ?? 0, icon: '📚', bg: '#fff4e6' },
  { label: '工单数', value: data.value?.ticketCount ?? 0, icon: '🎫', bg: '#ffeef0' },
  { label: '今日消息', value: data.value?.todayMessageCount ?? 0, icon: '🕒', bg: '#f2f0ff' }
])

const BRAND = '#4c6fff'
const PALETTE = ['#4c6fff', '#7b5cff', '#22c1a3', '#f59e0b', '#f56c6c', '#909399', '#67c23a']

// ==================== 图表数据 ====================

function buildTrend() {
  const map = new Map((data.value?.messageTrend ?? []).map((t) => [t.d, t.count]))
  const days: string[] = []
  const counts: number[] = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    days.push(key.slice(5))
    counts.push(map.get(key) ?? 0)
  }
  return { days, counts }
}

function pieData(items: { name: string; value: number }[]) {
  return items.filter((i) => i.value > 0)
}

function ticketPie() {
  const labels: Record<number, string> = { 1: '待处理', 2: '处理中', 3: '已解决', 4: '已关闭' }
  return pieData(
    (data.value?.ticketStatus ?? []).map((i) => ({
      name: labels[i.status ?? 0] ?? `状态${i.status}`,
      value: i.count
    }))
  )
}

function convPie() {
  const labels: Record<string, string> = { AUTO: '机器人', HUMAN: '人工' }
  return pieData(
    (data.value?.conversationType ?? []).map((i) => ({
      name: labels[i.type ?? ''] ?? i.type ?? '未知',
      value: i.count
    }))
  )
}

function vectorPie() {
  const labels: Record<number, string> = { 0: '待向量化', 1: '已向量化', 2: '失败' }
  return pieData(
    (data.value?.chunkVector ?? []).map((i) => ({
      name: labels[i.status ?? 0] ?? `状态${i.status}`,
      value: i.count
    }))
  )
}

function intentPie() {
  return pieData(
    (data.value?.intentDist ?? []).map((i) => ({
      name: i.category || '其他',
      value: i.count
    }))
  )
}

function hotBar() {
  const list = (data.value?.hotKnowledge ?? []).slice(0, 10)
  return {
    titles: list.map((k) => (k.title && k.title.length > 12 ? k.title.slice(0, 12) + '…' : k.title || '')),
    counts: list.map((k) => k.hitCount ?? 0)
  }
}

// ==================== 图表渲染 ====================

function pieOption(items: { name: string; value: number }[]) {
  return {
    color: PALETTE,
    tooltip: { trigger: 'item', formatter: '{b}: {c}（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: items
      }
    ]
  }
}

function renderCharts() {
  const { days, counts } = buildTrend()

  const trend = echarts.init(trendRef.value!)
  trend.setOption({
    color: [BRAND],
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: days, boundaryGap: false, axisLine: { lineStyle: { color: '#e4e7ed' } } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#f0f2f5' } } },
    series: [
      {
        type: 'line',
        smooth: true,
        data: counts,
        symbolSize: 6,
        lineStyle: { width: 3 },
        areaStyle: { color: 'rgba(76,111,255,0.12)' }
      }
    ]
  })

  const sat = echarts.init(satRef.value!)
  sat.setOption({
    series: [
      {
        type: 'gauge',
        min: 0,
        max: 5,
        startAngle: 210,
        endAngle: -30,
        progress: { show: true, width: 12, itemStyle: { color: BRAND } },
        axisLine: { lineStyle: { width: 12, color: [[1, '#eef0f6']] } },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        pointer: { show: false },
        detail: { valueAnimation: true, formatter: '{value}', fontSize: 30, offsetCenter: [0, 0], color: BRAND },
        data: [{ value: avgRating.value }]
      }
    ]
  })

  const ticket = echarts.init(ticketRef.value!)
  ticket.setOption(pieOption(ticketPie()))

  const conv = echarts.init(convRef.value!)
  conv.setOption(pieOption(convPie()))

  const vector = echarts.init(vectorRef.value!)
  vector.setOption(pieOption(vectorPie()))

  const intent = echarts.init(intentRef.value!)
  intent.setOption(pieOption(intentPie()))

  const hot = echarts.init(hotRef.value!)
  const { titles, counts: hotCounts } = hotBar()
  hot.setOption({
    color: [BRAND],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 10, right: 40, top: 10, bottom: 10, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#f0f2f5' } } },
    yAxis: { type: 'category', data: titles, axisLine: { show: false }, axisTick: { show: false } },
    series: [
      {
        type: 'bar',
        data: hotCounts,
        barWidth: 12,
        itemStyle: { borderRadius: [0, 6, 6, 0] },
        label: { show: true, position: 'right', color: '#5b6069' }
      }
    ]
  })

  charts = [trend, sat, ticket, conv, vector, intent, hot]
}

function resize() {
  charts.forEach((c) => c.resize())
}

onMounted(async () => {
  loading.value = true
  try {
    data.value = await getSummary()
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  charts.forEach((c) => c.dispose())
  charts = []
})
</script>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
  padding: 8px 0;
}

.stat-icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 10px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
}

.stat-label {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 4px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 16px;
}

.chart {
  width: 100%;
}

.chart-lg {
  height: 280px;
}

.chart-md {
  height: 260px;
}

.satisfy-count {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 8px;
  text-align: center;
}
</style>
