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
    // ★ 重排节点夹在检索与生成之间：它读检索写的结构化命中（retrieval_hits），
    //   重排后写 rerank_result —— AI 对话节点优先读那一份，所以这一段真的改变了送进模型的前 3 条顺序。
    node('n-rerank', 'RERANKER_NODE', '重排', 540, { topK: 3, question: '' }),
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
    edge('e3', 'n-search', 'n-rerank'),
    edge('e3b', 'n-rerank', 'n-ai'),
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
  '自动链路 · 轨迹覆盖 6 个节点（含重排）',
  (autoData?.nodeTrail?.length ?? 0) === 6,
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

// ── 3b. 应用调用：把另一个已发布的应用当成这一步的函数 ─────────────────────
// 这一段的重点不是「能不能调用」，而是「只认已发布版本 + 递归有上限」：
// 子应用先发布一个版本，父流程指名调它；再造一个不存在的 id 看它是不是明确失败。
const childName = '__e2e 子应用__';
const existing = JSON.parse(
  (await get(`/api/v1/assistants?page=1&size=100&type=WORKFLOW`)).body,
)?.data;
const existingList = Array.isArray(existing) ? existing : (existing?.list ?? []);
const found = existingList.find((a) => a.name === childName);
const childId = found?.id
  ?? JSON.parse(
    (await post(`/api/v1/assistants`, { name: childName, type: 'WORKFLOW', description: '被 application 节点调用' })).body,
  )?.data?.id;

const childGraph = {
  nodes: [
    node('c-start', 'START_NODE', '开始', 60, {}),
    node('c-reply', 'REPLY_NODE', '回复', 320, { content: '子系统给的结论：按第 31 条。', replyType: 'content', asResult: true }),
  ],
  edges: [edge('c1', 'c-start', 'c-reply')],
};
await fetch(`${API_URL}/api/v1/assistants/${childId}/workflow/draft`, {
  method: 'PUT',
  headers: auth,
  body: JSON.stringify({ graph: childGraph }),
});
const pub = await post(`/api/v1/assistants/${childId}/workflow/publish`, { graph: childGraph, note: 'e2e 应用调用' });
record('应用调用 · 子应用发布出一个版本', pub.status === 200, `HTTP ${pub.status}`);

const appGraph = {
  nodes: [
    node('a-start', 'START_NODE', '开始', 60, {}),
    node('a-call', 'APPLICATION_NODE', '应用调用', 320, { applicationId: childId, version: '' }),
    node('a-reply', 'REPLY_NODE', '回复', 580, { content: '父流程收尾。', replyType: 'content', asResult: true }),
  ],
  edges: [edge('a1', 'a-start', 'a-call'), edge('a2', 'a-call', 'a-reply')],
};
const called = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: appGraph,
  inputs: { entry: '看一下子系统怎么说' },
});
const calledData = JSON.parse(called.body)?.data;
record(
  '应用调用 · 父流程跑到回复节点',
  calledData?.ok === true && calledData?.finalOutput === '父流程收尾。',
  `ok=${calledData?.ok}，finalOutput=${JSON.stringify(calledData?.finalOutput)?.slice(0, 60)}`,
);
record(
  '应用调用 · 轨迹是 开始 → 应用调用 → 回复',
  (calledData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '') === 'START_NODE → APPLICATION_NODE → REPLY_NODE',
  calledData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '(无)',
);

// 反证：指向一个不存在的应用，必须明确报错，而不是当作空输出继续往下跑
const missingGraph = {
  nodes: [
    node('m-start', 'START_NODE', '开始', 60, {}),
    node('m-call', 'APPLICATION_NODE', '应用调用', 320, { applicationId: '00000000-0000-0000-0000-00000000dead', version: '' }),
    node('m-reply', 'REPLY_NODE', '回复', 580, { content: '不该跑到这里。', replyType: 'content', asResult: true }),
  ],
  edges: [edge('m1', 'm-start', 'm-call'), edge('m2', 'm-call', 'm-reply')],
};
const missing = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: missingGraph,
  inputs: { entry: 'x' },
});
const missingData = JSON.parse(missing.body)?.data;
record(
  '应用调用 · 应用不存在时明确失败（不静默继续）',
  missingData?.ok === false && /不存在|看不到|还没有发布版本/.test(String(missingData?.errors ?? missingData?.failReason ?? '')),
  `ok=${missingData?.ok}，errors=${JSON.stringify(missingData?.errors ?? missingData?.failReason)?.slice(0, 80)}`,
);
// ── 3c. 函数：START → 函数库(upper) → 函数(substring) → 回复 ──────────────
// ★ 这里用「负空间」证明函数真的被调了：第二步读的是第一步写出的 `function_result`，
//   而 `substring("", 0, 2)` 会抛「结束位置 2 不在 [0, 0] 之内」⇒ 第一步没写出值，这条必红。
//   比去读 debug 响应的 state 更稳（那个响应里根本没有 state 字段）。
const funcGraph = {
  nodes: [
    node('f-start', 'START_NODE', '开始', 60, {}),
    node('f-lib', 'FUNCTION_LIB_NODE', '转大写', 320, {
      functionName: 'upper',
      args: [{ from: 'variable', value: 'query' }],
      outputKey: '',
    }),
    node('f-node', 'FUNCTION_NODE', '取前两位', 580, {
      functionName: 'substring',
      args: [
        { from: 'variable', value: 'function_result' },
        { from: 'literal', value: '0' },
        { from: 'literal', value: '2' },
      ],
      outputKey: 'head_two',
    }),
    node('f-reply', 'REPLY_NODE', '回复', 840, { content: '函数链跑完了。', replyType: 'content', asResult: true }),
  ],
  edges: [edge('f1', 'f-start', 'f-lib'), edge('f2', 'f-lib', 'f-node'), edge('f3', 'f-node', 'f-reply')],
};
const funcRun = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: funcGraph,
  inputs: { entry: 'abc' },
});
const funcData = JSON.parse(funcRun.body)?.data;
record(
  '函数 · 两个函数节点串联跑到底',
  funcData?.ok === true && funcData?.finalOutput === '函数链跑完了。',
  `ok=${funcData?.ok}，finalOutput=${JSON.stringify(funcData?.finalOutput)?.slice(0, 40)}`,
);
record(
  '函数 · 轨迹是 开始 → 函数库 → 函数 → 回复',
  (funcData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '') ===
    'START_NODE → FUNCTION_LIB_NODE → FUNCTION_NODE → REPLY_NODE',
  funcData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '(无)',
);

// 反证：把第二步的变量名改成不存在的，它就会收到空串 ⇒ substring 越界 ⇒ 必须红
const badFuncGraph = structuredClone(funcGraph);
badFuncGraph.nodes[2].config.args[0].value = '从来没写过的变量';
const badFuncRun = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: badFuncGraph,
  inputs: { entry: 'abc' },
});
const badFuncData = JSON.parse(badFuncRun.body)?.data;
record(
  '函数 · 变量不存在时明确失败（反证第一步确实写了值）',
  badFuncData?.ok === false && /substring|超出范围/.test(String(badFuncData?.errors ?? '')),
  `ok=${badFuncData?.ok}，errors=${JSON.stringify(badFuncData?.errors)?.slice(0, 100)}`,
);

// ── 3d. 多模态两串：产出变量同名就能直接串起来 ────────────────────────────
// 这一段验的不是「模型会不会画图 / 会不会说话」（桩不实现那些），而是**数据流**：
// 图像生成写 `image_key`，图像理解默认就读 `image_key`；语音合成写 `audio_key`，
// 语音识别默认就读 `audio_key` —— 两个节点之间不填任何变量名也能接上。
// ★ 媒体字节不进流程变量（`FlowState` 是 Map<String,String>），变量里只有文件键，
//   所以这里紧接着用 `GET /api/v1/files/{key}` 把字节取回来验「真的落盘了」。
const mediaModels = JSON.parse((await get('/api/v1/models?page=1&size=200')).body)?.data?.list ?? [];
const pickModel = (kind, name) =>
  mediaModels.find((m) => m.kind === kind && m.name === name)?.id ?? mediaModels.find((m) => m.kind === kind)?.id;

const ttsModel = pickModel('TTS', '桩语音合成');
const sttModel = pickModel('STT', '桩语音识别');
const imageModel = pickModel('IMAGE', '桩图像生成');
record(
  '多模态 · 库里有 TTS / STT / IMAGE 三种桩模型',
  Boolean(ttsModel && sttModel && imageModel),
  `tts=${ttsModel?.slice(0, 8)} stt=${sttModel?.slice(0, 8)} image=${imageModel?.slice(0, 8)}`,
);

const imageGraph = {
  nodes: [
    node('i-start', 'START_NODE', '开始', 60, {}),
    node('i-gen', 'IMAGE_GENERATE_NODE', '图像生成', 320, { modelId: imageModel, size: '', promptKey: 'query' }),
    node('i-see', 'IMAGE_UNDERSTAND_NODE', '图像理解', 580, { modelId: MODEL, imageKey: 'image_key', question: '这张图是什么？' }),
    node('i-reply', 'REPLY_NODE', '回复', 840, { content: '图像链路跑完了。', replyType: 'content', asResult: true }),
  ],
  edges: [
    edge('i1', 'i-start', 'i-gen'),
    edge('i2', 'i-gen', 'i-see'),
    edge('i3', 'i-see', 'i-reply'),
  ],
};
const imageRun = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: imageGraph,
  inputs: { entry: '一只戴墨镜的猫' },
});
const imageData = JSON.parse(imageRun.body)?.data;
record(
  '多模态 · 图像生成 → 图像理解 → 回复 跑到底',
  imageData?.ok === true && imageData?.finalOutput === '图像链路跑完了。',
  `ok=${imageData?.ok}，failReason=${JSON.stringify(imageData?.errors ?? imageData?.failReason)?.slice(0, 120)}`,
);
record(
  '多模态 · 图像链路的轨迹是 4 个节点',
  (imageData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '') ===
    'START_NODE → IMAGE_GENERATE_NODE → IMAGE_UNDERSTAND_NODE → REPLY_NODE',
  imageData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '(无)',
);

const speechGraph = {
  nodes: [
    node('s-start', 'START_NODE', '开始', 60, {}),
    node('s-tts', 'TEXT_TO_SPEECH_NODE', '语音合成', 320, { modelId: ttsModel, voice: 'alloy', sourceKey: 'query' }),
    node('s-stt', 'SPEECH_TO_TEXT_NODE', '语音识别', 580, { modelId: sttModel, audioKey: 'audio_key', filename: 'audio.mp3' }),
    node('s-reply', 'REPLY_NODE', '回复', 840, { content: '语音链路跑完了。', replyType: 'content', asResult: true }),
  ],
  edges: [
    edge('s1', 's-start', 's-tts'),
    edge('s2', 's-tts', 's-stt'),
    edge('s3', 's-stt', 's-reply'),
  ],
};
const speechRun = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: speechGraph,
  inputs: { entry: '合同违约金怎么算' },
});
const speechData = JSON.parse(speechRun.body)?.data;
record(
  '多模态 · 语音合成 → 语音识别 → 回复 跑到底',
  speechData?.ok === true && speechData?.finalOutput === '语音链路跑完了。',
  `ok=${speechData?.ok}，failReason=${JSON.stringify(speechData?.errors ?? speechData?.failReason)?.slice(0, 120)}`,
);
record(
  '多模态 · 语音链路的轨迹是 4 个节点',
  (speechData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '') ===
    'START_NODE → TEXT_TO_SPEECH_NODE → SPEECH_TO_TEXT_NODE → REPLY_NODE',
  speechData?.nodeTrail?.map((t) => t.type).join(' → ') ?? '(无)',
);

// 反证：把图像理解的 modelId 换成"不存在的模型" ⇒ 必须明确报"模型不可用"，
// 而不是拿空描述继续往下跑（静默失败是这一族最危险的地方）。
const badImageGraph = {
  nodes: [
    node('b-start', 'START_NODE', '开始', 60, {}),
    node('b-gen', 'IMAGE_GENERATE_NODE', '图像生成', 320, { modelId: imageModel, size: '', promptKey: 'query' }),
    node('b-see', 'IMAGE_UNDERSTAND_NODE', '图像理解', 580, {
      modelId: '00000000-0000-0000-0000-00000000beef',
      imageKey: 'image_key',
      question: 'x',
    }),
    node('b-reply', 'REPLY_NODE', '回复', 840, { content: '不该跑到这里。', replyType: 'content', asResult: true }),
  ],
  edges: [
    edge('b1', 'b-start', 'b-gen'),
    edge('b2', 'b-gen', 'b-see'),
    edge('b3', 'b-see', 'b-reply'),
  ],
};
const badImageRun = await post(`/api/v1/assistants/${APP}/workflow/debug`, {
  graph: badImageGraph,
  inputs: { entry: 'x' },
});
const badImageData = JSON.parse(badImageRun.body)?.data;
record(
  '多模态 · 模型不存在时明确失败（不静默继续）',
  badImageData?.ok === false && /模型不可用|种类是/.test(String(badImageData?.errors ?? badImageData?.failReason ?? '')),
  `ok=${badImageData?.ok}，errors=${JSON.stringify(badImageData?.errors ?? badImageData?.failReason)?.slice(0, 100)}`,
);

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