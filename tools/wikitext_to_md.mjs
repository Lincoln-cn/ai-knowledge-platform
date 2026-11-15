// 把 .build/wiki-raw/*.txt 里的维基文本转成 corpus/real/NN-标题.md。
// 抓取单独做（PowerShell 的 Invoke-WebRequest 能通，Node 的 fetch 直连超时），清洗在这里做。
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'

const RAW = 'D:\\IdeaProjects\\ai-plantform\\.build\\wiki-raw'
const OUT = 'D:\\IdeaProjects\\ai-plantform\\corpus\\real'

function toMarkdown(wikitext) {
  const lines = wikitext.split('\n')
  const out = []
  let inHeader = false
  for (const rawLine of lines) {
    let line = rawLine.trim()

    if (line.startsWith('{{header')) { inHeader = true; continue }
    if (inHeader) { if (line === '}}' || line.endsWith('}}')) inHeader = false; continue }

    if (!line) continue
    if (line.startsWith('[[Category:') || line.startsWith('[[分类:')) continue
    if (/^\{\{(nop|gap|!|·|center|right)\}\}$/.test(line)) continue

    line = line.replace(/^\{\{gap\}\}+/, '').trim()
    if (!line) continue
    if (/^\{\{[^{}]*\}\}$/.test(line)) continue

    line = line.replace(/\[\[[^\]|]*\|([^\]]*)\]\]/g, '$1').replace(/\[\[([^\]]*)\]\]/g, '$1')
    line = line.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/https?:\/\/\S+/g, '').trim()
    if (!line) continue

    const h = line.match(/^==\s*(.+?)\s*==$/)
    if (h) { out.push('', `## ${h[1]}`); continue }
    const h3 = line.match(/^===\s*(.+?)\s*===$/)
    if (h3) { out.push('', `### ${h3[1]}`); continue }

    line = line.replace(/'''(.+?)'''/g, '**$1**')
    line = line.replace(/\u3000+/g, ' ')
    out.push('', line)
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

function meta(wikitext) {
  const lawmaker = wikitext.match(/\|\s*lawmaker\s*=\s*([^\n|}]+)/)?.[1]?.trim() ?? ''
  const notes = wikitext.match(/\|\s*notes\s*=\s*([^\n]+)/)?.[1]?.trim() ?? ''
  return { lawmaker, notes }
}

const files = readdirSync(RAW).filter((f) => f.endsWith('.txt')).sort()
let idx = 3
for (const f of files) {
  const text = readFileSync(`${RAW}\\${f}`, 'utf8')
  const title = f.replace(/\.txt$/, '')
  const base = title.replace(/\s*\(\d{4}年\)$/, '')
  const { lawmaker, notes } = meta(text)
  const body = toMarkdown(text)
  const articles = (body.match(/^\*\*第[一二三四五六七八九十百千]+条\*\*/gm) ?? []).length
  const cleanNotes = notes
    .replace(/\[\[[^\]|]*\|([^\]]*)\]\]/g, '$1')
    .replace(/\[\[([^\]]*)\]\]/g, '$1')
    .replace(/'''/g, '')
  const md = [`# ${base}`, '', cleanNotes, '', lawmaker ? `制定机关：${lawmaker}` : '', '', body, ''].join('\n')
  const name = `${String(idx).padStart(2, '0')}-${base}.md`
  writeFileSync(`${OUT}\\${name}`, md, { encoding: 'utf8' })
  console.log(`${name} · ${articles} 条 · ${md.length} 字符`)
  idx += 1
}
