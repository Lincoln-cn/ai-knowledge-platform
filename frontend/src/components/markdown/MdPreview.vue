<template>
  <div class="md-preview" @click="onClick">
    <template v-for="(block, i) in blocks" :key="i">
      <component
        :is="headingTag(block)"
        v-if="block.type === 'h'"
        class="md-h"
        v-html="block.html"
      />
      <p v-else-if="block.type === 'p'" class="md-p" v-html="block.html" />
      <ul v-else-if="block.type === 'ul'" class="md-ul">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ul>
      <ol v-else-if="block.type === 'ol'" class="md-ol">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ol>
      <pre v-else-if="block.type === 'code'" class="md-code"><code :class="block.lang ? 'lang-' + block.lang : ''" v-html="block.html" /></pre>
      <table v-else-if="block.type === 'table'" class="md-table">
        <thead>
          <tr>
            <th v-for="(cell, j) in block.header" :key="j" v-html="cell" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, r) in block.rows" :key="r">
            <td v-for="(cell, c) in row" :key="c" v-html="cell" />
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { citeIndexFromEvent, parseMarkdown } from './markdown'
import type { MdRenderOptions } from './types'

/**
 * 轻量 Markdown 预览。先转义再插标签，内容来自模型时也不注入 HTML。
 * 可选引注：打开后 [N] 变为可点击元素，抛出 `cite`，口径与会话 AnswerBody.vue 一致。
 */
defineOptions({ name: 'MdPreview' })

const props = withDefaults(
  defineProps<{
    source?: string
    citations?: boolean
  }>(),
  {
    source: '',
    citations: false
  }
)

const emit = defineEmits<{
  cite: [index: number]
}>()

const blocks = computed(() =>
  parseMarkdown(props.source, { citations: props.citations } satisfies MdRenderOptions)
)

/** 标题级别夹到 [1,6]，避免 level 越界产生非法标签 */
function headingTag(block: { type: string; level?: number }): string {
  const lv = Math.min(6, Math.max(1, block.level ?? 1))
  return `h${lv}`
}

function onClick(e: MouseEvent) {
  if (!props.citations) return
  const n = citeIndexFromEvent(e)
  if (n != null) emit('cite', n)
}
</script>

<style scoped>
.md-preview {
  font-family: var(--font-body);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink-2);
  word-break: break-word;
}

.md-p {
  margin: 0 0 var(--space-xs);
}

.md-p:last-child {
  margin-bottom: 0;
}

/* 标题：沿用设计令牌，中文字距为 0（中文标题字距纪律） */
.md-h {
  margin: var(--space-sm) 0 var(--space-xs);
  font-weight: 600;
  line-height: var(--leading-tight);
  color: var(--color-ink);
  letter-spacing: 0;
}

.md-h:first-child {
  margin-top: 0;
}

h1.md-h { font-size: var(--text-lg); }
h2.md-h { font-size: var(--text-md); }
h3.md-h { font-size: var(--text-sm); }
h4.md-h,
h5.md-h,
h6.md-h { font-size: var(--text-sm); }

.md-ul,
.md-ol {
  margin: 0 0 var(--space-xs);
  padding-left: var(--space-md);
}

.md-ul :deep(li),
.md-ol :deep(li) {
  margin: var(--space-3xs) 0;
}

/* 表格：发丝线分面，不靠阴影 */
.md-table {
  margin: 0 0 var(--space-xs);
  border-collapse: collapse;
  width: 100%;
  font-size: var(--text-2xs);
  line-height: var(--leading-body);
}

.md-table th,
.md-table td {
  border: 1px solid var(--color-rule);
  padding: var(--space-2xs) var(--space-xs);
  text-align: left;
  vertical-align: top;
}

.md-table th {
  background: var(--color-sunken);
  font-weight: 600;
  color: var(--color-ink);
}

.md-code {
  margin: 0 0 var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-sunken);
  border: 1px solid var(--color-rule-2);
  border-radius: var(--radius-sm);
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  line-height: var(--leading-tight);
}

.md-code :deep(code) {
  font-family: inherit;
  background: none;
  padding: 0;
}

.md-preview :deep(.cite) {
  color: var(--color-accent);
  cursor: pointer;
  padding: 0 var(--space-3xs);
  border-radius: var(--radius-xs);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}

.md-preview :deep(.cite:hover),
.md-preview :deep(.cite.active) {
  background: var(--color-accent-soft);
}

.md-preview :deep(code) {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  background: var(--color-sunken);
  padding: var(--space-3xs) var(--space-2xs);
  border-radius: var(--radius-xs);
}

.md-preview :deep(strong) {
  font-weight: 600;
  color: var(--color-ink);
}

.md-preview :deep(br) {
  line-height: var(--leading-body);
}
</style>
