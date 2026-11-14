// 定时触发的端到端验证（`#295` / 0.7.3）。
//
// 为什么必须是"真等一分钟"而不是打桩：这条链路上真正会错的不是逻辑分支，而是**时间**——
// cron 解释成哪个时刻、扫到了没有、`next_at` 有没有顺延。桩掉调度器等于把这三点全跳过去。
//
// 用法：后端（18088）+ 前端（5280）+ 桩（11434）都起着之后
//   node e2e/workflow-trigger-sweep.mjs
//
// 用的应用是 `__真模型冒烟 子应用__`：它的已发布图是 START → REPLY 两节点、回复是写死的文本，
// 所以定时触发不依赖任何模型服务 —— 这条用例只验调度，不验模型。
const API = process.env.AIP_BE_URL ?? 'http://localhost:18088';
const APP = process.env.AIP_TRIGGER_APP ?? 'c7d921d2-6b3d-4780-860e-102b157b21d3';

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

const lr = await fetch(`${API}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin' }),
});
const token = JSON.parse(await lr.text())?.data?.accessToken;
if (!token) throw new Error('登录失败（后端是不是开着验证码？用 CAPTCHA_ENABLED=false）');
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };

const getApp = async () => {
  const r = await fetch(`${API}/api/v1/assistants/${APP}`, { headers: auth });
  const body = await r.text();
  if (r.status !== 200) throw new Error(`读应用失败 ${r.status}: ${body.slice(0, 200)}`);
  return JSON.parse(body).data;
};
const setTrigger = async (cron) => {
  const r = await fetch(`${API}/api/v1/assistants/${APP}/trigger`, {
    method: 'PUT',
    headers: auth,
    body: JSON.stringify({ cron }),
  });
  return { status: r.status, body: await r.text() };
};

const pad = (n) => String(n).padStart(2, '0');
/** 把"现在 + offsetMin 分钟"写成 5 段 cron 的「分 时」两位，日/月/周留 `*`。 */
function cronForMinutesFromNow(offsetMin) {
  const d = new Date(Date.now() + offsetMin * 60_000);
  return { cron: `${d.getMinutes()} ${d.getHours()} * * *`, at: d };
}

// ── 0. 准备：先清一次，让"基线"这件事可重复 ──────────────────────────────
//    前一次跑完（或者别人手动点过试用）会留下 trigger_last_at，用它当基线就要求"库必须是全新的" ——
//    那样这条用例只能在干净的库上跑一次，第二次就红。清空既验证了"关掉"这件事，也让后面每一格都是确定的。
const reset = await setTrigger(null);
record('准备：清空定时触发返回 200', reset.status === 200, `status=${reset.status}`);

const before = await getApp();
record('应用存在且是工作流类型', before.type === 'WORKFLOW', `type=${before.type}`);
record('准备后：没配 cron，也没有残留的上次运行时刻', !before.triggerCron && !before.triggerLastAt && !before.triggerNextAt,
  `cron=${before.triggerCron} last=${before.triggerLastAt} next=${before.triggerNextAt}`);

// ── 1. 配一个"两分钟后"的 cron ────────────────────────────────────────────
const { cron, at } = cronForMinutesFromNow(2);
const scheduled = await setTrigger(cron);
record('配置定时触发返回 200', scheduled.status === 200, `status=${scheduled.status} body=${scheduled.body.slice(0, 160)}`);

const afterSchedule = JSON.parse(scheduled.body).data ?? {};
record('回显带回了 cron', afterSchedule.triggerCron === cron, `cron=${afterSchedule.triggerCron}`);
const nextAt = afterSchedule.triggerNextAt ? new Date(afterSchedule.triggerNextAt) : null;
record('后端算出了下次触发时刻', !!nextAt, `nextAt=${afterSchedule.triggerNextAt}`);
// 允许 90s 偏差：cron 只精确到分钟，输入框里的 `H:M` 与后端解释出的时刻最多差一个整分。
const skewMs = nextAt ? Math.abs(nextAt.getTime() - at.getTime()) : Infinity;
record('下次触发时刻与输入一致（±90s）', skewMs <= 90_000, `skew=${Math.round(skewMs / 1000)}s expected≈${at.toISOString()}`);

// ── 2. 等扫描器真的跑一次 ─────────────────────────────────────────────────
const DEADLINE = Date.now() + 200_000;
let fired = null;
while (Date.now() < DEADLINE) {
  await new Promise((r) => setTimeout(r, 5000));
  const now = await getApp();
  if (now.triggerLastAt && now.triggerLastAt !== before.triggerLastAt) {
    fired = now;
    break;
  }
}
record('到点后扫描器真的跑了一次（trigger_last_at 前进）', !!fired, fired ? `lastAt=${fired.triggerLastAt}` : '200s 内没有变化');

if (fired) {
  record('下一次触发时刻已经顺延到未来', new Date(fired.triggerNextAt).getTime() > new Date(fired.triggerLastAt).getTime(),
    `next=${fired.triggerNextAt} last=${fired.triggerLastAt}`);
  record('正在补跑的次数为 0（这次是准点，不是补跑）', (fired.triggerMissed ?? 0) === 0, `missed=${fired.triggerMissed}`);

  // ★ 这条是「定时触发能被看见」的判据：记录里必须能分辨出这次是它自己到点跑的，
  //   否则列在库里没人读（`#295` 最初被批的形态就是"有列无读者"）。
  const recs = await fetch(`${API}/api/v1/assistants/${APP}/workflow/records?page=1&size=1`, { headers: auth });
  const top = (await recs.json())?.data?.list?.[0];
  record('运行记录里这次是 CRON 发起的', top?.triggerSource === 'CRON', `triggerSource=${top?.triggerSource}`);
  record('那次运行成功且留下了输出摘要', top?.status === 'SUCCEEDED' && !!top?.finalOutputPreview,
    `status=${top?.status} preview=${String(top?.finalOutputPreview).slice(0, 40)}`);

  // 这次是 `2 分钟后` 的 cron，所以第二次触发要再等两分钟 —— 用例到此为止，不清空会在后台继续跑，
  // 所以第 4 步无条件清空（无论上面断言结果如何）。
}

// ── 3. 反例：写错的 cron 必须被拒，而不是"存下来但永远不跑" ────────────────
const six = await setTrigger('0 0 9 * * 1-5');
record('6 段 cron 被拒（400）', six.status === 400, `status=${six.status} body=${six.body.slice(0, 120)}`);
record('拒的原因说清了段数', /5 段/.test(six.body) && /6 段/.test(six.body), six.body.slice(0, 160));

const never = await setTrigger('0 0 30 2 *');
record('永不发生的 cron 被拒（400）', never.status === 400, `status=${never.status} body=${never.body.slice(0, 120)}`);

const afterRejects = await getApp();
record('被拒的两次没有改动已保存的 cron', afterRejects.triggerCron === cron, `cron=${afterRejects.triggerCron}`);

// ── 4. 收尾：关掉，别让它在后台一直跑 ──────────────────────────────────────
const cleared = await setTrigger(null);
const afterClear = JSON.parse(cleared.body).data ?? {};
record('清空后 cron / nextAt / lastAt 都为空', !afterClear.triggerCron && !afterClear.triggerNextAt && !afterClear.triggerLastAt,
  `cron=${afterClear.triggerCron} next=${afterClear.triggerNextAt} last=${afterClear.triggerLastAt}`);

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length ? 1 : 0);
