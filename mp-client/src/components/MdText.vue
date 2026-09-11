<template>
  <view class="md">
    <template v-for="(blk, i) in blocks" :key="i">
      <!-- 小标题：**xxx** 独占一行 -->
      <view v-if="blk.type === 'h'" class="md-h">
        <text v-for="(seg, j) in blk.segs" :key="j" :class="seg.b ? 'md-bold' : ''">{{ seg.s }}</text>
      </view>

      <!-- 列表项：- 或 1. 开头 -->
      <view v-else-if="blk.type === 'li'" class="md-li">
        <text class="md-li-dot">•</text>
        <text class="md-li-text">
          <text v-for="(seg, j) in blk.segs" :key="j" :class="seg.b ? 'md-bold' : ''">{{ seg.s }}</text>
        </text>
      </view>

      <!-- 表格 -->
      <view v-else-if="blk.type === 'table'" class="md-tbl-wrap">
        <view class="md-tbl">
          <view class="md-tbl-row md-tbl-head">
            <text v-for="(c, k) in blk.header" :key="k" class="md-tbl-cell">{{ c }}</text>
          </view>
          <view v-for="(row, r) in blk.rows" :key="r" class="md-tbl-row" :class="r % 2 === 1 ? 'md-tbl-alt' : ''">
            <text v-for="(c, k) in row" :key="k" class="md-tbl-cell">{{ c }}</text>
          </view>
        </view>
      </view>

      <!-- 普通段落 / 参考文件尾注 -->
      <text v-else-if="blk.type === 'p'" class="md-p">
        <text v-for="(seg, j) in blk.segs" :key="j" :class="seg.b ? 'md-bold' : ''">{{ seg.s }}</text>
      </text>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Seg {
  s: string
  b: boolean // 是否 **加粗**
}
type Block =
  | { type: 'h'; segs: Seg[] }
  | { type: 'li'; segs: Seg[] }
  | { type: 'p'; segs: Seg[] }
  | { type: 'table'; header: string[]; rows: string[][] }

const props = defineProps<{ text: string }>()

/** 行内解析：**加粗** / [文本](链接) 去链接只留文本 / 行内 | 保留 */
function inline(raw: string): Seg[] {
  // 先去掉 markdown 链接外壳：[label](url) -> label
  const text = raw.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  const segs: Seg[] = []
  const re = /\*\*([^*]+)\*\*/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) {
    if (m.index > last) segs.push({ s: text.slice(last, m.index), b: false })
    segs.push({ s: m[1], b: true })
    last = m.index + m[0].length
  }
  if (last < text.length) segs.push({ s: text.slice(last), b: false })
  return segs
}

function splitCells(line: string): string[] {
  const parts = line.split('|')
  // 去掉首尾因竖线产生的空段
  if (parts.length && parts[0].trim() === '') parts.shift()
  if (parts.length && parts[parts.length - 1].trim() === '') parts.pop()
  return parts.map((s) => s.trim())
}

const isTableLine = (l: string) => l.includes('|') && splitCells(l).length >= 2

const blocks = computed<Block[]>(() => {
  const lines = (props.text || '').split(/\r?\n/)
  const out: Block[] = []

  const flushTable = (rows: string[][]) => {
    // 去掉 |---| 分隔行；首行作表头
    const data = rows.filter((cells) => !cells.every((c) => /^-{2,}$/.test(c)))
    if (!data.length) return
    out.push({ type: 'table', header: data[0], rows: data.slice(1) })
  }

  let tbl: string[][] | null = null
  let para: string[] = []

  const flushPara = () => {
    if (!para.length) return
    const joined = para.join('')
    out.push({ type: 'p', segs: inline(joined) })
    para = []
  }

  for (const raw of lines) {
    const line = raw.trimEnd()
    const t = line.trim()
    if (!t) {
      flushPara()
      continue
    }

    // 表格连续行成块
    if (isTableLine(t)) {
      flushPara()
      if (!tbl) tbl = []
      tbl.push(splitCells(t))
      continue
    } else if (tbl) {
      flushTable(tbl)
      tbl = null
    }

    // 小标题：整行被 ** 包裹
    const hMatch = t.match(/^\*\*([^*]+)\*\*$/)
    if (hMatch) {
      flushPara()
      out.push({ type: 'h', segs: [{ s: hMatch[1].trim(), b: true }] })
      continue
    }

    // 列表项：- 开头，或编号开头（剥掉编号）
    let content: string | null = null
    const dash = t.match(/^[-–•]\s*(.*)$/)
    if (dash) {
      content = dash[1]
    } else {
      const num = t.match(/^\d+[.．、]\s*(.*)$/)
      if (num) content = num[1]
    }
    if (content != null) {
      flushPara()
      if (content) out.push({ type: 'li', segs: inline(content) })
      continue
    }

    // 分隔线
    if (/^-{3,}$/.test(t) || /^=+$/.test(t)) {
      flushPara()
      continue
    }

    // 普通文本行：并入当前段落（后端结构化答案是整行返回的，单行即可）
    para.push(t + '')
  }
  flushPara()
  if (tbl) flushTable(tbl)
  return out
})
</script>

<style lang="scss" scoped>
.md {
  font-size: 28rpx;
  line-height: 1.7;
  word-break: break-all;
}

.md-h {
  font-weight: 700;
  margin: 8rpx 0 4rpx;
}

.md-li {
  display: flex;
  align-items: flex-start;
  margin: 2rpx 0;
}

.md-li-dot {
  color: #7a5cf0;
  margin-right: 10rpx;
  flex-shrink: 0;
}

.md-li-text {
  flex: 1;
}

.md-bold {
  font-weight: 700;
}

.md-p {
  display: block;
}

/* 表格 */
.md-tbl-wrap {
  margin: 10rpx 0;
  overflow: hidden;
  border-radius: 12rpx;
  border: 1rpx solid #e5e9f5;
}

.md-tbl-row {
  display: flex;
}

.md-tbl-head {
  background: linear-gradient(135deg, #5b7cfa, #9a5cf5);
}

.md-tbl-head .md-tbl-cell {
  color: #fff;
  font-weight: 600;
}

.md-tbl-alt {
  background: #f6f8ff;
}

.md-tbl-cell {
  flex: 1;
  min-width: 0;
  padding: 12rpx 10rpx;
  font-size: 24rpx;
  border-right: 1rpx solid #e5e9f5;
  border-bottom: 1rpx solid #e5e9f5;
}

.md-tbl-row:last-child .md-tbl-cell {
  border-bottom: none;
}

.md-tbl-cell:last-child {
  border-right: none;
}
</style>
