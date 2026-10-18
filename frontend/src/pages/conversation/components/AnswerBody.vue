<template>
  <div class="answer" @click="onClick">
    <template v-for="(block, i) in blocks" :key="i">
      <h3 v-if="block.type === 'h'" class="a-h" v-html="block.html" />
      <pre v-else-if="block.type === 'code'" class="a-code"><code>{{ block.text }}</code></pre>
      <hr v-else-if="block.type === 'hr'" class="a-hr" />
      <blockquote v-else-if="block.type === 'quote'" class="a-quote" v-html="block.html" />
      <p v-else-if="block.type === 'p'" class="a-p" v-html="block.html" />
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
 *
 * 旧项目用 md-editor-v3；本工程**依赖白名单是封闭的**（`frontend/README.md` 只允许
 * element-plus / pinia / vue-router / axios / dayjs / lodash-es / mitt / echarts / logicflow 那几项），
 * 所以这里自己做「问答正文需要的那一子集」。
 *
 * <h3>★ 2026-10-18 补齐（用户：「markdown 格式似乎没有正确渲染」）</h3>
 *
 * 之前只支持「段落 / 列表 / 粗体 / 行内代码 / 引用角标」，而模型的真实输出里还有
 * `### 标题`、代码块、`---` 分隔线、`> 引用`、`*斜体*`、`[文字](链接)` ——
 * 那些行会被当成**普通段落原样显示**（井号、星号、反引号都露在界面上）。
 *
 * 现在按 CommonMark 的**行首优先级**解析：代码块 > 标题 > 分隔线 > 引用 > 列表 > 段落。
 *
 * <h3>★ 安全</h3>
 *
 * 一律**先转义 HTML 再拼标签**（与原实现同一条口径）：模型输出是不可信输入，
 * `v-html` 直接吃原文等于给它一个 XSS 入口。链接另加 scheme 白名单（只放行 http/https/mailto），
 * 于是 `javascript:` 这类链接不会变成可点的锚点。
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

/** 行内元素：粗体 / 斜体 / 删除线 / 行内代码 / 链接 / 引用角标[1]。 */
function renderInline(raw: string): string {
  let html = escapeHtml(raw)

  // ★ 行内代码先占位：它的内容不该再被粗体/斜体规则改写（`**a**` 里的星号是字面量）
  const codes: string[] = []
  html = html.replace(/`([^`]+)`/g, (_m, code: string) => {
    codes.push(code)
    return `\u0000CODE${codes.length - 1}\u0000`
  })

  // 链接：只放行安全的 scheme
  html = html.replace(
    /\[([^\]]+)\]\((https?:\/\/[^\s)]+|mailto:[^\s)]+)\)/g,
    (_m, label: string, href: string) =>
      `<a class="a-link" href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
  )

  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>')
  html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>')
  // ★ 斜体：`*x*` 与 `_x_`，且两侧不能紧贴字母（否则 `snake_case`、`2*3*4` 会被误伤）
  html = html.replace(/(^|[\s（(【[])\*([^*\n]+)\*(?=[\s）)】\]。，,.;；:：!！?？]|$)/g, '$1<em>$2</em>')
  html = html.replace(/(^|[\s（(【[])_([^_\n]+)_(?=[\s）)】\]。，,.;；:：!！?？]|$)/g, '$1<em>$2</em>')

  html = html.replace(
    /\[(\d+)\]/g,
    (_m, n: string) =>
      `<span class="cite${Number(n) === props.activeIndex ? ' active' : ''}" data-ref="${n}" role="button" tabindex="0">[${n}]</span>`
  )

  // 还原行内代码
  html = html.replace(/\u0000CODE(\d+)\u0000/g, (_m, i: string) => {
    const code = codes[Number(i)] ?? ''
    return `<code>${code}</code>`
  })
  return html
}

type Block =
  | { type: 'p'; html: string }
  | { type: 'h'; html: string }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'code'; text: string }
  | { type: 'quote'; html: string }
  | { type: 'hr' }

const blocks = computed<Block[]>(() => {
  const lines = (props.text || '').split(/\r?\n/)
  const out: Block[] = []
  let para: string[] = []
  let ul: string[] = []
  let ol: string[] = []
  let quote: string[] = []
  let fence: { text: string[] } | null = null

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
  const flushQuote = () => {
    if (quote.length) {
      out.push({ type: 'quote', html: renderInline(quote.join(' ')) })
      quote = []
    }
  }
  const flushAll = () => {
    flushPara()
    flushUl()
    flushOl()
    flushQuote()
  }

  for (const line of lines) {
    const trimmed = line.trim()

    // ★ 代码块（``` 或 ~~~）：内容原样保留
    if (fence) {
      if (/^(```|~~~)/.test(trimmed)) {
        out.push({ type: 'code', text: fence.text.join('\n') })
        fence = null
      } else {
        fence.text.push(line)
      }
      continue
    }
    if (/^(```|~~~)/.test(trimmed)) {
      flushAll()
      fence = { text: [] }
      continue
    }

    if (!trimmed) {
      flushAll()
      continue
    }

    // 标题：# ~ ######（统一渲染成 h3，字号由 CSS 控制 —— 正文里不该出现 h1/h2 抢层级）
    const hMatch = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (hMatch) {
      flushAll()
      out.push({ type: 'h', html: renderInline(hMatch[2]!) })
      continue
    }

    // 分隔线
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushAll()
      out.push({ type: 'hr' })
      continue
    }

    // 引用块
    const qMatch = /^>\s?(.*)$/.exec(trimmed)
    if (qMatch) {
      flushPara()
      flushUl()
      flushOl()
      quote.push(qMatch[1]!)
      continue
    }

    const ulMatch = /^[-*+]\s+(.*)$/.exec(trimmed)
    const olMatch = /^\d+[.)]\s+(.*)$/.exec(trimmed)
    if (ulMatch) {
      flushPara()
      flushOl()
      flushQuote()
      ul.push(ulMatch[1]!)
      continue
    }
    if (olMatch) {
      flushPara()
      flushUl()
      flushQuote()
      ol.push(olMatch[1]!)
      continue
    }
    flushUl()
    flushOl()
    flushQuote()
    para.push(trimmed)
  }

  // 未闭合的代码块（流式中途）也要显示出来，否则那段文字凭空消失
  if (fence) {
    out.push({ type: 'code', text: fence.text.join('\n') })
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

.a-h {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
  margin: var(--space-sm) 0 var(--space-2xs);
  letter-spacing: 0;
}

.a-h:first-child {
  margin-top: 0;
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

.a-code {
  margin: var(--space-xs) 0;
  padding: var(--space-sm) var(--space-md);
  background: var(--color-sunken);
  border-radius: var(--radius-sm);
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
  line-height: 1.5;
  white-space: pre;
}

.a-code :deep(code) {
  background: transparent;
  padding: 0;
  font-size: inherit;
}

.a-hr {
  border: none;
  border-top: 1px solid var(--color-rule);
  margin: var(--space-sm) 0;
}

.a-quote {
  margin: var(--space-xs) 0;
  padding: var(--space-2xs) var(--space-sm);
  border-left: 2px solid var(--color-rule);
  color: var(--color-muted);
}

.answer :deep(.a-link) {
  color: var(--color-accent);
  text-decoration: none;
  border-bottom: 1px solid var(--color-accent-soft);
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

.answer :deep(em) {
  font-style: italic;
}

.answer :deep(del) {
  text-decoration: line-through;
  color: var(--color-muted);
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
