// 清掉 0 条的空文件（沿革页被当成正文转过来了），并按条文数从多到少重编号 01..N。
import { readFileSync, readdirSync, unlinkSync, renameSync } from 'node:fs'

const OUT = 'D:\\IdeaProjects\\ai-plantform\\corpus\\real'

const kept = []
for (const f of readdirSync(OUT).filter((x) => x.endsWith('.md'))) {
  const path = `${OUT}\\${f}`
  const text = readFileSync(path, 'utf8')
  const articles = (text.match(/^\*\*第[一二三四五六七八九十百千]+条\*\*/gm) ?? []).length
  if (articles === 0) {
    unlinkSync(path)
    console.log(`删除空文件 ${f}`)
  } else {
    kept.push({ f, articles, text })
  }
}

// 先全部改成临时名，避免 01 → 02 这种链式冲突
kept.sort((a, b) => b.articles - a.articles)
kept.forEach((k, i) => {
  const tmp = `${OUT}\\_tmp_${i}.md`
  renameSync(`${OUT}\\${k.f}`, tmp)
  k.tmp = tmp
})
kept.forEach((k, i) => {
  const title = k.f.replace(/^\d{2}-/, '')
  const final = `${String(i + 1).padStart(2, '0')}-${title}`
  renameSync(k.tmp, `${OUT}\\${final}`)
  console.log(`${final} · ${k.articles} 条`)
})
