// 定时触发的界面面（`#295` / 0.7.3）：顶栏那个按钮、对话框里的三块信息、保存与停用。
//
// 为什么界面也要有读数：后端能跑（`workflow-trigger-sweep.mjs` 已经证明了）与"用户能不能看见并操作它"
// 是两件事。这个项目吃过太多次"端点有了、页面没接"的账（`#79` 三格配对），所以这一层单独验。
//
// 用法：后端 + 前端都起着之后 `node e2e/workflow-trigger-panel.mjs`
const APP = process.env.AIP_TRIGGER_APP ?? 'c7d921d2-6b3d-4780-860e-102b157b21d3';
const { launch, login, assert, report, BASE_URL } = await import('./lib/pw.mjs');

const lines = [];
let failures = 0;
function check(name, ok, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!ok) failures += 1;
}

const browser = await launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on('console', (m) => {
  // 本轮**故意**打一次会被拒的 cron（断言那段文案），浏览器会把 400 记成 console error
  // —— 那是被测行为的一部分，不是页面缺陷。其余错误照算。
  if (m.type() === 'error' && !/status of 400/.test(m.text())) errors.push(m.text());
});

try {
  await login(page);
  await page.goto(`${BASE_URL}/#/assistants/${APP}/workflow`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.lf-node', { timeout: 30_000 });

  // ── 1. 顶栏有入口，且未开启时文案是「定时」 ───────────────────────────────
  const triggerBtn = page.getByRole('button', { name: /定时/ }).first();
  await triggerBtn.waitFor({ state: 'visible', timeout: 10_000 });
  const label0 = (await triggerBtn.textContent())?.trim() ?? '';
  check('顶栏有「定时」入口', /定时/.test(label0), `label=${label0}`);

  // ── 2. 打开对话框：三块信息按「未开启」渲染 ───────────────────────────────
  await triggerBtn.click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '定时触发' }).first();
  await dialog.waitFor({ state: 'visible', timeout: 8000 });
  const dialogText = (await dialog.textContent()) ?? '';
  check('对话框说明了「未开启」时的行为', /只在点|未开启/.test(dialogText), dialogText.slice(0, 60).replace(/\s+/g, ' '));
  check('对话框给了 cron 的写法提示', /5 段/.test(dialogText), '5 段提示');
  check('对话框说清了跑的是已发布版本', /已发布版本/.test(dialogText), '已发布版本');
  check('有四个预设', (await dialog.locator('.trigger-preset').count()) === 4,
    `presets=${await dialog.locator('.trigger-preset').count()}`);

  // ── 3. 保存一个「每天 09:00」 ─────────────────────────────────────────────
  await dialog.locator('.trigger-preset').first().click();
  const input = dialog.locator('input').first();
  check('点预设会把 cron 填进输入框', (await input.inputValue()) === '0 9 * * *', `value=${await input.inputValue()}`);

  await dialog.getByRole('button', { name: '保存' }).click();
  // 保存成功会关掉对话框并弹一条成功提示
  await page.waitForSelector('.el-message--success', { timeout: 15_000 }).catch(() => {});
  const savedMsg = (await page.locator('.el-message--success').first().textContent().catch(() => '')) ?? '';
  check('保存后提示里带上了下次触发时刻', /已保存/.test(savedMsg) && /下次/.test(savedMsg), savedMsg.trim());

  // ── 4. 顶栏变成「已定时」，再打开能看到「已开启」与下次时刻 ─────────────────
  await page.waitForTimeout(500);
  const label1 = (await page.getByRole('button', { name: /定时/ }).first().textContent())?.trim() ?? '';
  check('顶栏按钮变成「已定时」', label1.includes('已定时'), `label=${label1}`);

  await page.getByRole('button', { name: /已定时/ }).first().click();
  const dialog2 = page.locator('.el-dialog').filter({ hasText: '定时触发' }).first();
  await dialog2.waitFor({ state: 'visible', timeout: 8000 });
  const text2 = (await dialog2.textContent()) ?? '';
  check('再次打开显示「已开启 · 下次 …」', /已开启/.test(text2) && /下次/.test(text2), text2.slice(0, 80).replace(/\s+/g, ' '));
  check('显示了最近一次运行与来源', /最近一次运行/.test(text2), text2.slice(0, 120).replace(/\s+/g, ' '));

  // ── 5. 写错的 cron 要当场被拒，且对话框不关 ───────────────────────────────
  await dialog2.locator('input').first().fill('0 0 9 * * 1-5');
  await dialog2.getByRole('button', { name: '保存' }).click();
  await page.waitForSelector('.el-message--error', { timeout: 15_000 }).catch(() => {});
  const errMsg = (await page.locator('.el-message--error').first().textContent().catch(() => '')) ?? '';
  check('6 段 cron 保存时报错并说清段数', /5 段/.test(errMsg) && /6 段/.test(errMsg), errMsg.trim());
  check('报错后对话框仍然开着（没有把人踢出去）', await dialog2.isVisible(), 'dialog visible');

  // ── 6. 停用 ───────────────────────────────────────────────────────────────
  await dialog2.getByRole('button', { name: '停用' }).click();
  await page.waitForSelector('.el-message--success', { timeout: 15_000 }).catch(() => {});
  await page.waitForTimeout(600);
  const label2 = (await page.getByRole('button', { name: /定时/ }).first().textContent())?.trim() ?? '';
  check('停用后顶栏回到「定时」', label2 === '定时' || !label2.includes('已定时'), `label=${label2}`);

  check('全程没有控制台错误', errors.length === 0, errors.slice(0, 2).join(' | '));
} catch (error) {
  check('脚本跑完没有抛异常', false, error.message);
} finally {
  await browser.close();
}

report(['', ...lines, '', `${lines.filter((l) => l.startsWith('PASS')).length}/${lines.length} 通过`]);
process.exit(failures ? 1 : 0);
