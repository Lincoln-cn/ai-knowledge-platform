-- ════════════════════════════════════════════════════════════════════════════
-- 应用的共享授权（#195 的共享写子项；07 §8.1 / §8.5）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么现在才建：V29 的建表文末明写"grants 不在本批"。写入口缺失时，
--   前端的共享对话框只能拿到 404 —— 契约有、页面有、后端没有。
--
-- ★ 形状照抄 kb_knowledge_base_grant（V12 第 98 行起）：
--   subject + actions + expires_at + can_delegate 是"能长的"形状 ——
--   加「共享给部门」只是多一个 SubjectKind 取值，加「只读/可编辑」只是多一个 action，
--   都不动表结构（07 §8.1）。
--
-- ★ 用子表而不是 JSON 列：grants 必须能进 EXISTS 谓词（07 §8.5），
--   而 02 §5.1 明确「JSON 列仅用于不参与查询的结构化数据」。
--
-- ★ 与知识库共享表**不合并成一张泛化表**：两边的资源表不同、生命周期不同，
--   合并需要多一列 resource_type 且所有谓词都要带它；等第三个 OwnedResource 真的要共享时再说。
-- ════════════════════════════════════════════════════════════════════════════

create table as_assistant_grant
(
  id            uuid        not null,
  assistant_id  uuid        not null references as_assistant (id) on delete cascade,
  subject_kind  varchar(16) not null,
  -- USER/ORG 时是 UUID 字符串；ROLE 时是角色 code。★ kind 与 id 一起比：部门「3」不是角色「3」
  subject_id    varchar(64) not null,
  -- 授权动作，与权限点同一套词汇：assistant:view / assistant:edit …
  actions       text[]      not null default '{}',
  -- 时效；null = 长期有效。判定必须带上时限检查，否则会写出永远不会过期的共享
  expires_at    timestamptz,
  can_delegate  boolean     not null default false,
  primary key (id),
  constraint chk_as_grant_subject_kind
    check (subject_kind in ('USER', 'ROLE', 'ORG', 'GROUP', 'LINK'))
);

create index idx_as_grant_assistant on as_assistant_grant (assistant_id);

-- 范围谓词按 (kind, id) 命中 grant（07 §8.5 的 EXISTS；SHARED 数据范围要用）
create index idx_as_grant_subject on as_assistant_grant (subject_kind, subject_id);

comment on table as_assistant_grant is
  '应用共享授权（07 §8.1）。仅 visibility=SHARED 时有意义；形状取「能长的」那种';
comment on column as_assistant_grant.subject_id is
  '与 subject_kind 一起比对 —— 部门「3」不是角色「3」（07 §8.2）';
comment on column as_assistant_grant.expires_at is
  'null = 长期有效；判定必须带时限，否则共享永远不会过期';
