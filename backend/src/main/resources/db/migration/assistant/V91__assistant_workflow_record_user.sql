-- 工作流执行记录记下「谁发起」（#326 第 19 轮）。
--
-- ★ 为什么现在加：检索节点（SEARCH_DATASET_NODE）要按「谁在执行」去查 —— 密级 pre-filter 与组织 scope
-- 都挂在人身上。在执行记录有这一列之前，LinearWorkflowExecutor 只能给上下文传 user_id = null，
-- 检索实现拿到 null 就只有两条路：按系统身份查（等于绕过密级）、或者不查（功能缺失）。
-- 两者都不该是实现层自己选的，所以先把这一列立起来。
--
-- ★ 为什么可空、不回填：
--   1. 这不是占位符 —— 是「真的未知」。历史执行记录无法可靠地推断发起人（as_workflow_record 上没有别的身份列），
--      回填成某个默认用户等于伪造审计。
--   2. 语义上 null 是有意义的：它表示「这次执行没有记录身份」，读到它的人应当拒绝按「能看到全部」处理，
--      而不是默默当成管理员。
--
-- ★ 为什么不建 iam_user 的外键：与 as_assistant.owner_id、as_assistant_favorite.user_id 同口径（V50 已写明理由）——
-- 用户被删时执行记录仍然要留着（审计要能回答「谁在什么时候跑过」），外键会把这份历史一起删掉。

alter table as_workflow_record add column user_id uuid;

comment on column as_workflow_record.user_id is
  '发起这次执行的用户（#326）。可空：null 表示真的未知（历史记录无法可靠推断），不许被读成「管理员」或「能看到全部」';

-- 查询面向「我跑过哪些」：user_id 等值 + 时间倒序，列序与排序方向与查询逐字对应。
create index idx_workflow_record_user on as_workflow_record (user_id, started_at desc);
