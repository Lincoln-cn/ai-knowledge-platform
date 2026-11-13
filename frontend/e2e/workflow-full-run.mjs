// 完整 workflow 编排测试：一条真链路从入口跑到出结果。
//
// 与 workflow-node-panels.mjs 的分工：那个测「单个节点的配置面板」，
// 这个测「整张图在真后端上跑通」—— 真实检索（PG 全文索引）、真实模型调用（桩服务）、
// 真实节点执行记录。两者都过，才谈得上「这张图能用」。
//
// 为什么必须用桩服务（tools/openai_stub.py）而不是真模型：这里验的是编排与数据流，
// 模型答得好不好是另一回事；桩让「模型被调用了几次、收到什么 prompt」变成可断言的事实。
//
// 三段：
//   1. 自动链路 START → BASE → SEARCH → AI_CHAT → REPLY 跑到底
//   2. 人工介入 START → QUESTION → REPLY 停在 QUESTION（不跑完，状态存住）
//   3. 恢复：把答复交回去，从下一步接着跑到底（`#338` 的端到端判据）
//
// 前置：真栈（PG 15433 · 后端 18088 带 CAPTCHA_ENABLED=false · 前端 5280 · 桩 11434）
// 跑法：AIP_E2E_APP=... node e2e/workflow-full-run.mjs
import { API_URL, BASE_URL, launch, login } from './lib/pw.mjs';

const STUB = process.env.AIP_STUB ?? 'http://localhost:11434';
const APP = process.env.AIP_E2E_APP ?? '30fc12bd-5dd8-460f-a8c7-5bf4dacd2721';
const KB = process.env.AIP_KB ?? '05f0a754-6c67-4604-986d-ea8f32d54aa8';
const MODEL = process.env.AIP_MODEL ?? '10ba5063-c0aa-4423-8d50-391f8b7ac4c5';

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

const lr = await fetch(`${API_URL}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin' }),
});
const token = JSON.parse(await lr.text())?.data?.accessToken;
if (!token) throw new Error('登录失败（后端是不是开着验证码？用 CAPTCHA_ENABLED=false）');
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };

const post = async (path, body) => {
  const r = await fetch(`${API_URL}${path}`, { method: 'POST', headers: auth, body: JSON.stringify(body) });
  return { status: r.status, body: await r.text() };
};
const get = async (path) => {
  const r = await fetch(`${API_URL}${path}`, { headers: auth });
  return { status: r.status, body: await r.text() };
};
const stubCount = async () => {
  const all = await fetch(`${STUB}/__stub/requests`).then((r) => r.json()).catch(() => []);
  return Array.isArray(all) ? all.length : (all?.requests?.length ?? 0);
};

const node = (id, type, title, x, config) => ({ id, type, title, position: { x, y: 200 }, config });
const edge = (id, source, target) => ({ id, source, target });

// ── 1. 自动链路 ────────────────────────────────────────────────────────────
const autoGraph = {
  nodes: [
    node('n-start', 'START_NODE', '开始', 60, {}),
    node('n-base', 'BASE_NODE', '合同信息', 220, { prologue: '请说明合同违约条款', fields: [], apiFields: [] }),
    node('n-search', 'SEARCH_DATASET_NODE', '检索合同制度', 420, {
      kbIds: [KB],
      retrieval: { mode: 'HYBRID', threshold: 0.2, topN: 3, noRefStrategy: 'REFUSE' },
    }),
    node('n-ai', 'AI_CHAT_NODE', 'AI 对话', 640, {
      modelId: MODEL,
      rolePrompt: '你是合同顾问，只根据检索结果回答，简短。',
      promptIds: [],
      kbIds: [],
      thinkingEnabled: false,
      multiTurn: false,
      asResult: true,
    }),
    node('n-reply', 'REPLY_NODE', '回复', 880, {
      content: '已根据制度检索给出结论。',
      replyType: 'content',
      asResult: true,
    }),
  ],
  edges: [
    edge('e1', 'n-start', 'n-base'),
    edge('e2', 'n-base', 'n-search'),
    edge('e3', 'n-search', 'n-ai'),
    edge('e4', 'n-ai', 'n-reply'),
  ],
};

const drafts = await fetch(`${API_URL}/api/v1/assistants/${APP}/workflow/draft`, {
  method: 'PUT',
  headers: auth,
  body: JSON.stringify({ graph: autoGraph }),
});
record('自动链路 · 草稿写入', drafts.status === 200, `HTTP ${drafts.status}`);

const stubBefore = await stubCount();
const auto = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: autoGraph,
  inputs: { entry: '合同违约金怎么算？' },
});
const autoData = JSON.parse(auto.body)?.data;
record('自动链路 · 调试接口返回 ok', autoData?.ok === true, `HTTP ${auto.status}，failReason=${autoData?.errors ?? '(无)'}`);
record(
  '自动链路 · 跑到了回复节点（finalOutput 非空）',
  typeof autoData?.finalOutput === 'string' && autoData.finalOutput.length > 0,
  `finalOutput=${JSON.stringify(autoData?.finalOutput)?.slice(0, 80)}`,
);
record(
  '自动链路 · 轨迹覆盖 5 个节点',
  (autoData?.nodeTrail?.length ?? 0) === 5,
  autoData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '(无)',
);
const stubAfter = await stubCount();
record(
  '自动链路 · 模型被真的调用过',
  stubAfter > stubBefore,
  // ★ 不能断"恰好 1 次"：一次检索会先做查询理解（默认 3 条）再生成，桩会收到好几个请求。
  //   断"至少 1 次"能钉住「模型这一环真的接上了」，断具体次数只会随检索策略变化而假红。
  `桩请求 ${stubBefore} → ${stubAfter}（+${stubAfter - stubBefore}，含查询理解与生成）`,
);

// 执行记录：这一层才证明「节点真的执行过」，nodeTrail 只是编译出来的计划
const recs = JSON.parse((await get(`/api/v1/assistants/${APP}/workflow/records`)).body)?.data;
const recList = Array.isArray(recs) ? recs : (recs?.list ?? []);
const latest = recList[0];
record(
  '自动链路 · 运行记录为 SUCCEEDED',
  latest?.status === 'SUCCEEDED',
  `记录 ${recList.length} 条，最新 status=${latest?.status}，耗时 ${latest?.elapsedMs}ms`,
);

// ── 2. 人工介入：停在 QUESTION ────────────────────────────────────────────
const pauseGraph = {
  nodes: [
    node('p-start', 'START_NODE', '开始', 60, {}),
    node('p-ask', 'QUESTION_NODE', '提问', 300, {
      modelId: MODEL,
      rewritePrompt: '',
      defaultAnswer: '合同违约金怎么算？',
    }),
    node('p-reply', 'REPLY_NODE', '回复', 560, { content: '结论：按制度执行。', replyType: 'content', asResult: true }),
  ],
  edges: [edge('p1', 'p-start', 'p-ask'), edge('p2', 'p-ask', 'p-reply')],
};

const paused = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: pauseGraph,
  inputs: { entry: '' },
});
const pausedData = JSON.parse(paused.body)?.data;
record(
  '人工介入 · 停在提问节点（没有输出）',
  pausedData?.ok === true && !pausedData?.finalOutput,
  `ok=${pausedData?.ok}，finalOutput=${JSON.stringify(pausedData?.finalOutput)}`,
);

const pauses = JSON.parse((await get(`/api/v1/assistants/${APP}/workflow/records`)).body)?.data;
const pauseList = Array.isArray(pauses) ? pauses : (pauses?.list ?? []);
const pausedRec = pauseList[0];
record(
  '人工介入 · 运行记录为 PAUSED（不是 FAILED、也不是 SUCCEEDED）',
  pausedRec?.status === 'PAUSED',
  `最新 status=${pausedRec?.status}，failReason=${pausedRec?.failReason ?? '(无)'}`,
);

// ── 3. 恢复：把答复交回去，接着跑完 ──────────────────────────────────────
const executionId = pausedData?.executionId;
if (executionId) {
  const resumed = await post(`/api/v1/assistants/${APP}/workflow/resume`, {
    executionId,
    answer: '合同违约金按日万分之五计算。',
    // ★ 必须带 graph：恢复用「当前的图」编译一次计划，再与快照的 stepsDone 比 ——
    //   不带图就无从核对「暂停时那一步在今天这张图上还是不是同一个节点」。
    graph: pauseGraph,
  });
  const resumedData = JSON.parse(resumed.body)?.data;
  record(
    `恢复 · 从第 1 步之后接着跑到底（#338 的 off-by-one 判据）`,
    resumed.status === 200 && resumedData?.ok === true && !!resumedData?.finalOutput,
    `HTTP ${resumed.status}，ok=${resumedData?.ok}，finalOutput=${JSON.stringify(resumedData?.finalOutput)?.slice(0, 60)}`,
  );
} else {
  record('恢复 · 拿到 executionId', false, '调试返回里没有 executionId');
}

// ── 4. 界面上点「试用」—— 同一条链路，但走真实前端（`fromLogicFlow` 的产物）────
// 前三段都用 API 直接发契约形状；而人实际点的是「试用」：它先存一次草稿，再把画布上的图
// 转成契约形状发出去。`#335` 那批断链恰恰只在后者暴露 —— API 直发现成形状是测不出来的。
const browser = await launch();
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
await login(page);
await page.goto(`${BASE_URL}/#/assistants/${APP}/workflow`, { waitUntil: 'domcontentloaded' });
await page.locator('.lf-node').first().waitFor({ timeout: 20000 });
await page.waitForTimeout(1500);

await page.getByRole('button', { name: /^试用$/ }).click();
const promptBox = page.locator('.el-message-box').first();
await promptBox.waitFor({ timeout: 15000 });
await promptBox.locator('input').first().fill('合同违约金怎么算？');
// Element Plus 的 MessageBox 按钮文案随语言包变，按结构取最后一个（确定/OK 都在末尾）
await promptBox.locator('.el-message-box__btns button').last().click();

await page
  .waitForFunction(() => /最终输出/.test(document.body.innerText), { timeout: 90000 })
  .catch(() => {});
const alertText = await page.locator('.el-message-box').last().innerText().catch(() => '');
record(
  '界面试用 · 弹出结果（含耗时 / 节点轨迹 / 最终输出）',
  /最终输出/.test(alertText) && /耗时 \d+ms/.test(alertText),
  alertText.replace(/\s+/g, ' ').slice(0, 140),
);
record(
  '界面试用 · 最终输出就是回复节点的文案',
  alertText.includes('已根据制度检索给出结论。'),
  /最终输出：?\s*([^\n]*)/.exec(alertText)?.[1]?.trim().slice(0, 60) ?? '(没读到)',
);
await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n合计 ${results.length} 条：${results.length - failed.length} 通过，${failed.length} 失败`);
process.exit(failed.length ? 1 : 0);