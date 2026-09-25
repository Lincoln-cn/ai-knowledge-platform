/**
 * 轻量 Markdown 渲染（先转义再拼标签）。
 * 支持：段落 / 有序·无序列表 / **粗体** / `行内代码` / ```代码块``` / 换行 / 可选引注 [N]。
 * 与会话模块 AnswerBody.vue 的引注 DOM 口径一致。
 */
import type { MdBlock, MdRenderOptions, MdSection } from './types'

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 行内语法：粗体 / 行内代码 / 引注。必须先 escape。 */
export function renderInline(raw: string, options: MdRenderOptions = {}): string {
  let html = escapeHtml(raw)
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  // 换行（在行内语法之后，避免打断标签）
  html = html.replace(/\n/g, '<br />')
  if (options.citations) {
    // 与 AnswerBody.vue 相同：span.cite[data-ref]
    html = html.replace(
      /\[(\d+)\]/g,
      '<span class="cite" data-ref="$1" role="button" tabindex="0">[$1]</span>'
    )
  }
  return html
}

/**
 * 把 Markdown 文本解析为块列表。
 * 空行分段；单换行渲染为 <br>；``` 围栏为代码块。
 */
export function parseMarkdown(text: string, options: MdRenderOptions = {}): MdBlock[] {
  const src = text || ''
  const blocks: MdBlock[] = []
  const lines = src.split(/\r?\n/)
  let para: string[] = []
  let ul: string[] = []
  let ol: string[] = []
  let code: string[] | null = null
  let codeLang = ''

  const flushPara = () => {
    if (para.length) {
      blocks.push({ type: 'p', html: renderInline(para.join('\n'), options) })
      para = []
    }
  }
  const flushUl = () => {
    if (ul.length) {
      blocks.push({ type: 'ul', items: ul.map((i) => renderInline(i, options)) })
      ul = []
    }
  }
  const flushOl = () => {
    if (ol.length) {
      blocks.push({ type: 'ol', items: ol.map((i) => renderInline(i, options)) })
      ol = []
    }
  }
  const flushAll = () => {
    flushPara()
    flushUl()
    flushOl()
  }

  for (const line of lines) {
    // 代码块围栏
    const fence = /^```(\S*)\s*$/.exec(line)
    if (fence) {
      if (code) {
        blocks.push({ type: 'code', html: escapeHtml(code.join('\n')), lang: codeLang })
        code = null
        codeLang = ''
      } else {
        flushAll()
        code = []
        codeLang = fence[1] || ''
      }
      continue
    }
    if (code) {
      code.push(line)
      continue
    }

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
    // 保留段内换行
    para.push(line)
  }
  if (code) {
    blocks.push({ type: 'code', html: escapeHtml(code.join('\n')), lang: codeLang })
  }
  flushAll()
  return blocks
}

/** 切分特殊段：<echarts_rander> / <form_rander> / <html_rander> */
export function splitSpecialSections(source: string): MdSection[] {
  return scanSections(source)
}

function scanSections(source: string): MdSection[] {
  const re = /<(echarts_rander|form_rander|html_rander)>([\d\S]*?)<\/\1>/g
  const out: MdSection[] = []
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(source))) {
    if (m.index > last) {
      out.push({ type: 'md', content: source.slice(last, m.index) })
    }
    const kind = m[1]!
    const content = m[2] || ''
    if (kind === 'echarts_rander') out.push({ type: 'echarts', content })
    else if (kind === 'form_rander') out.push({ type: 'form', content })
    else out.push({ type: 'html', content })
    last = m.index + m[0].length
  }
  if (last < source.length) {
    out.push({ type: 'md', content: source.slice(last) })
  }
  return out.length ? out : [{ type: 'md', content: source }]
}

/** 从点击事件里取出引注序号（与 AnswerBody 相同的选择器） */
export function citeIndexFromEvent(e: Event): number | null {
  const target = e.target as HTMLElement | null
  const cite = target?.closest('.cite') as HTMLElement | null
  if (!cite) return null
  const n = Number(cite.dataset.ref)
  return Number.isFinite(n) ? n : null
}
