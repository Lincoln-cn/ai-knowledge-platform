-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_knowledge_base —— 知识库（第 5 周 · 0.2.0 批 1）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这是第一个「归属资源」（07 §3.1 的 OwnedResource）落库：
--   owner_id + visibility + grants 三件套从此有了物理形状。
--   后续的 assistant / conversation 照这个形状长，判定逻辑零复制（07 §8.4）。
--
-- ★ max_classification 是 **ceiling**（13 §6）——「这个库允许装的最高密级」，
--   不是「谁能看这个库」。两者正交：谁能看是 Role.max_classification 的事。
--   入库校验（文档密级 ≤ ceiling）在文档上传那一段落地，但列必须现在就有：
--   后加 ⇒ 已经进库的越权文档要逐一重新处置（13 §8.3 的「后加代价」表）。
--
-- ★ client_request_id 是幂等键（#12）：同一键重复创建返回既有库，
--   而不是「名称已存在」。建库成功但后续上传失败后，重试不应被卡死、
--   也不该留下孤儿库。唯一约束是 (client_request_id, owner_id)：
--   幂等是「同一个调用方的重试」，不是「全平台一个命名空间」——
--   后者会让用户 B 撞上用户 A 的键时直接创建失败，且失败原因看起来像攻击。
--
-- ★ 枚举取值一律 SCREAMING_SNAKE_CASE（14 册 §2.1）：
--   visibility / import_type / max_classification / subject_kind 四列都遵守。
-- ════════════════════════════════════════════════════════════════════════════

create table kb_knowledge_base
(
  id                   uuid         not null,
  -- KB-I1：名称 1–30 字（领域层校验；库层用长度上限兜底）
  name                 varchar(30)  not null,
  -- 分类名（契约 category: string 是显示名，不是外键 —— 分类字典随 FR-03-03 后补）
  category             varchar(64)  not null,
  -- KB-I2：描述 ≤200 字
  description          varchar(200),
  icon                 varchar(64)  not null default '',
  -- BR-03-01：导入类型创建时选定，之后不可更改
  import_type          varchar(16)  not null,
  -- 绑定的向量模型（mc_model.id）。KB-I3/KB-I4 的校验在领域层；本段先落列
  embedding_model_id   uuid         not null,
  -- ★ ceiling：这个库允许装的最高密级（13 §6）。必填 —— 缺了它入库校验没有依据
  max_classification   varchar(16)  not null,
  -- 平台启用位：停用后不参与检索，数据保留
  enabled              boolean      not null default true,
  -- ★ 归属（OwnedResource）：创建时由后端从 JWT sub 写入，不接受前端传
  owner_id             uuid         not null,
  visibility           varchar(16)  not null default 'PRIVATE',
  -- 幂等键。nullable：不传 clientRequestId 的创建不参与幂等
  client_request_id    varchar(64),
  -- 计数列（列表页直接读，免连表 count）
  document_count       integer      not null default 0,
  segment_count        integer      not null default 0,
  char_count           bigint       not null default 0,
  -- 已有向量化内容 ⇒ 向量模型被锁定（KB-I4）
  has_vectorized_content boolean    not null default false,
  created_at           timestamptz  not null,
  created_by           uuid,
  updated_at           timestamptz  not null,
  updated_by           uuid,
  -- 软删：仅聚合根使用（02 §5.1）
  deleted_at           timestamptz,
  primary key (id),

  constraint chk_kb_visibility check (visibility in ('PRIVATE', 'SHARED', 'PUBLIC')),
  constraint chk_kb_import_type check (import_type in ('LOCAL', 'ONLINE')),
  constraint chk_kb_max_classification
    check (max_classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL')),
  constraint chk_kb_name_length check (char_length(name) between 1 and 30)
);

-- ★ 幂等键：(client_request_id, owner_id) 唯一。PostgreSQL 的 unique 对 NULL 不生效，
--   所以「不传 clientRequestId」的创建可以任意多条。
create unique index uq_kb_client_request on kb_knowledge_base (client_request_id, owner_id)
  where client_request_id is not null;

-- 列表页默认按分类 / 启用位筛选（契约 KnowledgeBaseQuery）
create index idx_kb_category on kb_knowledge_base (category) where deleted_at is null;
create index idx_kb_enabled on kb_knowledge_base (enabled) where deleted_at is null;
-- ★ 数据范围谓词的主索引：own/shared 档都会先按 owner_id 收窄
create index idx_kb_owner on kb_knowledge_base (owner_id) where deleted_at is null;

comment on table kb_knowledge_base is
  '知识库聚合根。第一个 OwnedResource：owner_id + visibility + grants（子表）三件套';
comment on column kb_knowledge_base.max_classification is
  'ceiling：这个库允许装的最高密级（13 §6）。★ 与 Role.max_classification（谁能看）正交，不要混';
comment on column kb_knowledge_base.client_request_id is
  '幂等键。同一 (client_request_id, owner_id) 重复创建返回既有库，而不是「名称已存在」';
comment on column kb_knowledge_base.owner_id is
  '归属人。创建时由后端从 JWT sub 写入，不接受前端传 —— 否则用户能把资源挂到别人名下';
comment on column kb_knowledge_base.visibility is
  '可见性。默认 PRIVATE：新建资源一律收窄（08 §4.4.4）。PUBLIC = 登录用户里人人可见，不等于免登录';

-- ────────────────────────── grants（07 §8.1） ──────────────────────────
--
-- ★ 用子表而不是 JSON 列：grants 必须可查询（07 §8.5 的 EXISTS 谓词），
--   而 02 §5.1 明确「JSON 列仅用于不参与查询的结构化数据」。
-- ★ 形状取「能长的」那种（subject + actions + expires_at + can_delegate），
--   而不是 {userIds, roleCodes}：加「共享给部门」= 加一个 SubjectKind，
--   加「只读/可编辑」= 每条 grant 多一个 action，都不动表结构（07 §8.1）。

create table kb_knowledge_base_grant
(
  id                 uuid         not null,
  knowledge_base_id  uuid         not null references kb_knowledge_base (id) on delete cascade,
  subject_kind       varchar(16)  not null,
  -- USER 时是 UUID 字符串；ROLE 时是角色 code（kind+id 一起比：部门「3」不是角色「3」）
  subject_id         varchar(64)  not null,
  -- 授权动作，与权限点同一套词汇：knowledge:view / knowledge:manage …
  actions            text[]       not null default '{}',
  -- 时效；null = 长期有效。判定必须带上时限检查，否则会写出永远不会过期的共享
  expires_at         timestamptz,
  can_delegate       boolean      not null default false,
  primary key (id),
  constraint chk_kb_grant_subject_kind
    check (subject_kind in ('USER', 'ROLE', 'ORG', 'GROUP', 'LINK'))
);

create index idx_kb_grant_kb on kb_knowledge_base_grant (knowledge_base_id);
-- 范围谓词按 (kind, id) 命中 grant（07 §8.5 的 EXISTS）
create index idx_kb_grant_subject on kb_knowledge_base_grant (subject_kind, subject_id);

comment on table kb_knowledge_base_grant is
  '知识库共享授权（07 §8.1）。仅 visibility=SHARED 时有意义；形状取「能长的」那种';
comment on column kb_knowledge_base_grant.subject_id is
  '与 subject_kind 一起比对 —— 部门「3」不是角色「3」（07 §8.2）';
