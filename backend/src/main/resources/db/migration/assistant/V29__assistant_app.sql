-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：as_assistant —— 应用（智能体）配置（第 15 周 · 0.4.3）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这是**第二个「归属资源」**（07 §3.1 的 OwnedResource）。形状照抄 V12 的 kb_knowledge_base：
--   owner_id + visibility 两列在此，grants 子表**本批不建** —— 见文末那条注释。
--   ⇒ 判定逻辑零复制（07 §8.4）：实体实现 platform.access.OwnedResource，判定侧只认那个接口。
--
-- ★ type 创建后不可改（SRS 02 / FR-04；契约 UpsertAssistantBody 的注释也这么写）：
--   改成编排或反之等于换了一个应用。所以它既是列，也是**幂等创建**的一部分 ——
--   更新路径根本不碰它（领域方法不提供 setter）。
--
-- ★ 「简单有模型、编排没有模型」用 **check 约束**写死在库里：
--   编排应用的模型在各 AI 节点里，可一个流程用多个模型；应用壳上放一个「默认模型」会让人
--   以为整条流程就用那一个（契约 Assistant.modelId 的注释）。库里兜一道，免得某条写入路径漏判。
--
-- ★ 枚举取值一律 SCREAMING_SNAKE_CASE（14 册 §2.1）：type / visibility / retrieval_* 都遵守。
-- ════════════════════════════════════════════════════════════════════════════

create table as_assistant
(
  id                     uuid          not null,
  -- AS-I1：名称 1–30 字（领域层校验；库层用长度上限兜底）
  name                   varchar(30)   not null,
  icon                   varchar(64)   not null default '',
  -- 描述 ≤200 字
  description            varchar(200),
  -- BR：创建时选定，之后不可更改
  type                   varchar(16)   not null,
  -- 对话模型（mc_model.id）。★ 简单应用必填、编排应用必须为空 —— 见 chk_as_model_by_type
  model_id               uuid,
  -- 角色设定（提示词正文）。上限给足：它是用户写的长文本，不是标题
  role_prompt            varchar(4000) not null default '',
  multi_turn             boolean       not null default true,
  thinking_enabled       boolean       not null default false,
  prologue               varchar(1000) not null default '',
  -- 推荐问题：数组而不是 JSON —— 它要能单独取出来渲染，也和 tags 同一形状
  quick_questions        text[]        not null default '{}',
  -- 市场分类标签（列表筛选用）
  tags                   text[]        not null default '{}',

  -- ────────────────────────── 检索参数（与知识库同形，应用覆盖库上的值）──────────────────────────
  -- ★ 为什么挂在应用上：契约 Assistant.retrieval 就是 RetrievalParams 的完整形状；
  --   而「检索参数是资源的一部分，读与写先问同一个判定」这条口径与 V12 一致。
  retrieval_mode         varchar(16)   not null default 'HYBRID',
  retrieval_threshold    numeric(3, 2) not null default 0.60,
  retrieval_top_n        smallint      not null default 5,
  retrieval_no_ref_strategy varchar(16) not null default 'REFUSE',
  retrieval_query_count  smallint      not null default 3,
  retrieval_decompose_enabled boolean  not null default true,
  retrieval_max_sub_questions smallint not null default 3,

  -- 内置应用不可编辑对话设定，仅供问答
  builtin                boolean       not null default false,
  published              boolean       not null default false,

  -- ★ 归属（OwnedResource）：创建时由后端从 JWT sub 写入，不接受前端传
  owner_id               uuid          not null,
  visibility             varchar(16)   not null default 'PRIVATE',
  -- 幂等键。nullable：不传 clientRequestId 的创建不参与幂等（形状照抄 V12）
  client_request_id      varchar(64),

  -- 计数列（列表/广场卡片直接读，免连表 count）
  call_count             bigint        not null default 0,
  token_count            bigint        not null default 0,
  -- ★ 满意度口径待第 18 周赞踩落地时定（现在只落列，不参与任何计算）
  satisfaction           numeric(5, 2),

  created_at             timestamptz   not null,
  created_by             uuid,
  updated_at             timestamptz   not null,
  updated_by             uuid,
  -- 软删：仅聚合根使用（02 §5.1）
  deleted_at             timestamptz,
  primary key (id),

  constraint chk_as_type check (type in ('SIMPLE', 'WORKFLOW')),
  constraint chk_as_visibility check (visibility in ('PRIVATE', 'SHARED', 'PUBLIC')),
  constraint chk_as_retrieval_mode check (retrieval_mode in ('VECTOR', 'FULLTEXT', 'HYBRID')),
  constraint chk_as_no_ref_strategy check (retrieval_no_ref_strategy in ('REFUSE', 'FREE_ANSWER')),
  constraint chk_as_name_length check (char_length(name) between 1 and 30),
  constraint chk_as_threshold check (retrieval_threshold >= 0 and retrieval_threshold <= 1),
  -- ★ 「简单有模型、编排无模型」：把契约里那句注释写成库层约束
  constraint chk_as_model_by_type check (
    (type = 'SIMPLE' and model_id is not null)
      or (type = 'WORKFLOW' and model_id is null)
    )
);

-- ★ 幂等键：(client_request_id, owner_id) 唯一，与 V12 同一条口径 ——
--   幂等是「同一个调用方的重试」，不是「全平台一个命名空间」。
create unique index uq_as_client_request on as_assistant (client_request_id, owner_id)
  where client_request_id is not null;

-- 列表 / 广场的默认筛选（契约 AssistantQuery）
create index idx_as_type on as_assistant (type) where deleted_at is null;
create index idx_as_published on as_assistant (published) where deleted_at is null;
create index idx_as_builtin on as_assistant (builtin) where deleted_at is null;
-- ★ 数据范围谓词的主索引：own/shared 档都会先按 owner_id 收窄
create index idx_as_owner on as_assistant (owner_id) where deleted_at is null;
-- 市场排序：人气 / 最新（契约 AssistantQuery.sort）
create index idx_as_popular on as_assistant (call_count desc) where deleted_at is null;
create index idx_as_created on as_assistant (created_at desc) where deleted_at is null;

comment on table as_assistant is
  '应用（智能体）聚合根。第二个 OwnedResource —— 形状照抄 kb_knowledge_base（V12）';
comment on column as_assistant.type is
  'SIMPLE | WORKFLOW。★ 创建后不可改（改成另一种等于换了一个应用）⇒ 更新路径不碰它';
comment on column as_assistant.model_id is
  '对话模型。★ 简单应用必填、编排应用必须为空（模型在各节点里，见 chk_as_model_by_type）';
comment on column as_assistant.owner_id is
  '归属人。创建时由后端从 JWT sub 写入，不接受前端传 —— 否则用户能把应用挂到别人名下';
comment on column as_assistant.visibility is
  '可见性。默认 PRIVATE：新建资源一律收窄（08 §4.4.4）。PUBLIC = 登录用户里人人可见，不等于免登录';
comment on column as_assistant.satisfaction is
  '满意度。★ 口径（0–1 还是 0–100）待第 18 周赞踩落地时定；现在只落列，不参与计算';

-- ────────────────────────── 绑定关系 ──────────────────────────
--
-- ★ 用**子表**而不是 text[] / JSON 列：这两个关系都要能反向查
--   （「哪些应用绑了这个知识库 / 这个提示词」—— 删除保护要用它，
--   04 册的「被引用时禁止删」就是这个查询）。
--   02 §5.1：JSON 列仅用于**不参与查询**的结构化数据。

create table as_assistant_kb
(
  assistant_id      uuid not null references as_assistant (id) on delete cascade,
  knowledge_base_id uuid not null references kb_knowledge_base (id),
  primary key (assistant_id, knowledge_base_id)
);

create index idx_as_kb_kb on as_assistant_kb (knowledge_base_id);

comment on table as_assistant_kb is
  '应用绑定的知识库。用子表而不是数组：删除保护要反查「哪些应用绑了它」';

create table as_assistant_prompt
(
  assistant_id     uuid not null references as_assistant (id) on delete cascade,
  -- ★ 暂不加外键：提示词配置表（prompt-configs）尚未落地（前端该域仍是 404）。
  --   等它建表时再补外键 —— 先加会让本迁移依赖一张不存在的表。
  prompt_config_id uuid not null,
  primary key (assistant_id, prompt_config_id)
);

comment on table as_assistant_prompt is
  '应用使用的提示词模板。★ prompt_config_id 暂不加外键（提示词配置表还没建）';

-- ★★ 关于共享（grants）**本批不建表**：
--   as_assistant_grant 的形状会照抄 kb_knowledge_base_grant（V12 第 98 行起），
--   但它的写入口与判定接线属于「共享」那一批（与知识库共享对话框同一族，T302 的后半）。
--   现在建了也只有一个空表，而实体侧会因此多一处"看起来在判定、其实没有数据"的谎言。
--   ⇒ 归属资源的三件套里，本批落 **owner_id + visibility**；grants 到那段一起落。
--   ★ 这不是"后加代价高"那一类（V12 注释里的 max_classification 才是）：本表是新增表，
--     没有存量数据要回填，晚建只是晚建。
