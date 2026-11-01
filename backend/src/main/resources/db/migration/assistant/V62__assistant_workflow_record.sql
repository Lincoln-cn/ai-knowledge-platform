-- 编排运行记录（`#198` 剩余端点之一：`GET /assistants/{appId}/workflow/records`）。
--
-- ★★ 为什么先建表、而"今天永远是空的"：
--   这张表**没有写入方**。运行记录的唯一写入者是把用户画的图真的跑一遍的那个执行引擎，
--   而 `09 §5.3` / `08 §二` 定的是「执行引擎在 V1.0」—— 今天它还不存在（`#113` 的准入判据
--   在 `caliber/WorkflowEngineAdmissionTest` 里钉着，`workflow` 模块里只有一个 package-info）。
--   ★ 特别注意：写入方**不是** `POST …/workflow/debug`。画布内调试**刻意不写**这张表
--   （设计册与 mock 同一口径：调试不产生正式运行记录，否则每点一次「试运行」都会污染真实账）。
--   所以：端点有了、表有了、读得到 0 行 —— 这是**如实**的状态，不是"功能已具备"。
--   等 `POST …/workflow/debug` 与执行引擎落地，它们各自成为写入方（那是一个独立批次）。
--
-- ★ 表的形状**不是在这里设计的**，是从冻结契约抄下来的：
--   `frontend/src/api/workflow.ts` 的 `WorkflowRecord`（id / appId / conversationId / input /
--   nodeTrail / finalOutputPreview / status / failReason / elapsedMs / startedAt）。
--   列名用 snake_case 是库侧惯例（同 `as_workflow_version.graph`），字段名与契约逐一对应，
--   不做二次改名 —— 多一层映射就多一处能对不上。
--
-- ★ status 的取值域用 check 约束**钉死**在库里，不靠应用层记：
--   `14 §2.4` 把 `WorkflowRecord.status` 与 `nodeTrail[].status` 都定成 `SUCCEEDED` / `FAILED`
--   （没有 `RUNNING` —— 契约里没有"运行中"这一态；正在跑的那次还没有落成记录）。
--   本仓库栽过 `chk_msg_status` 那次：写 `'DONE'` 被库拒绝。有约束时**库会当场拒**，
--   没有约束时同样的错值会静默存进去、读出来谁也看不懂 —— 所以要约束。

create table as_workflow_record (
  id                    uuid primary key default gen_random_uuid(),
  -- 记录归属的应用。★ 级联删：应用没了，它的运行记录也失去意义（且不删会留下读不到的主人）。
  assistant_id          uuid not null references as_assistant(id) on delete cascade,
  -- 这次运行由哪次会话触发。★ 刻意**不加外键**：运行记录是排查线索的历史账，
  --   会话被删不该连带抹掉"这个应用跑过什么、错在哪"——那正是出事之后最需要的那一页。
  conversation_id       uuid,
  -- 触发本次运行的输入原文。没有它，记录只能看出"跑过"，看不出"为什么这样跑"（契约注释）。
  input                 text not null,
  -- 走过的节点轨迹（JSON 数组：nodeId / type / title / status / enteredAt / elapsedMs / failReason）。
  -- ★ 记的是**执行当时**的节点名与状态，不是回查当前图 —— 流程改版后回查等于篡改历史。
  node_trail            jsonb not null,
  -- 最终答复的摘要（截断；完整答复在会话里）。可为空串：失败的那次没有答复。
  final_output_preview  text not null default '',
  status                text not null,
  fail_reason           text,
  -- 整条记录的总耗时（毫秒）。不让界面把 trail 加起来，两者的口径会漂移（契约注释）。
  elapsed_ms            int not null,
  started_at            timestamptz not null,
  created_at            timestamptz not null default now(),

  -- ★ 与 `14 §2.4` 逐字一致；多一个值就等于契约与库各说一套。
  constraint chk_workflow_record_status
    check (status in ('SUCCEEDED', 'FAILED')),
  -- 耗时是"花掉的时间"，负数只能来自一次减法实现（同 `chk_msg_prompt_tokens` 口径）。
  constraint chk_workflow_record_elapsed check (elapsed_ms >= 0),
  -- 失败必须给得出原因（与 `cv_message` 的 `chk_msg_failed_has_reason` 同一条口径）：
  -- 一条"失败了但不知道为什么"的记录对排查零价值，而它看起来完全正常。
  constraint chk_workflow_record_failed_has_reason
    check (status <> 'FAILED' or (fail_reason is not null and btrim(fail_reason) <> '')),
  -- 轨迹必须是数组：契约是 `nodeTrail: Array<…>`，存成对象时列表端点会把数组读成
  -- `undefined`，页面那条 `for (const n of trail)` 直接抛 —— 那种错不会报在写入侧。
  constraint chk_workflow_record_trail_is_array check (jsonb_typeof(node_trail) = 'array')
);

-- 列表的唯一查询形状：某应用、按开始时间倒序（新的在前）、分页。
-- ★ 索引的列序与排序方向跟查询逐字对应（`assistant_id` 等值 + `started_at desc` 排序），
--   否则分页会退化成整表排序。
create index idx_workflow_record_app on as_workflow_record (assistant_id, started_at desc);

comment on table as_workflow_record is
  '编排运行记录（#198；★ 今天没有写入方：执行引擎在 V1.0，画布调试刻意不写这张表）';
comment on column as_workflow_record.assistant_id is '归属应用；应用删除时级联删（记录失去主人就没有意义）';
comment on column as_workflow_record.conversation_id is
  '触发本次运行的会话 id；★ 无外键：会话删了也要留得住"跑过什么、错在哪"';
comment on column as_workflow_record.node_trail is
  '执行当时的节点轨迹快照（JSON 数组）；不回查当前图，回查等于篡改历史';
comment on column as_workflow_record.status is
  'SUCCEEDED / FAILED（14 §2.4；没有 RUNNING —— 正在跑的那次还没落成记录）';
comment on column as_workflow_record.elapsed_ms is '整条记录总耗时（毫秒），不让界面把 trail 自行相加';
