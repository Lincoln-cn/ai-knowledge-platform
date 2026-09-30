/**
 * 轻量 Markdown 渲染（先转义再拼标签）。
 * 支持：标题 # ~ ###### / 段落 / 有序·无序列表 / 表格（GFM 管道）/ **粗体** / `行内代码` / ```代码块``` / 换行 / 可选引注 [N]。
 * 与会话模块 AnswerBody.vue 的引注 DOM 口径一致。
 *
 * ★ 选型理由（0.2.1 批 3）：项目已有 `components/markdown/` 轻量渲染器（先 escape 再拼标签，
 * 从根上防 v-html 注入），本批只需补「标题 / 表格」两种块级语法即可满足「解析结果按结构渲染」，
 * 没有引入 `marked` 的必要 —— 新依赖会扩大供应链面，且与现有引注/转义口径要再对齐一遍。
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
 * 空行分段；单换行渲染为 <br>；``` 围栏为代码块；# 标题；| 管道表格。
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

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]!
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

    // ATX 标题：# ~ ######（必须独占一行，# 后至少一个空格）
    const hMatch = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (hMatch) {
      flushAll()
      const level = hMatch[1]!.length
      blocks.push({ type: 'h', level, html: renderInline(hMatch[2]!, options) })
      continue
    }

    // GFM 管道表格：当前行是 | 开头，且下一行是分隔行 |---|---|
    if (trimmed.startsWith('|') && i + 1 < lines.length) {
      const sep = lines[i + 1]!.trim()
      if (/^\|[\s:|-]+\|$/.test(sep)) {
        flushAll()
        const parseRow = (raw: string): string[] =>
          raw
            .trim()
            .replace(/^\|/, '')
            .replace(/\|$/, '')
            .split('|')
            .map((c) => renderInline(c.trim(), options))
        const header = parseRow(trimmed)
        const rows: string[][] = []
        i += 2 // 跳过表头 + 分隔行
        while (i < lines.length) {
          const bodyLine = lines[i]!.trim()
          if (!bodyLine.startsWith('|')) break
          rows.push(parseRow(bodyLine))
          i += 1
        }
        i -= 1 // for 循环还会 +1
        blocks.push({ type: 'table', header, rows })
        continue
      }
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
