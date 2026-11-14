/**
 * 真模型冒烟：用 DeepSeek（不是桩）把 14 个「不依赖多模态模型」的节点跑一遍。
 *
 * <p>为什么单独一套：既有的 `workflow-full-run.mjs` 断言里写着桩的固定文案
 * （"已根据制度检索给出结论。" 之类），换成真模型必然红 —— 但那是**断言绑了桩**，
 * 不是产品坏了。这一套只断结构：`ok`、错误的形状、轨迹里覆盖了哪些类型、
 * 以及"这条链路确实调了外部模型"（耗时显著高于桩）。
 *
 * <p>四个多模态节点（图像理解 / 语音合成 / 语音识别 / 图像生成）**不在这一套里**：
 * DeepSeek 只提供对话能力，没有 TTS/STT/IMAGE，图像理解要的是带视觉输入的对话模型
 * 而 deepseek-flash 不支持图像输入。那四个只能用桩服务或换别家服务商。
 *
 * 用法：`node e2e/workflow-real-model-smoke.mjs`
 *      环境变量 `AIP_MODEL` 覆盖对话模型（默认 deepseek-flash 的 id）。
 */
const API = 'http://127.0.0.1:18088';
const APP = process.env.AIP_E2E_APP ?? '30fc12bd-5dd8-460f-a8c7-5bf4dacd2721';
const MODEL = process.env.AIP_MODEL ?? 'ee76c564-294f-45b9-8556-ded5449aee76';
const KB = '05f0a754-6c67-4604-986d-ea8f32d54aa8';
const DOC = '0ff99adf-7a9d-47b9-930a-c686095cc4ee';

let pass = 0;
let fail = 0;
const row = (ok, name, detail) => {
  if (ok) pass += 1;
  else fail += 1;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const node = (id, type, title, config, x = 0) => ({ id, type, title, position: { x, y: 200 }, config });
const edge = (id, source, target, sourceHandle) => ({ id, source, target, ...(sourceHandle ? { sourceHandle } : {}) });

const lr = await fetch(`${API}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin' }),
});
const loginBody = await lr.text();
if (!lr.ok) throw new Error('登录失败: ' + loginBody.slice(0, 200));
const token = JSON.parse(loginBody).data.accessToken;
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };

const post = async (path, body) => {
  const r = await fetch(`${API}${path}`, { method: 'POST', headers: auth, body: JSON.stringify(body) });
  const text = await r.text();
  return { status: r.status, body: text, json: (() => { try { return JSON.parse(text); } catch { return null; } })() };
};

/** 跑一张图，返回 { ok, finalOutput, errors, types, ms } */
const run = async (graph, entry) => {
  const started = Date.now();
  const r = await post(`/api/v1/assistants/${APP}/workflow/debug`, { graph, inputs: { entry } });
  const d = r.json?.data;
  return {
    status: r.status,
    ok: d?.ok === true,
    finalOutput: d?.finalOutput ?? null,
    errors: d?.errors ?? null,
    executionId: d?.executionId ?? null,
    types: (d?.nodeTrail ?? []).map((x) => x.type),
    ms: Date.now() - started,
  };
};

const covers = (types, expected) => expected.every((t) => types.includes(t));
const brief = (res) => `ok=${res.ok} ${res.ms}ms trail=${res.types.length}条 errors=${JSON.stringify(res.errors)?.slice(0, 90)}`;

// ── 1. 检索 + 重排 + 对话 + 回复：三个都要外部模型 ───────────────────────────
{
  const graph = {
    nodes: [
      node('m-start', 'START_NODE', '开始', {}, 60),
      node('m-search', 'SEARCH_DATASET_NODE', '检索', { kbIds: [KB], retrieval: { mode: 'HYBRID', threshold: 0.3, topN: 5, noRefStrategy: 'REFUSE' } }, 300),
      node('m-rerank', 'RERANKER_NODE', '重排', { topK: 3, question: '' }, 540),
      node('m-ai', 'AI_CHAT_NODE', '对话', { modelId: MODEL, rolePrompt: '根据检索结果回答，不超过三句话。', prompt: '违约金怎么算？', dialogueNumber: 0, asResult: true }, 780),
      node('m-reply', 'REPLY_NODE', '回复', { content: '这条链路的结论已经写进 answer。', replyType: 'content', asResult: true }, 1020),
    ],
    edges: [edge('m1', 'm-start', 'm-search'), edge('m2', 'm-search', 'm-rerank'), edge('m3', 'm-rerank', 'm-ai'), edge('m4', 'm-ai', 'm-reply')],
  };
  const res = await run(graph, '违约金怎么算？');
  const expected = ['START_NODE', 'SEARCH_DATASET_NODE', 'RERANKER_NODE', 'AI_CHAT_NODE', 'REPLY_NODE'];
  row(res.ok && covers(res.types, expected) && res.finalOutput !== null,
    '检索 → 重排 → 对话 → 回复（真模型）', brief(res));
  row(res.ms > 1500, '这条链路确实调了外部模型（耗时 > 1.5s）', `${res.ms}ms`);
}

// ── 2. 问题改写 + 条件判断 ──────────────────────────────────────────────────
// ★ QUESTION_NODE 是**人工介入**节点（编译器把它的 id 放进 interruptNodeIds）：
//   执行到它之前就停住等人，它本身不调模型 —— 「改写」是画布上的旧名字，
//   它实际做的是"把这次答复写进 query，然后往下走"。所以这段验的是
//   暂停 → 恢复 → 条件判定 → 回复 这条链，而不是"模型改写得好不好"。
{
  const graph = {
    nodes: [
      node('q-start', 'START_NODE', '开始', {}, 60),
      node('q-ask', 'QUESTION_NODE', '提问', { modelId: MODEL, rewritePrompt: '把问题改写成一句独立可检索的问法。', defaultAnswer: '默认答复' }, 300),
      node('q-cond', 'CONDITION_NODE', '条件', { branches: [{ id: 'b-hit', type: 'IF', expression: '问题在问违约金' }], fallbackBranchId: 'b-fb' }, 540),
      node('q-hit', 'REPLY_NODE', '命中', { content: '走到了命中的分支。', replyType: 'content', asResult: true }, 780),
      node('q-fb', 'REPLY_NODE', '兜底', { content: '走到了兜底。', replyType: 'content', asResult: true }, 780),
    ],
    edges: [edge('q1', 'q-start', 'q-ask'), edge('q2', 'q-ask', 'q-cond'), edge('q3', 'q-cond', 'q-hit', 'b-hit'), edge('q4', 'q-cond', 'q-fb', 'b-fb')],
  };
  const paused = await run(graph, '合同里违约金怎么算？');
  row(paused.ok && paused.finalOutput === null && Boolean(paused.executionId), '提问节点：停在提问节点等人', brief(paused));
  if (paused.executionId) {
    const resumed = await post('/api/v1/assistants/' + APP + '/workflow/resume', {
      executionId: paused.executionId,
      answer: '合同里的违约金按合同总额的百分之五计算。',
      graph,
    });
    const d = resumed.json?.data;
    row(d?.ok === true && d?.finalOutput === '走到了命中的分支。', '提问 → 条件判断（恢复后走命中的分支）',
      `ok=${d?.ok} final=${JSON.stringify(d?.finalOutput)}`);
  }
}

// ── 3. 数值条件：不依赖模型，保留精确断言 ────────────────────────────────────
{
  const graph = {
    nodes: [
      node('n-start', 'START_NODE', '开始', {}, 60),
      node('n-len', 'FUNCTION_LIB_NODE', '取长度', { functionName: 'length', args: [{ from: 'variable', value: 'query' }], outputKey: 'len_value' }, 300),
      node('n-cond', 'NUMBER_CONDITION_NODE', '数值条件', {
        branches: [
          { id: 'n-long', type: 'IF', logic: 'AND', conditions: [{ variable: ['n-len', 'len_value'], operator: 'GT', value: '3' }] },
          { id: 'n-short', type: 'IF', logic: 'AND', conditions: [{ variable: ['n-len', 'len_value'], operator: 'LT', value: '3' }] },
        ],
        fallbackBranchId: 'n-fb',
      }, 540),
      node('n-r1', 'REPLY_NODE', '长', { content: '问题很长。', replyType: 'content', asResult: true }, 780),
      node('n-r2', 'REPLY_NODE', '短', { content: '问题很短。', replyType: 'content', asResult: true }, 780),
      node('n-r3', 'REPLY_NODE', '兜底', { content: '不确定。', replyType: 'content', asResult: true }, 780),
    ],
    edges: [edge('n1', 'n-start', 'n-len'), edge('n2', 'n-len', 'n-cond'), edge('n3', 'n-cond', 'n-r1', 'n-long'), edge('n4', 'n-cond', 'n-r2', 'n-short'), edge('n5', 'n-cond', 'n-r3', 'n-fb')],
  };
  const long = await run(graph, '合同违约金怎么算');
  const short = await run(graph, '合同');
  row(long.ok && long.finalOutput === '问题很长。', '数值条件：长输入命中 GT 分支', `${brief(long)} final=${JSON.stringify(long.finalOutput)}`);
  row(short.ok && short.finalOutput === '问题很短。', '数值条件：短输入命中 LT 分支', `${brief(short)} final=${JSON.stringify(short.finalOutput)}`);
}

// ── 4. 函数链：内置函数，不依赖模型 ──────────────────────────────────────────
{
  const graph = {
    nodes: [
      node('f-start', 'START_NODE', '开始', {}, 60),
      node('f-upper', 'FUNCTION_LIB_NODE', '转大写', { functionName: 'upper', args: [{ from: 'variable', value: 'query' }], outputKey: 'upper_value' }, 300),
      node('f-sub', 'FUNCTION_NODE', '取前两位', { functionName: 'substring', args: [{ from: 'variable', value: 'upper_value' }, { from: 'literal', value: '0' }, { from: 'literal', value: '2' }], outputKey: 'head' }, 540),
      node('f-reply', 'REPLY_NODE', '回复', { content: '函数链跑完了。', replyType: 'content', asResult: true }, 780),
    ],
    edges: [edge('f1', 'f-start', 'f-upper'), edge('f2', 'f-upper', 'f-sub'), edge('f3', 'f-sub', 'f-reply')],
  };
  const res = await run(graph, 'contract');
  row(res.ok && covers(res.types, ['FUNCTION_LIB_NODE', 'FUNCTION_NODE']) && res.finalOutput === '函数链跑完了。',
    '函数库 → 函数（内置函数，无模型）', brief(res));
}

// ── 5. 文档抽取：从库里取已解析正文 ──────────────────────────────────────────
{
  const graph = {
    nodes: [
      node('d-start', 'START_NODE', '开始', {}, 60),
      node('d-ext', 'DOCUMENT_EXTRACT_NODE', '文档抽取', { kbId: KB, docId: DOC }, 300),
      node('d-reply', 'REPLY_NODE', '回复', { content: '文档抽取跑完了。', replyType: 'content', asResult: true }, 540),
    ],
    edges: [edge('d1', 'd-start', 'd-ext'), edge('d2', 'd-ext', 'd-reply')],
  };
  const res = await run(graph, '随便问点什么');
  row(res.ok && res.finalOutput === '文档抽取跑完了。', '文档抽取（真库真文档）', brief(res));
}

// ── 6. 表单：先停下等人，恢复后跑完 ──────────────────────────────────────────
{
  const graph = {
    nodes: [
      node('fm-start', 'START_NODE', '开始', {}, 60),
      node('fm-form', 'FORM_NODE', '表单', { formFields: [], contentFormat: '{{form}}', asResult: true }, 300),
      node('fm-head', 'FUNCTION_LIB_NODE', '取前两位', { functionName: 'substring', args: [{ from: 'variable', value: 'form_data' }, { from: 'literal', value: '0' }, { from: 'literal', value: '2' }], outputKey: 'head' }, 540),
      node('fm-reply', 'REPLY_NODE', '回复', { content: '表单链路跑完了。', replyType: 'content', asResult: true }, 780),
    ],
    edges: [edge('fm1', 'fm-start', 'fm-form'), edge('fm2', 'fm-form', 'fm-head'), edge('fm3', 'fm-head', 'fm-reply')],
  };
  const paused = await run(graph, '请填一下');
  row(paused.ok && paused.finalOutput === null && paused.executionId, '表单：停在表单节点等人', brief(paused));
  if (paused.executionId) {
    const resumed = await post('/api/v1/assistants/' + APP + '/workflow/resume', {
      executionId: paused.executionId,
      answer: '合同事项',
      graph,
    });
    const d = resumed.json?.data;
    row(d?.ok === true && d?.finalOutput === '表单链路跑完了。', '表单：恢复后跑到末尾', `ok=${d?.ok} final=${JSON.stringify(d?.finalOutput)}`);
  }
}

// ── 7. 基础信息收集（BASE）：不依赖模型 ──────────────────────────────────────
{
  const graph = {
    nodes: [
      node('b-start', 'START_NODE', '开始', {}, 60),
      node('b-base', 'BASE_NODE', '基础', { prologue: '请提供合同编号。', fields: [{ name: '合同编号', required: true }], apiFields: [] }, 300),
      node('b-reply', 'REPLY_NODE', '回复', { content: '基础信息节点跑完了。', replyType: 'content', asResult: true }, 540),
    ],
    edges: [edge('b1', 'b-start', 'b-base'), edge('b2', 'b-base', 'b-reply')],
  };
  const res = await run(graph, '合同编号 HT-001');
  row(res.ok && res.finalOutput === '基础信息节点跑完了。', '基础信息收集（无模型）', brief(res));
}

// ── 8. 应用调用：子应用发布一个版本，父流程调它 ──────────────────────────────
{
  const childName = '__真模型冒烟 子应用__';
  const list = JSON.parse((await (await fetch(`${API}/api/v1/assistants?page=1&size=200`, { headers: auth })).text()))?.data;
  const found = (Array.isArray(list) ? list : list?.list ?? []).find((a) => a.name === childName);
  const childId = found?.id ?? JSON.parse((await post('/api/v1/assistants', { name: childName, type: 'WORKFLOW', description: '被应用调用节点调用' })).body)?.data?.id;
  const childGraph = {
    nodes: [
      node('c-start', 'START_NODE', '开始', {}, 60),
      node('c-reply', 'REPLY_NODE', '回复', { content: '子系统给的结论：按第 31 条。', replyType: 'content', asResult: true }, 320),
    ],
    edges: [edge('c1', 'c-start', 'c-reply')],
  };
  await fetch(`${API}/api/v1/assistants/${childId}/workflow/draft`, { method: 'PUT', headers: auth, body: JSON.stringify({ graph: childGraph }) });
  const pub = await post(`/api/v1/assistants/${childId}/workflow/publish`, { graph: childGraph, note: '真模型冒烟' });
  row(pub.status === 200, '应用调用：子应用发布出一个版本', `HTTP ${pub.status}`);

  const graph = {
    nodes: [
      node('a-start', 'START_NODE', '开始', {}, 60),
      node('a-app', 'APPLICATION_NODE', '应用调用', { applicationId: childId, version: '' }, 300),
      node('a-reply', 'REPLY_NODE', '回复', { content: '父流程收尾。', replyType: 'content', asResult: true }, 540),
    ],
    edges: [edge('a1', 'a-start', 'a-app'), edge('a2', 'a-app', 'a-reply')],
  };
  const res = await run(graph, '子应用，给我结论');
  row(res.ok && covers(res.types, ['APPLICATION_NODE']) && res.finalOutput === '父流程收尾。', '应用调用（真发布版本）', brief(res));
}

console.log('='.repeat(60));
console.log(`合计 ${pass + fail} 条：${pass} 通过，${fail} 失败`);
process.exit(fail === 0 ? 0 : 1);
