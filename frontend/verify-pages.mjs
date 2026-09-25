import { chromium } from 'playwright'

const BASE = 'http://localhost:5280'
const PAGES = ['/admin', '/models', '/analytics', '/feedback']

const run = async () => {
  const browser = await chromium.launch({ headless: true })
  const ctx = await browser.newContext()
  const page = await ctx.newPage()

  const consoleErrors = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      const text = msg.text()
      // 已知既存噪声：favicon 404
      if (!text.includes('favicon')) consoleErrors.push(text)
    }
  })
  page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message))

  // 登录
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.fill('input[type="text"], input[placeholder*="账号"], input[placeholder*="用户"]', 'admin')
  await page.fill('input[type="password"]', 'admin')
  await page.keyboard.press('Enter')
  await page.waitForTimeout(2000)

  const results = []
  for (const path of PAGES) {
    const before = consoleErrors.length
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1500)

    const newErrors = consoleErrors.slice(before)
    let chartInfo = 'n/a'
    if (path === '/analytics') {
      chartInfo = await page.evaluate(() => {
        const canvases = document.querySelectorAll('canvas')
        if (!canvases.length) return 'no canvas'
        return [...canvases].map((c) => `${c.width}x${c.height}`).join(' | ')
      })
    }
    results.push({ path, newErrors, chartInfo })
    console.log(`[${path}] errors=${newErrors.length} chart=${chartInfo}`)
    if (newErrors.length) newErrors.forEach((e) => console.log('  ERR:', e))
  }

  console.log('\n=== SUMMARY ===')
  for (const r of results) {
    console.log(`${r.path}: ${r.newErrors.length === 0 ? 'OK' : r.newErrors.length + ' new errors'} chart=${r.chartInfo}`)
  }
  const totalNew = results.reduce((s, r) => s + r.newErrors.length, 0)
  console.log(totalNew === 0 ? 'ALL PASS' : `FAIL: ${totalNew} new console errors`)

  await browser.close()
  process.exit(totalNew === 0 ? 0 : 1)
}

run().catch((e) => {
  console.error(e)
  process.exit(1)
})
