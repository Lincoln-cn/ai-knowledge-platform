<template>
  <!--
    ★ 光标不在这里 —— 它由 `html` 计算属性注入到最后一行的内部（见 attachCursor）。
      挂在这个 div 外面的话，因为 div 是块级，光标会永远另起一行。
  -->
  <div class="answer" @click="onClick" v-html="html" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

/**
 * 回答正文渲染 —— 用成熟的 **markdown-it**，不再自己写解析器。
 *
 * <h3>★ 为什么换掉自研解析器（用户原话：「可以用成熟的 markdown 渲染组件，不要自己编写一个解析器嘛？」）</h3>
 *
 * 之前是一个手写的「行首优先级」小解析器，能覆盖段落/列表/粗体/行内代码，
 * 但漏了标题、代码块、分隔线、引用块、斜体、链接、表格 —— 实测库里真的存在
 * `### 劳动合同应当具备的条款` 这类输出，于是井号原样露在界面上。
 * 手写解析器的本质问题是：**它是 CommonMark 的一个不断长大的子集**，
 * 每漏一条就是一次"某个格式不渲染"的报障，而且边界（嵌套、转义、围栏）永远补不完。
 *
 * <h3>★★ 安全：靠 `html: false`，而不是靠"先转义再拼"</h3>
 *
 * 模型输出是**不可信输入**。这里刻意关掉 markdown-it 的 HTML 直通：
 * 于是 `<script>`、`<img onerror=…>` 这类原始标签会被**转义成文本**，
 * 渲染结果只包含 markdown-it 自己生成的标签 ⇒ **不需要再引 DOMPurify**，
 * 也不会出现"忘了 sanitize"这一类漏洞。
 *
 * ★ 代价：回答里内嵌 HTML 会显示成源码而不是生效 —— 对问答正文来说这是想要的行为。
 *
 * <h3>引用角标 `[N]`</h3>
 *
 * 它不是 markdown 语法，而是本平台的引用锚点。做法：**渲染前**把 `[N]` 换成不含
 * HTML 特殊字符的占位符，**渲染后**再把占位符换回可点的 `<span class="cite">`。
 * ★ 为什么不在渲染后的 HTML 上直接正则替换 `[N]`：那会连 `<a title="[1]">`
 * 这类属性内容一起改坏；占位符方案里 HTML 已经生成完毕，替换只命中正文。
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

const md = new MarkdownIt({
  // ★ 安全的总开关：不直通原始 HTML
  html: false,
  // 单个换行也当换行（问答正文里模型常用它分行，严格的 CommonMark 会并成一段）
  breaks: true,
  // 自动识别裸链接（`https://…` 不必写成 [文字](url)）
  linkify: true,
  typographer: false
})

// 外链统一新窗口 + noreferrer（与安全策略一致）
const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]!
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/**
 * `[3]` → 占位符。
 *
 * ★ 不能用 `\u0000` 这类控制字符：markdown-it 会把它规范化成 `�`（实测），
 *   于是占位符再也换不回来，正文里直接露出乱码。
 *   ★ 用 "@@CITE数字@@" 这种**不会出现在正常中文回答里**的可见串即可。
 */
const CITE_PLACEHOLDER = '@@CITE'

/** 流式光标。★ 它必须是一个**行内**元素，且要插在最后一段正文里面（见 {@link attachCursor}）。 */
const CURSOR_HTML = '<span class="cursor" aria-hidden="true"></span>'

/**
 * 把光标插进**最后一块正文的内部**。
 *
 * <h3>★★ 为什么不能像以前那样挂在容器外面（用户报障：「生成时蓝色光标没有在生成字符的后面，而是永远在最后面一行」）</h3>
 *
 * 之前模板是：
 *
 * ```html
 * <div class="answer" v-html="html" />
 * <span v-if="streaming" class="cursor" />
 * ```
 *
 * `<div>` 是**块级**元素 ⇒ 那个 span 永远另起一行。于是光标看起来"掉到下一行"，
 * 而不是"跟在刚生成的那个字后面" —— 打字机效果的关键恰恰是这个"贴住"。
 *
 * <h3>怎么插</h3>
 *
 * markdown-it 的输出以块级标签收尾（`</p>` / `</li>` / `</h1>` …）。把光标插到
 * **最后一个闭标签之前**，它就成为那一段的行内末尾 ⇒ 紧贴最后一个字。
 *
 * ★ 例外：最后一块是 `</pre>`（代码块）时**插在外面**。代码块里塞一个闪烁块既不像光标、
 *   也会被 `overflow` 裁掉；而"代码块下方另起一行"本来就是代码结束该有的样子。
 *
 * @param rendered 已渲染的 HTML
 * @returns 带光标的 HTML
 */
function attachCursor(rendered: string): string {
  // 代码块收尾 ⇒ 光标放外面（另起一行是对的）
  if (/<\/pre>\s*$/.test(rendered)) {
    return rendered + CURSOR_HTML
  }
  // 否则插进最后一个块级闭标签之前（表格单元格也算：流式表格的光标该在最后一格末尾）
  const match = rendered.match(/<\/(p|li|h[1-6]|td|th|blockquote|dd|dt)>(\s*)$/)
  if (match && match.index !== undefined) {
    return rendered.slice(0, match.index) + CURSOR_HTML + rendered.slice(match.index)
  }
  // 兜底：没有任何块级标签（极少见，比如只有一行行内内容）⇒ 直接接在末尾
  return rendered + CURSOR_HTML
}

const html = computed(() => {
  const raw = (props.text || '').replace(
    /\[(\d+)\]/g,
    (_m, n: string) => `${CITE_PLACEHOLDER}${n}@@`
  )
  let rendered = md.render(raw)
  // 占位符 → 可点角标（此时 HTML 已生成完毕，替换不会碰到属性）
  rendered = rendered.replace(/@@CITE(\d+)@@/g, (_m, n: string) => {
    const active = Number(n) === props.activeIndex ? ' active' : ''
    return `<span class="cite${active}" data-ref="${n}" role="button" tabindex="0">[${n}]</span>`
  })
  return props.streaming ? attachCursor(rendered) : rendered
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

/* ── markdown-it 生成的标签：用 :deep 才作用得到 v-html 的内容 ── */
.answer :deep(p) {
  margin: 0 0 var(--space-xs);
}

.answer :deep(p:last-child) {
  margin-bottom: 0;
}

.answer :deep(h1),
.answer :deep(h2),
.answer :deep(h3),
.answer :deep(h4),
.answer :deep(h5),
.answer :deep(h6) {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-ink);
  margin: var(--space-sm) 0 var(--space-2xs);
  letter-spacing: 0;
}

.answer :deep(ul),
.answer :deep(ol) {
  margin: 0 0 var(--space-xs);
  padding-left: var(--space-lg);
}

.answer :deep(li) {
  margin: var(--space-3xs) 0;
}

.answer :deep(pre) {
  margin: var(--space-xs) 0;
  padding: var(--space-sm) var(--space-md);
  background: var(--color-sunken);
  border-radius: var(--radius-sm);
  overflow-x: auto;
  font-size: var(--text-2xs);
  line-height: 1.5;
}

.answer :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: inherit;
}

.answer :deep(hr) {
  border: none;
  border-top: 1px solid var(--color-rule);
  margin: var(--space-sm) 0;
}

.answer :deep(blockquote) {
  margin: var(--space-xs) 0;
  padding: var(--space-2xs) var(--space-sm);
  border-left: 2px solid var(--color-rule);
  color: var(--color-muted);
}

.answer :deep(table) {
  border-collapse: collapse;
  margin: var(--space-xs) 0;
  font-size: var(--text-2xs);
}

.answer :deep(th),
.answer :deep(td) {
  border: 1px solid var(--color-rule);
  padding: var(--space-2xs) var(--space-xs);
  text-align: left;
}

.answer :deep(th) {
  background: var(--color-sunken);
  font-weight: 600;
}

.answer :deep(a) {
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
