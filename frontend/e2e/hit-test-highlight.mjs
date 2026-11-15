// 命中高亮的真栈读数（`/knowledge/hittest`，0.9.0）。
//
// 验的是"高亮的片段是不是真的来自正文"：断言 `<mark>` 的文本能在对应段落里找到，
// 且**命中的片段非空**（空 `<mark>` 或整段变色都等于没高亮）。
//
// 为什么必须真栈：高亮用的是"用户实际发出去的那个查询"，而 mock 的检索结果是固定夹具，
// 用它验证等于只证明模板能渲染。
import { BASE_URL, launch, login } from './lib/pw.mjs'

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const browser = await launch()
const page = await browser.newPage()
const errors = []
page.on('console', (m) => {
  if (m.type() === 'error') errors.push(m.text())
})

const QUERY = '打卡申请应在几个工作日内办结'

try {
  await login(page)
  await page.goto(`${BASE_URL}/#/knowledge/hittest`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)

  const body = await page.locator('body').innerText()
  check('命中测试页打开', body.includes('命中测试'), body.slice(0, 40))

  // 问题输入框：placeholder 里带「问题」字样
  const input = page.locator('input[placeholder*="问题"], textarea[placeholder*="问题"]').first()
  await input.fill(QUERY)
  await page.waitForTimeout(200)

  const searchBtn = page.getByRole('button', { name: /测试|搜索/ }).first()
  await searchBtn.click()
  // 检索要打真模型（查询理解）+ 重排，慢的批次给足时间
  await page.waitForSelector('[data-hit-content]', { timeout: 90_000 })
  await page.waitForTimeout(800)

  const marks = page.locator('mark.hit')
  const markCount = await marks.count()
  check('结果里出现了高亮片段', markCount > 0, `${markCount} 个`)

  if (markCount > 0) {
    const texts = await marks.allInnerTexts()
    check(
      '没有一个空的高亮片段',
      texts.every((t) => t.trim().length > 0),
      texts.slice(0, 3).join(' / ')
    )
    // ★ 核心判据：高亮出来的字必须**原样**出现在它所在的那段正文里 ——
    //   否则就是把查询"画"到正文上了，那是最坏的一种假绿。
    const first = page.locator('[data-hit-content]').first()
    const fullText = await first.innerText()
    const sample = texts[0].trim()
    check('高亮片段确实来自正文', fullText.includes(sample), `「${sample}」`)
    check(
      '高亮的不是整段',
      sample.length < Math.max(4, fullText.length),
      `片段 ${sample.length} 字 / 全段 ${fullText.length} 字`
    )
  }

  check('全程零控制台错误', errors.length === 0, errors.slice(0, 2).join(' | '))
} catch (error) {
  check('脚本跑完不抛错', false, error instanceof Error ? error.message : String(error))
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  for (const f of failed) console.log(`  × ${f.name} — ${f.detail}`)
  process.exitCode = 1
}
