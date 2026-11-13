// 逐节点卡片测试：每个可执行节点都要能在画布上加出来、点开面板、改得动、**保存后还在**。
//
// 为什么必须是「保存后还在」而不是「重开面板还在」：面板可能把值放在自己的局部状态里，
// 重开面板读到的是另一份对象 —— 那种情况在内存里看着"留住了"，存盘却是空的。
// 这里统一走「改 → 保存 → 重载页面 → 重开面板」，只有存下来的东西才算数。
//
// 关于「面板 label 与目录 label 是否一致」：只有 REPLY / DOCUMENT_EXTRACT 是 SchemaForm
// 按目录渲染，其余手写（`#337` 第二步 B2 的"不推广"结论）。所以只对那两个严格比对，
// 其余**报告差距**不判失败 —— 混进通过/失败会把"功能可用性"与"面板与目录分叉"搅在一起。
//
// 前置：真栈（PG 15433 + 后端 18088 带 CAPTCHA_ENABLED=false + 前端 5280），
//      以及一个专用应用（`node e2e/make-app.mjs` 建，id 从环境变量给）。
// 跑法：node e2e/workflow-node-panels.mjs
import fs from 'node:fs';
import { launch, login, BASE_URL, API_URL } from './lib/pw.mjs';

const APP = process.env.AIP_E2E_APP ?? '30fc12bd-5dd8-460f-a8c7-5bf4dacd2721';
const CATALOG = JSON.parse(
  fs.readFileSync('../backend/src/main/resources/workflow/node-types.json', 'utf8'),
);
const EXECUTABLE = CATALOG.filter((n) => n.executable);
const SCHEMA_DRIVEN = new Set(['REPLY_NODE', 'DOCUMENT_EXTRACT_NODE']);

const EMPTY_GRAPH = {
  nodes: [{ id: 'n-start', type: 'START_NODE', title: '开始', position: { x: 120, y: 240 }, config: {} }],
  edges: [],
};

const results = [];
const notes = [];
function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

const browser = await launch();
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
const consoleErrors = [];
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200));
});

await login(page);
// API 用自己登录换来的令牌，不去猜前端把令牌存在哪个键里 —— 猜错会静默取到 refreshToken，
// 表现成「重置草稿 401」，排查时还以为是后端权限问题。
const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: process.env.AIP_USER ?? 'admin',
    password: process.env.AIP_PASS ?? 'admin',
  }),
});
const token = JSON.parse(await loginRes.text())?.data?.accessToken;
if (!token) throw new Error('取令牌失败：后端登录没返回 accessToken（验证码开了？用 CAPTCHA_ENABLED=false 起后端）');
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };

async function resetDraft() {
  const r = await fetch(`${API_URL}/api/v1/assistants/${APP}/workflow/draft`, {
    method: 'PUT',
    headers: auth,
    body: JSON.stringify({ graph: EMPTY_GRAPH }),
  });
  if (r.status !== 200) throw new Error('重置草稿失败：' + r.status + ' ' + (await r.text()).slice(0, 160));
}

async function openCanvas({ reload = false } = {}) {
  // ★ goto 到「同一个带 hash 的 URL」不会真的重新加载（浏览器只做 hash 导航）；
  // 要验证「存下来的东西」必须 reload，否则读到的是内存里那份，测了等于没测。
  if (reload) await page.reload({ waitUntil: 'domcontentloaded' });
  else await page.goto(`${BASE_URL}/#/assistants/${APP}/workflow`, { waitUntil: 'domcontentloaded' });
  await page.locator('.lf-node').first().waitFor({ timeout: 15000 });
  await page.waitForTimeout(1200);
}

const visibleDrawerCount = () =>
  page.evaluate(
    () =>
      Array.from(document.querySelectorAll('.el-drawer')).filter(
        (d) => d.getBoundingClientRect().width > 0,
      ).length,
  );

const drawerState = () =>
  page.evaluate(() => {
    const open = Array.from(document.querySelectorAll('.el-drawer')).filter(
      (d) => d.getBoundingClientRect().width > 0,
    );
    const d = open[open.length - 1];
    if (!d) return null;
    const fields = Array.from(d.querySelectorAll('.el-form-item')).map((item) => {
      const label = (item.querySelector('.el-form-item__label')?.textContent ?? '').trim();
      const control = item.querySelector('input, textarea');
      // el-select 内部的 input 在 filterable 时不是 readonly，但它不是「可自由输入」的控件 ——
      // 往里面写文本只会改搜索框，不会改选中值（会造出「写成功、值没变」的假读数）。
      const editable =
        !!control &&
        !control.disabled &&
        !control.readOnly &&
        !control.closest('.el-select') &&
        control.type !== 'hidden' &&
        control.getBoundingClientRect().width > 0;
      return { label, editable, value: control ? control.value : null };
    });
    return {
      title: (d.querySelector('.el-drawer__header')?.textContent ?? '').trim(),
      fields,
      // 非 el-form-item 的控件（分支表格、开关、下拉）也算"面板有可操作的东西"
      widgets: {
        selects: d.querySelectorAll('.el-select').length,
        switches: d.querySelectorAll('.el-switch').length,
        numbers: d.querySelectorAll('.el-input-number').length,
        rows: d.querySelectorAll('.el-table__row').length,
        buttons: d.querySelectorAll('button').length,
      },
    };
  });

async function closeDrawer() {
  for (let i = 0; i < 3; i++) {
    if ((await visibleDrawerCount()) === 0) return true;
    const btn = page.locator('.el-drawer__close-btn').locator('visible=true').first();
    if ((await btn.count()) && (await btn.isVisible().catch(() => false))) {
      await btn.click({ timeout: 3000 }).catch(() => {});
    } else {
      await page.keyboard.press('Escape');
    }
    await page.waitForTimeout(350);
  }
  return (await visibleDrawerCount()) === 0;
}

async function closeMenu() {
  const hidden = () =>
    page
      .waitForFunction(
        () => {
          const el = document.querySelector('.workflow-dropdown-menu');
          return !el || getComputedStyle(el).display === 'none';
        },
        { timeout: 3000 },
      )
      .then(() => true)
      .catch(() => false);
  if (await hidden()) return;
  await page.keyboard.press('Escape');
  if (await hidden()) return;
  await page.getByRole('button', { name: /添加组件/ }).click().catch(() => {});
  await hidden();
}

function nodeCard(text) {
  // 按卡片文本开头匹配：`hasText` 是「包含」，会把「回复」匹到「兜底回复」、
  // 把「AI 对话」匹到描述里写着"生成回复"的别的项 —— 加错节点时症状是"点不开面板"。
  return page.locator('.lf-node').filter({ hasText: new RegExp('^\\s*' + escapeRe(text)) }).first();
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * 点节点卡片。
 * ★ 不用 `locator.click()`：新加节点可能落在顶部工具栏（`.top`）下面，Playwright 的可点性检查
 *   会一直报「pointer events 被拦截」。这里按卡片外框点**下半部分**（顶栏只盖上沿），
 *   仍是真实鼠标事件 —— LogicFlow 的选中依赖 mousedown/mouseup，dispatchEvent 试过不打开面板。
 */
async function clickCard(card) {
  const box = await card.boundingBox();
  if (!box) throw new Error('节点卡片没有可见外框：' + (await card.textContent()));
  await page.mouse.click(box.x + box.width / 2, box.y + box.height * 0.8);
  await page.waitForTimeout(900);
}

/** @returns {'added' | 'existing' | 'missing'} */
async function addNode(label) {
  await page.getByRole('button', { name: /添加组件/ }).click();
  await page.waitForTimeout(400);
  // 菜单项文本是「标题 + 描述」连在一起，描述里会提到别的节点名（「AI 对话」那项写着
  // "生成回复"，`hasText: '回复'` 就会先命中它）⇒ 按**标题开头**认，并且认不出来就报出来。
  const idx = await page.evaluate((title) => {
    const els = Array.from(document.querySelectorAll('.workflow-dropdown-item'));
    return els.findIndex((el) => (el.textContent ?? '').trim().startsWith(title));
  }, label);
  if (idx < 0) {
    await closeMenu();
    return (await nodeCard(label).count()) > 0 ? 'existing' : 'missing';
  }
  const item = page.locator('.workflow-dropdown-item').nth(idx);
  const text = (await item.textContent()) ?? '';
  if (text.includes('规划中')) {
    await closeMenu();
    return 'missing';
  }
  if (text.includes('已存在')) {
    await closeMenu();
    return 'existing';
  }
  await item.click();
  await page.waitForTimeout(800);
  await closeMenu();
  const tidy = page.getByRole('button', { name: /自动排版/ }).first();
  if (await tidy.count()) {
    await tidy.click({ timeout: 3000 }).catch(() => {});
    await page.waitForTimeout(800);
  }
  return 'added';
}

async function openPanel(label) {
  const card = nodeCard(label);
  if ((await card.count()) === 0) return null;
  await closeDrawer();
  await clickCard(card);
  return drawerState();
}

async function saveDraft() {
  await page.getByRole('button', { name: /^保存$/ }).click();
  await page.waitForTimeout(2200);
}

/** 读回草稿里该类节点的 config —— 区分「没落库」与「落库了但面板读不出」。 */
async function readSavedConfig(type) {
  const r = await fetch(`${API_URL}/api/v1/assistants/${APP}/workflow/draft`, { headers: auth });
  const graph = JSON.parse(await r.text()).data?.graph;
  const node = (graph?.nodes ?? []).find((n) => n.type === type);
  return node?.config ?? null;
}

for (const node of EXECUTABLE) {
  await resetDraft();
  await openCanvas();

  const before = await page.locator('.lf-node').count();
  const how = await addNode(node.label);
  const after = await page.locator('.lf-node').count();
  record(
    `${node.type} · 菜单可用`,
    how === 'missing' ? false : how === 'existing' ? after === before : after === before + 1,
    how === 'existing' ? `单例，用画布上已有的（${before} 个）` : `${before} → ${after}`,
  );
  if (how === 'missing') continue;

  const panel = await openPanel(node.label);
  record(`${node.type} · 点开后面板打开`, panel !== null, panel ? `标题=${panel.title}` : '没有可见抽屉');
  if (!panel) continue;

  const editable = panel.fields.filter((f) => f.editable);
  const widgets = Object.values(panel.widgets).reduce((a, b) => a + b, 0);
  record(
    `${node.type} · 面板有可操作控件`,
    node.type === 'START_NODE' ? panel.fields.length === 0 : editable.length > 0 || widgets > 0,
    node.type === 'START_NODE'
      ? '开始节点无配置项（只读变量目录）'
      : `${editable.length} 个可编辑 input/text` + (widgets ? ` + ${widgets} 个其它控件` : ''),
  );

  const declared = (node.configFields ?? []).map((f) => f.label);
  const panelLabels = panel.fields.map((f) => f.label);
  const missing = declared.filter((label) => !panelLabels.some((l) => l.includes(label)));
  if (SCHEMA_DRIVEN.has(node.type)) {
    record(
      `${node.type} · 面板字段与目录一致`,
      missing.length === 0,
      missing.length ? '缺: ' + missing.join('、') : `${declared.length} 项全在`,
    );
  } else if (missing.length) {
    notes.push(`${node.type}：面板 label 与目录不一致（缺 ${missing.join('、')}）—— 手写面板，B2 未推广`);
  }

  const probe = editable[0];
  if (!probe) {
    record(`${node.type} · 无自由文本控件（跳过落盘校验）`, true, '');
    await closeDrawer();
    continue;
  }

  const value = `e2e-${node.type.toLowerCase()}-${Date.now() % 100000}`;
  const wrote = await page.evaluate(
    ({ label, value }) => {
      const open = Array.from(document.querySelectorAll('.el-drawer')).filter(
        (d) => d.getBoundingClientRect().width > 0,
      );
      const d = open[open.length - 1];
      if (!d) return false;
      const item = Array.from(d.querySelectorAll('.el-form-item')).find(
        (it) => ((it.querySelector('.el-form-item__label')?.textContent ?? '').trim()) === label,
      );
      if (!item) return false;
      const input = item.querySelector('input, textarea');
      if (!input) return false;
      input.focus();
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.blur();
      return true;
    },
    { label: probe.label, value },
  );
  record(`${node.type} · 字段可写`, wrote, `探针字段=${probe.label || '(无 label)'}`);

  if (wrote) {
    await closeDrawer();
    await saveDraft();
    const savedConfig = await readSavedConfig(node.type);
    await openCanvas({ reload: true });
    const again = await openPanel(node.label);
    const kept = again?.fields.find((f) => f.label === probe.label)?.value === value;
    record(
      `${node.type} · 保存后重载值还在`,
      !!kept,
      `字段=${probe.label || '(无 label)'}，面板=${JSON.stringify(again?.title)}，落库=${JSON.stringify(
        savedConfig?.[probe.label] ?? savedConfig,
      ).slice(0, 160)}`,
    );
  }
  await closeDrawer();
}

console.log('\n控制台错误:', consoleErrors.length ? consoleErrors : '(无)');
if (notes.length) {
  console.log('\n已知差距（手写面板未按目录渲染，本测试不判失败）：');
  for (const n of notes) console.log('  · ' + n);
}
const failed = results.filter((r) => !r.ok);
console.log(`\n合计 ${results.length} 条：${results.length - failed.length} 通过，${failed.length} 失败`);
await browser.close();
process.exit(failed.length ? 1 : 0);
