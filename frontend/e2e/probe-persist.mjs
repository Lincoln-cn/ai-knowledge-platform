// 诊断八：手写面板的值 —— 到底没落库，还是落库了但面板读不出。
// 结论决定修哪边：修面板的 get/set，还是修 toLogicFlow 的还原。
import { launch, login, BASE_URL, API_URL } from './lib/pw.mjs';

const APP = process.env.AIP_E2E_APP ?? '30fc12bd-5dd8-460f-a8c7-5bf4dacd2721';
const EMPTY = {
  nodes: [{ id: 'n-start', type: 'START_NODE', title: '开始', position: { x: 120, y: 240 }, config: {} }],
  edges: [],
};

const lr = await fetch(`${API_URL}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin' }),
});
const token = JSON.parse(await lr.text()).data.accessToken;
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };

const putDraft = async (graph) => {
  const r = await fetch(`${API_URL}/api/v1/assistants/${APP}/workflow/draft`, {
    method: 'PUT',
    headers: auth,
    body: JSON.stringify({ graph }),
  });
  return r.status;
};
const getDraft = async () => {
  const r = await fetch(`${API_URL}/api/v1/assistants/${APP}/workflow/draft`, { headers: auth });
  return JSON.parse(await r.text()).data?.graph;
};

console.log('重置草稿:', await putDraft(EMPTY));

const browser = await launch();
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
await login(page);
await page.goto(`${BASE_URL}/#/assistants/${APP}/workflow`, { waitUntil: 'domcontentloaded' });
await page.locator('.lf-node').first().waitFor({ timeout: 15000 });
await page.waitForTimeout(1200);

async function openCard(label) {
  const card = page.locator('.lf-node').filter({ hasText: label }).first();
  const box = await card.boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height * 0.8);
  await page.waitForTimeout(900);
}

await page.getByRole('button', { name: /添加组件/ }).click();
await page.waitForTimeout(400);
await page.locator('.workflow-dropdown-item').filter({ hasText: '基础节点' }).first().click();
await page.waitForTimeout(800);
await page.keyboard.press('Escape');
await page.waitForTimeout(400);
// 自动排版会把新节点挪开，后面按文本找卡片即可
const tidy = page.getByRole('button', { name: /自动排版/ }).first();
if (await tidy.count()) {
  await tidy.click().catch(() => {});
  await page.waitForTimeout(800);
}

await openCard('基础节点');
const wrote = await page.evaluate(() => {
  const d = Array.from(document.querySelectorAll('.el-drawer')).filter((x) => x.getBoundingClientRect().width > 0).pop();
  const item = Array.from(d.querySelectorAll('.el-form-item')).find((it) =>
    (it.querySelector('.el-form-item__label')?.textContent ?? '').includes('应用名称'),
  );
  const input = item.querySelector('input');
  input.focus();
  input.value = 'PROBE-NAME-2';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  input.blur();
  return input.value;
});
console.log('写入:', wrote);
await page.waitForTimeout(400);

// 关面板，再保存
const close = page.locator('.el-drawer__close-btn').locator('visible=true').first();
if ((await close.count()) && (await close.isVisible().catch(() => false))) await close.click().catch(() => {});
await page.waitForTimeout(500);
await page.getByRole('button', { name: /^保存$/ }).click();
await page.waitForTimeout(2500);

const saved = await getDraft();
const base = (saved?.nodes ?? []).find((n) => n.type === 'BASE_NODE');
console.log('落库的 BASE config:', JSON.stringify(base?.config ?? null).slice(0, 300));

// 重载页面，开面板看显示
await page.reload({ waitUntil: 'domcontentloaded' });
await page.locator('.lf-node').first().waitFor({ timeout: 15000 });
await page.waitForTimeout(1500);
await openCard('基础节点');
const shown = await page.evaluate(() => {
  const d = Array.from(document.querySelectorAll('.el-drawer')).filter((x) => x.getBoundingClientRect().width > 0).pop();
  if (!d) return 'no drawer';
  const item = Array.from(d.querySelectorAll('.el-form-item')).find((it) =>
    (it.querySelector('.el-form-item__label')?.textContent ?? '').includes('应用名称'),
  );
  return item?.querySelector('input')?.value ?? 'no input';
});
console.log('重载后面板显示:', JSON.stringify(shown));

await browser.close();
