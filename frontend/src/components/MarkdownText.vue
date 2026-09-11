<template>
  <div class="md-body" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 把一段文本按 Markdown 渲染为 HTML。
 * marked 负责解析（GFM + 单换行即断行，适合聊天回复），
 * DOMPurify 消毒，避免 AI 输出中的 <script>/on* / javascript: 造成 XSS。
 * 调用方自行决定哪些消息走 Markdown（一般只对机器人正文使用）。
 */
const props = defineProps<{ text: string }>()

const html = computed(() => {
  const src = props.text || ''
  const raw = marked.parse(src, { gfm: true, breaks: true, async: false }) as string
  return DOMPurify.sanitize(raw)
})
</script>

<style scoped>
.md-body {
  word-break: break-word;
  /* 覆盖可能从祖先气泡继承来的 white-space: pre-wrap，避免空行错乱 */
  white-space: normal;
  font-size: inherit;
  line-height: inherit;
}

/* 段落 / 标题 */
.md-body :deep(p) {
  margin: 0 0 0.6em;
}
.md-body :deep(p:last-child) {
  margin-bottom: 0;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4),
.md-body :deep(h5),
.md-body :deep(h6) {
  margin: 0.7em 0 0.35em;
  font-weight: 700;
  line-height: 1.4;
}
.md-body :deep(h1) {
  font-size: 1.25em;
}
.md-body :deep(h2) {
  font-size: 1.15em;
}
.md-body :deep(h3) {
  font-size: 1.08em;
}
.md-body :deep(h4),
.md-body :deep(h5),
.md-body :deep(h6) {
  font-size: 1em;
}

/* 列表 */
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.3em 0 0.6em;
  padding-left: 1.5em;
}
.md-body :deep(li) {
  margin: 0.15em 0;
}
.md-body :deep(li > ul),
.md-body :deep(li > ol) {
  margin-bottom: 0;
}

/* 行内代码 / 代码块 */
.md-body :deep(code) {
  background: #f0f2f8;
  padding: 0.12em 0.35em;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 0.88em;
}
.md-body :deep(pre) {
  background: #1f2430;
  color: #e6e9f2;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 0.4em 0 0.6em;
  line-height: 1.55;
}
.md-body :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
  font-size: 0.9em;
}

/* 链接 */
.md-body :deep(a) {
  color: var(--el-color-primary, #4c6fff);
  text-decoration: none;
}
.md-body :deep(a:hover) {
  text-decoration: underline;
}

/* 引用 / 分隔线 / 图片 / 删除线 */
.md-body :deep(blockquote) {
  border-left: 3px solid #d6dcf5;
  margin: 0.4em 0 0.6em;
  padding: 0.1em 0 0.1em 0.9em;
  color: #5b6069;
}
.md-body :deep(hr) {
  border: none;
  border-top: 1px solid #e7eaf3;
  margin: 0.7em 0;
}
.md-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
.md-body :deep(del) {
  color: #909399;
}

/* 表格 */
.md-body :deep(table) {
  border-collapse: collapse;
  margin: 0.4em 0 0.6em;
  width: 100%;
  font-size: 0.95em;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid #e7eaf3;
  padding: 6px 10px;
  text-align: left;
}
.md-body :deep(th) {
  background: #f7f8fc;
}
</style>
