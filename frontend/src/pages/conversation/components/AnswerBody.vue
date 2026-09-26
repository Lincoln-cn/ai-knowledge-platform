<template>
  <div class="answer" @click="onClick">
    <template v-for="(block, i) in blocks" :key="i">
      <p v-if="block.type === 'p'" class="a-p" v-html="block.html" />
      <ul v-else-if="block.type === 'ul'" class="a-ul">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ul>
      <ol v-else-if="block.type === 'ol'" class="a-ol">
        <li v-for="(item, j) in block.items" :key="j" v-html="item" />
      </ol>
    </template>
    <span v-if="streaming" class="cursor" aria-hidden="true" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 轻量 Markdown 渲染。
 * 旧项目用 md-editor-v3；本工程未引入该依赖，这里只做问答正文需要的子集：
 * 段落 / 有序·无序列表 / **粗体** / `代码` / 可点击引用 [N]。
 * 先转义再拼标签，避免 v-html 注入。
 */
const props = defineProps<{
  text: string
  streaming?: boolean
  /** 当前激活的引用编号 —— 对应正文里的 [N] 高亮，方便「回滚定位」 */
  activeIndex?: number | null
}>()

const emit = defineEmits<{
  cite: [index: number]
}>()

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function renderInline(raw: string): string {
  let html = escapeHtml(raw)
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(
    /\[(\d+)\]/g,
    (_m, n: string) =>
      `<span class="cite${Number(n) === props.activeIndex ? ' active' : ''}" data-ref="${n}" role="button" tabindex="0">[${n}]</span>`
  )
  return html
}

type Block =
  | { type: 'p'; html: string }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }

const blocks = computed<Block[]>(() => {
  const lines = (props.text || '').split(/\r?\n/)
  const out: Block[] = []
  let para: string[] = []
  let ul: string[] = []
  let ol: string[] = []

  const flushPara = () => {
    if (para.length) {
      out.push({ type: 'p', html: renderInline(para.join(' ')) })
      para = []
    }
  }
  const flushUl = () => {
    if (ul.length) {
      out.push({ type: 'ul', items: ul.map(renderInline) })
      ul = []
    }
  }
  const flushOl = () => {
    if (ol.length) {
      out.push({ type: 'ol', items: ol.map(renderInline) })
      ol = []
    }
  }
  const flushAll = () => {
    flushPara()
    flushUl()
    flushOl()
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) {
      flushAll()
      continue
    }
    const ulMatch = /^[-*]\s+(.*)$/.exec(trimmed)
    const olMatch = /^\d+[.)]\s+(.*)$/.exec(trimmed)
    if (ulMatch) {
      flushPara()
      flushOl()
      ul.push(ulMatch[1]!)
      continue
    }
    if (olMatch) {
      flushPara()
      flushUl()
      ol.push(olMatch[1]!)
      continue
    }
    flushUl()
    flushOl()
    para.push(trimmed)
  }
  flushAll()
  return out
})

function onClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  const cite = target?.closest('.cite') as HTMLElement | null
  if (!cite) return
  const n = Number(cite.dataset.ref)
  if (Number.isFinite(n)) emit('cite', n)
}

/**
 * 「回滚定位」：从引用面板回到正文里的原句角标。
 * 找到 [N] 后滚入视野 —— 看完引用能回到刚才那句话，而不是在面板里迷路。
 */
function scrollToCite(index: number) {
  const el = document.querySelector(`.cite[data-ref="${index}"]`) as HTMLElement | null
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

defineExpose({ scrollToCite })
</script>

<style scoped>
.answer {
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  color: var(--color-ink);
  word-break: break-word;
}

.a-p {
  margin: 0 0 var(--space-xs);
}

.a-p:last-child {
  margin-bottom: 0;
}

.a-ul,
.a-ol {
  margin: 0 0 var(--space-xs);
  padding-left: var(--space-lg);
}

.a-ul :deep(li),
.a-ol :deep(li) {
  margin: var(--space-3xs) 0;
}

.answer :deep(.cite) {
  color: var(--color-accent);
  cursor: pointer;
  padding: 0 var(--space-3xs);
  border-radius: var(--radius-xs);
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
}

.answer :deep(.cite:hover),
.answer :deep(.cite.active) {
  background: var(--color-accent-soft);
}

.answer :deep(code) {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  background: var(--color-sunken);
  padding: 1px var(--space-2xs);
  border-radius: var(--radius-xs);
}

.answer :deep(strong) {
  font-weight: 600;
  color: var(--color-ink);
}

.cursor {
  display: inline-block;
  width: var(--space-2xs);
  height: 1em;
  margin-left: var(--space-3xs);
  background: var(--color-accent);
  vertical-align: -2px;
  border-radius: var(--radius-xs);
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}
</style>
