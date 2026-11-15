// 检索引擎配置页的真栈读数（`/settings/retrieval`）。
//
// 验的是**页面到库的往返**，不是渲染细节：改窗口 → 保存 → 提示 → 刷新回读。
// 为什么不用 mock 跑：这条链的判据是"改完之后后端真的按新值算"，mock 只能证明界面自洽。
import { BASE_URL, assert, launch, login, report } from './lib/pw.mjs'

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const browser = await launch()
const page = await browser.newPage()
const errors = []
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

try {
  await login(page)
  await page.goto(`${BASE_URL}/#/settings/retrieval`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)

  const body = await page.locator('body').innerText()
  check('页面标题是「检索引擎」', body.includes('检索引擎'))
  check('三个分组都在', body.includes('查询理解') && body.includes('重排') && body.includes('listwise'))

  // 找重排窗口那个数字输入框：它在「跨编码器重排」分组里，label 是「窗口」
  const windowInput = page.locator('.panel').nth(1).locator('input[type=number]').first()
  await windowInput.fill('20')
  await windowInput.blur()
  await page.waitForTimeout(300)

  const saveBtn = page.getByRole('button', { name: '保存' }).first()
  await saveBtn.click()
  await page.waitForTimeout(1500)

  const afterSave = await page.locator('body').innerText()
  check('保存后有成功提示', /已保存/.test(afterSave), afterSave.match(/已保存[^\n]{0,40}/)?.[0] ?? '')

  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  const reloaded = await page.locator('.panel').nth(1).locator('input[type=number]').first()
  const value = await reloaded.inputValue()
  check('刷新后回读是 20（落库生效）', value === '20', `读回 ${value}`)

  // 恢复默认，别给后面的用例留脏数据
  await reloaded.fill('10')
  await reloaded.blur()
  await page.getByRole('button', { name: '保存' }).first().click()
  await page.waitForTimeout(1200)
  check('恢复 10 成功', /已保存/.test(await page.locator('body').innerText()))

  check('零控制台错误', errors.length === 0, errors.join(' | ').slice(0, 160))
} finally {
  await browser.close()
  report(results)
  process.exit(results.every((r) => r.ok) ? 0 : 1)
}