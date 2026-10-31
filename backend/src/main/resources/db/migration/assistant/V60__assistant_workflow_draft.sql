-- 编排草稿（#198 的第一块：把「画布上的那张图」存起来）。
--
-- ★ 为什么单开一张表而不是给 ai_assistant 加一列：
--   草稿会被频繁整体覆盖（每分钟自动保存 + 每次点保存），与 `as_assistant` 的其它列
--   （名称、可见性、发布态）读写频率差一个量级；放同一行会让每次自动保存都顺带更新
--   应用的 `updated_at`，于是「这个应用最近被人改过吗」这个问题失去意义。
--
-- ★ 为什么 graph 用 jsonb 而不是拆成节点表 / 边表：
--   草稿是**未固化的图**，它的形状由画布决定并且允许不合规（边画边存）。拆表等于现在就把
--   节点类型的目录钉死在库结构上 —— 那是 #198 后半段（发布固化 + 版本）才能定的事。
--   一旦固化成版本，再按版本行拆表。
create table as_workflow_draft (
  assistant_id uuid primary key references as_assistant (id) on delete cascade,
  graph jsonb not null,
  updated_at timestamptz not null default now()
);

comment on column as_workflow_draft.graph is
  '画布图（nodes/edges 的原始 JSON）。允许不合规：保存不做校验，校验在发布前（POST /workflow/validate）';
