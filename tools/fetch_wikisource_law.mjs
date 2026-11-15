// 从维基文库抓取中华人民共和国法律全文，转成与 corpus/real/ 现有两篇相同的 markdown 格式。
//
// 用法：node tools/fetch_wikisource_law.mjs "中华人民共和国劳动合同法" ...
//
// 为什么要走 `action=raw` 而不是抓页面：页面里有导航、编辑链接、姊妹计划盒子，清洗起来容易误删正文；
// raw 是维基文本，法条结构清晰（`== 第X章 ==` + `{{gap}}'''第X条'''　正文`）。
import { writeFileSync, readdirSync } from 'node:fs'

const OUT = 'D:\\IdeaProjects\\ai-plantform\\corpus\\real'
const UA = { 'user-agent': 'Mozilla/5.0 (compatible; ai-plantform corpus fetcher)' }

async function raw(title) {
  const u = `https://zh.wikisource.org/w/index.php?title=${encodeURIComponent(title)}&action=raw`
  const r = await fetch(u, { headers: UA })
  if (!r.ok) throw new Error(`${title} → HTTP ${r.status}`)
  return r.text()
}

/** 沿革页只有目录，正文在 `标题 (年份年)` 子页里；取年份最大的那个（最新版）。 */
function pickLatest(wikitext, base) {
  const esc = base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(`\\[\\[${esc} \\((\\d{4})年\\)\\]\\]`, 'g')
  let best = null
  for (const m of wikitext.matchAll(re)) {
    const y = Number(m[1])
    if (!best || y > best.y) best = { y, title: `${base} (${y}年)` }
  }
  return best ? best.title : null
}

/** 把 wikitext 变成纯 markdown 正文。只保留章/节/条，其余模板全部丢弃。 */
function toMarkdown(wikitext) {
  const lines = wikitext.split('\n')
  const out = []
  let inHeader = false
  for (const rawLine of lines) {
    let line = rawLine.trim()

    // {{header ... }} 是页面顶部的元信息模板，跨多行
    if (line.startsWith('{{header')) { inHeader = true; continue }
    if (inHeader) { if (line === '}}' || line.endsWith('}}')) inHeader = false; continue }

    if (!line) continue
    if (line.startsWith('[[Category:') || line.startsWith('[[分类:')) continue
    if (/^\{\{(nop|gap|!|·|center|right)\}\}$/.test(line)) continue

    // {{gap}} 是维基文库用来表示"段首空两字"的模板，去掉它，段首缩进对检索没有意义
    line = line.replace(/^\{\{gap\}\}+/, '').trim()
    if (!line) continue

    // 其余单行模板（如 {{...}}）一律丢弃
    if (/^\{\{[^{}]*\}\}$/.test(line)) continue

    // wiki 链接：[[A|B]] → B，[[A]] → A
    line = line.replace(/\[\[[^\]|]*\|([^\]]*)\]\]/g, '$1').replace(/\[\[([^\]]*)\]\]/g, '$1')
    // 外链：[文字](url) 或 http 裸链
    line = line.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/https?:\/\/\S+/g, '').trim()
    if (!line) continue

    // 章节
    const h = line.match(/^==\s*(.+?)\s*==$/)
    if (h) { out.push('', `## ${h[1]}`); continue }
    const h3 = line.match(/^===\s*(.+?)\s*===$/)
    if (h3) { out.push('', `### ${h3[1]}`); continue }

    // 法条：'''第X条'''　正文
    line = line.replace(/'''(.+?)'''/g, '**$1**')
    // 全角空格在 markdown 里会原样保留，统一成一个普通空格更稳
    line = line.replace(/\u3000+/g, ' ')

    out.push('', line)
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

/** 从 raw 的 header 模板里抽制定机关与修正说明（对应已入库两篇的前两行）。 */
function meta(wikitext) {
  const lawmaker = wikitext.match(/\|\s*lawmaker\s*=\s*([^\n|}]+)/)?.[1]?.trim() ?? ''
  const notes = wikitext.match(/\|\s*notes\s*=\s*([^\n]+)/)?.[1]?.trim() ?? ''
  return { lawmaker, notes }
}

function nextIndex() {
  const used = readdirSync(OUT)
    .map((f) => Number(f.match(/^(\d{2})-/)?.[1]))
    .filter((n) => Number.isFinite(n))
  return String(Math.max(0, ...used) + 1).padStart(2, '0')
}

const titles = process.argv.slice(2)
if (titles.length === 0) {
  console.error('用法：node tools/fetch_wikisource_law.mjs "中华人民共和国劳动合同法" ...')
  process.exit(1)
}

let idx = nextIndex()
for (const t of titles) {
  try {
    let text = await raw(t)
    let title = t
    // 沿革页：没有条文内容，只有一个"最新版"链接
    if (!/'\'\'第[一二三四五六七八九十百千]+条/.test(text)) {
      const latest = pickLatest(text, t)
      if (!latest) throw new Error('没有条文，也没找到最新版子页')
      text = await raw(latest)
      title = latest
      console.log(`  ${t} → 走最新版 ${latest}`)
    }
    const { lawmaker, notes } = meta(text)
    const body = toMarkdown(text)
    const articles = (body.match(/^\*\*第[一二三四五六七八九十百千]+条\*\*/gm) ?? []).length
    const md = [
      `# ${title.replace(/\s*\(\d{4}年\)$/, '')}`,
      '',
      notes ? `${notes}` : '',
      '',
      lawmaker ? `制定机关：${lawmaker}` : '',
      '',
      body,
      '',
    ].join('\n')
    const file = `${idx}-${title.replace(/[\\/:*?"<>|]/g, '_')}.md`
    writeFileSync(`${OUT}\\${file}`, md, { encoding: 'utf8' })
    console.log(`${file} · ${articles} 条 · ${md.length} 字符`)
    idx = String(Number(idx) + 1).padStart(2, '0')
  } catch (e) {
    console.error(`${t} 失败：${e.message}`)
  }
}
