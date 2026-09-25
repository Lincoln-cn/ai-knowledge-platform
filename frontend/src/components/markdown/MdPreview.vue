<template>
  <div class="md-preview" @click="onClick">
    <template v-for="(block, i) in blocks" :key="i">
      <p v-if="block.type === 'p'" class="md-p" v-html="block.html" />
      <ul v-else-if="block.type === 'ul'" class="md-ul">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ul>
      <ol v-else-if="block.type === 'ol'" class="md-ol">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ol>
      <pre v-else-if="block.type === 'code'" class="md-code"><code :class="block.lang ? 'lang-' + block.lang : ''" v-html="block.html" /></pre>
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

.md-ul,
.md-ol {
  margin: 0 0 var(--space-xs);
  padding-left: var(--space-md);
}

.md-ul :deep(li),
.md-ol :deep(li) {
  margin: var(--space-3xs) 0;
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
