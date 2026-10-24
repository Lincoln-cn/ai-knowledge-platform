-- ════════════════════════════════════════════════════════════════════════════
-- 应用收藏（#195 的「收藏」子项）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么是一张关系表，而不是 as_assistant 上的一个数组列：
--   收藏是**按用户**的（同一个应用，不同人各自收藏）——数组列只能表达"谁收藏过"
--   的一份全局答案，而市场页的心形按钮与「只看收藏」都问的是"**我**收藏了吗"。
-- ★ 为什么 user_id 不建到 iam_user 的外键：与 as_assistant.owner_id 同口径 ——
--   identity 模块的用户生命周期与业务表解耦（删用户不该级联删业务数据；业务表只
--   记 sub 的 UUID）。
-- ★ 为什么有代理主键 id 而不是 (assistant_id, user_id) 复合主键：
--   列表 favorite=true 必须作为 SQL 谓词下推（相关 EXISTS），而谓词需要一个可映射的
--   类型；本仓没有 @EmbeddedId / @IdClass 先例，复合主键的样板会盖过这张表的语义。
--   唯一性由 uq_as_favorite 保证，重复收藏是幂等的（insert ... on conflict do nothing）。
-- ★ 删除应用：on delete cascade —— 但应用是**软删**（deleted_at），所以级联只在
--   物理清理时生效；软删期间收藏行保留，恢复应用后收藏关系不丢。
create table as_assistant_favorite (
  id            uuid        primary key,
  assistant_id  uuid        not null references as_assistant (id) on delete cascade,
  user_id       uuid        not null,
  created_at    timestamptz not null default now()
);

-- 收藏的幂等键：同一人对同一应用最多一行
create unique index uq_as_favorite on as_assistant_favorite (assistant_id, user_id);

-- 列表的 favorite=true 走 "exists (... where assistant_id = t.id and user_id = ?)"，
-- 反向的"我收藏了哪些"按 (user_id, created_at desc) 读 —— 两列顺序不能换。
-- ★ 这条 SQL 谓词由真 HTTP 测试覆盖（AssistantFavoriteHttpTest 的 total 断言），
--   不是"加索引时顺手写的猜测"。
create index idx_as_favorite_user on as_assistant_favorite (user_id, created_at desc);

comment on table as_assistant_favorite is
  '应用收藏（按用户）：市场页「只看收藏」与卡片心形按钮的存储';
comment on column as_assistant_favorite.assistant_id is
  '被收藏的应用；应用软删时收藏行保留，物理清理时级联删除';
comment on column as_assistant_favorite.user_id is
  '收藏人（JWT 的 sub）；不建 iam_user 外键，与 as_assistant.owner_id 同口径';
