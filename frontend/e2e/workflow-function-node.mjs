#!/usr/bin/env node
/**
 * FUNCTION 两个节点的端到端：面板能拿到函数清单、图能落盘、真跑一次能出结果。
 *
 * ★ 请求一律走 Node 侧的 fetch（`API_URL` 直连后端 18088）。
 *   在 `page.evaluate` 里 fetch 会跨域（页面在 5280），CORS 拦下来只报一句
 *   `TypeError: Failed to fetch` —— 那句错误看起来像"服务没起"，会把人带到错方向。
 */
import { API_URL } from './lib/pw.mjs';

const APP_ID = '30fc12bd-5dd8-460f-a8c7-5bf4dacd2721';

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`);
};

let token = '';

async function call(path, init = {}) {
  const res = await fetch(`${API_URL}/api/v1${path}`, {
    ...init,
    headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), Authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
  });
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { status: res.status, body };
}

const graph = {
  nodes: [
    { id: 'n-start', type: 'START_NODE', title: '开始', position: { x: 80, y: 200 }, config: {} },
    {
      id: 'n-func',
      type: 'FUNCTION_LIB_NODE',
      title: '转大写',
      position: { x: 380, y: 200 },
      config: { functionName: 'upper', args: [{ from: 'variable', value: 'query' }], outputKey: '' },
    },
    // ★ 负空间反证：这一步的输入是上一步写出的 function_result。
    //   `substring("", 0, 2)` 会抛「结束位置 2 不在 [0, 0] 之内」⇒ 若上游没写出 function_result，
    //   这里必红。所以 ok=true 同时证明了三件事：两个函数节点串联能跑、变量传得过去、值非空。
    {
      id: 'n-func2',
      type: 'FUNCTION_NODE',
      title: '取前两位',
      position: { x: 680, y: 200 },
      config: {
        functionName: 'substring',
        args: [
          { from: 'variable', value: 'function_result' },
          { from: 'literal', value: '0' },
          { from: 'literal', value: '2' },
        ],
        outputKey: 'head_two',
      },
    },
    {
      id: 'n-reply',
      type: 'REPLY_NODE',
      title: '回复',
      position: { x: 980, y: 200 },
      config: { content: '函数结果已写回流程变量', replyType: 'content', asResult: true },
    },
  ],
  edges: [
    { source: 'n-start', target: 'n-func', sourceHandle: null },
    { source: 'n-func', target: 'n-func2', sourceHandle: null },
    { source: 'n-func2', target: 'n-reply', sourceHandle: null },
  ],
};

async function main() {
  const loginRes = await fetch(`${API_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin' }),
  });
  const loginBody = await loginRes.json();
  token = loginBody?.data?.accessToken ?? '';
  record('登录拿到令牌', Boolean(token), `status=${loginRes.status}`);

  // 1. 函数清单（面板下拉的数据源）
  const list = await call('/workflow/functions');
  const names = Array.isArray(list.body?.data) ? list.body.data : [];
  record(
    '函数清单非空（面板下拉靠它）',
    list.status === 200 && names.length >= 11,
    `status=${list.status} 个数=${names.length} 前三个=${names.slice(0, 3).join(',')}`
  );
  record(
    '清单里是内置函数名',
    names.includes('upper') && names.includes('jsonpath'),
    `upper=${names.includes('upper')} jsonpath=${names.includes('jsonpath')}`
  );

  // 2. 落盘
  const put = await call(`/assistants/${APP_ID}/workflow/draft`, {
    method: 'PUT',
    body: JSON.stringify({ graph }),
  });
  record('草稿可保存（函数节点能落盘）', put.status === 200, `status=${put.status}`);

  const saved = await call(`/assistants/${APP_ID}/workflow/draft`);
  const savedNode = (saved.body?.data?.graph?.nodes ?? []).find((n) => n.type === 'FUNCTION_LIB_NODE');
  record(
    '落盘后仍是契约形状（functionName/args 原样）',
    savedNode?.config?.functionName === 'upper' && Array.isArray(savedNode?.config?.args),
    `functionName=${savedNode?.config?.functionName} args=${JSON.stringify(savedNode?.config?.args)}`
  );

  // 3. 真跑一次
  const run = await call(`/assistants/${APP_ID}/workflow/debug`, {
    method: 'POST',
    body: JSON.stringify({ graph, inputs: { entry: 'abc' } }),
  });
  const data = run.body?.data ?? {};
  record('调试请求被接受', run.status === 200, `status=${run.status}`);
  record('流程跑到底（ok）', data.ok === true, `ok=${data.ok} failReason=${data.failReason ?? ''}`);
  const trail = (data.nodeTrail ?? []).map((n) => n.nodeType ?? n.type);
  record(
    '四个节点都执行了（两个函数节点串联）',
    trail.join(',') === 'START_NODE,FUNCTION_LIB_NODE,FUNCTION_NODE,REPLY_NODE',
    `轨迹=${trail.join('→')}`
  );
  record(
    '第一步的 function_result 传到了第二步（空串会让 substring 抛错）',
    data.ok === true,
    `ok=${data.ok} 响应字段=${Object.keys(data).join(',')}`
  );

  // 4. 反证一：函数名写错时必须红
  const badName = structuredClone(graph);
  badName.nodes[1].config.functionName = 'not_a_function';
  const bad = await call(`/assistants/${APP_ID}/workflow/debug`, {
    method: 'POST',
    body: JSON.stringify({ graph: badName, inputs: { entry: 'abc' } }),
  });
  const badData = bad.body?.data ?? {};
  record(
    '函数名不存在时失败',
    badData.ok === false,
    `ok=${badData.ok} 响应字段=${Object.keys(badData).join(',')} 失败信息=${JSON.stringify(
      JSON.stringify(badData.errors ?? '')
    ).slice(0, 140)}`
  );

  // 5. 反证二：变量名写错时第二步收到空串 ⇒ substring 越界必须红
  const badVar = structuredClone(graph);
  badVar.nodes[2].config.args[0].value = '从来没写过的变量';
  const badVarRun = await call(`/assistants/${APP_ID}/workflow/debug`, {
    method: 'POST',
    body: JSON.stringify({ graph: badVar, inputs: { entry: 'abc' } }),
  });
  const badVarData = badVarRun.body?.data ?? {};
  record(
    '变量不存在时第二步收到空串并失败（证明第一步确实写了值）',
    badVarData.ok === false,
    `ok=${badVarData.ok} 失败信息=${JSON.stringify(
      JSON.stringify(badVarData.errors ?? '')
    ).slice(0, 140)}`
  );

  // 恢复成能跑的图，免得把 e2e 应用留在坏状态
  await call(`/assistants/${APP_ID}/workflow/draft`, { method: 'PUT', body: JSON.stringify({ graph }) });

  const failed = results.filter((r) => !r.ok);
  console.log(`\n== 函数节点 e2e：${results.length - failed.length}/${results.length} 通过 ==`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
