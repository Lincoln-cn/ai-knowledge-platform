// 共享的 Playwright 启动器：项目 node_modules 里没有 playwright，
// 本机在全局 node 目录里有一份（@playwright/test 自带 playwright-core）。
// 这里不写死版本，只找「能用的那一份」，找不到就明确报错告诉人怎么装。
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const CANDIDATES = [
  'playwright',
  'playwright-core',
  '@playwright/test',
];

const GLOBAL_DIRS = [
  'C:/Users/Administrator/scoop/persist/nodejs-lts/bin/node_modules/',
  process.env.APPDATA + '/npm/node_modules/',
];

export async function loadPlaywright() {
  const require = createRequire(import.meta.url);
  for (const dir of CANDIDATES.map((n) => null).filter(() => false)) void dir;
  for (const name of CANDIDATES) {
    try {
      return require(name);
    } catch {
      /* 本地没有，继续找全局 */
    }
  }
  for (const dir of GLOBAL_DIRS) {
    for (const name of CANDIDATES) {
      try {
        const req = createRequire(dir);
        const mod = req(name);
        if (mod?.chromium) return mod;
      } catch {
        /* 这一份也没有 */
      }
    }
  }
  throw new Error(
    '找不到 playwright。装法：在 frontend/ 下 `npm i -D playwright`，或保证全局有 @playwright/test。',
  );
}

export const BASE_URL = process.env.AIP_FE_URL ?? 'http://localhost:5280';
export const API_URL = process.env.AIP_BE_URL ?? 'http://localhost:18088';

/** 打开浏览器；失败时把原因说清楚（浏览器二进制缺失与网络不同因）。 */
export async function launch() {
  const pw = await loadPlaywright();
  try {
    return await pw.chromium.launch();
  } catch (error) {
    throw new Error(
      'chromium 起不来（可能是浏览器二进制没装）：' + (error?.message ?? String(error)) +
        '\n修法：node <playwright>/cli.js install chromium',
    );
  }
}

/** 登录；验证码在后端用 CAPTCHA_ENABLED=false 关掉时不需要填。 */
export async function login(page, username = 'admin', password = 'admin') {
  await page.goto(`${BASE_URL}/#/login`, { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('请输入账号').fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  // 验证码是否必需由后端下发（`captcha.required`），页面默认按「必需」渲染、
  // 拿到响应后才收起输入框 —— 所以这里要等它落地，不能 goto 完立刻判。
  await page
    .waitForFunction(() => !/请输入图中字符/.test(document.body.innerHTML), { timeout: 8000 })
    .catch(() => {});
  const captcha = page.getByPlaceholder('请输入图中字符');
  if (await captcha.isVisible().catch(() => false)) {
    // 开了验证码就只能人工介入：这里明确报错，不静默跳过。
    throw new Error('登录要求验证码：用 CAPTCHA_ENABLED=false 起后端，或人工过验证码后再跑。');
  }
  await page.getByRole('button', { name: /登录/ }).click();
  await page.waitForURL((url) => !url.hash.includes('/login'), { timeout: 15000 });
}

/** 画布里所有可见节点卡片的标题与变量目录（用于断言目录与执行器写入键一致）。 */
export async function readNodeCards(page) {
  return page.evaluate(() => {
    const cards = [];
    for (const el of document.querySelectorAll('.lf-node')) {
      const title = el.querySelector('.node-title, [class*="title"]')?.textContent?.trim() ?? '';
      const fields = Array.from(el.querySelectorAll('[class*="field"]'))
        .map((f) => f.textContent?.trim() ?? '')
        .filter((t) => t.startsWith('{'));
      if (title) cards.push({ title, fields });
    }
    return cards;
  });
}

/** 面板（右侧抽屉）只有点开的那个宽度非 0，DOM 里同时存在多个。 */
export function visibleDrawer(page) {
  return page.locator('.el-drawer').filter({ has: page.locator('.el-drawer__header') }).locator('visible=true').first();
}

/** 打开「添加组件」菜单并点某个节点名。 */
export async function addNodeFromMenu(page, label) {
  await page.getByRole('button', { name: /添加组件/ }).click();
  const item = page.locator('.el-dropdown-menu__item, [class*="catalog"] [class*="item"]').filter({ hasText: label }).first();
  await item.waitFor({ state: 'visible', timeout: 8000 });
  await item.click();
  await page.waitForTimeout(400);
}

/** 在可见面板里填一个「按 label 找」的文本控件；返回是否找到。 */
export async function fillPanelField(page, label, value) {
  const drawer = page.locator('.el-drawer').filter({ hasNot: page.locator(':scope[style*="display: none"]') });
  const item = drawer.locator('.el-form-item').filter({ hasText: label }).first();
  if ((await item.count()) === 0) return false;
  const input = item.locator('input, textarea').first();
  if ((await input.count()) === 0) return false;
  await input.fill(String(value));
  await input.blur();
  await page.waitForTimeout(200);
  return true;
}

export function assert(condition, message) {
  if (!condition) throw new Error(message);
}

export function report(lines) {
  for (const line of lines) console.log(line);
}
