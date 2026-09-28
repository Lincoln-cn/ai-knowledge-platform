-- ════════════════════════════════════════════════════════════════════════════
-- modelcatalog 模块：ai_model —— 模型资产目录（第 4 周 · 0.1.3）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这张表回答的是「这个模型现在能不能被平台用」——它不是配置快照，而是
--   **带生命周期的资产**。status 的四态（未加载/加载中/已加载/加载失败）与
--   enabled 的启用位是两个正交的维度：
--     · status  = 加载这件事走到哪一步了（界面上的进度与失败原因）
--     · enabled = 平台是否允许业务走这个模型（加载成功自动打开、失败/删除关闭）
--   合成一列的话，「加载成功但被管理员临时停用」就无处表达。
--
-- ★ 不 seed 任何模型行：上游（Ollama/JAiRouter）在部署环境里还不存在，
--   种子数据没有意义，还会让"表是不是空的"这件事在测试里变成噪声。
--   builtin 字段留给「内置不可删」用（下一段端点实现删除守卫时读它）。
--
-- ★ 枚举取值与契约 frontend/src/api/models.ts 的 ModelInfo/UpsertModelBody
--   一字不差，包括中文值（supplier/status）——库/代码/契约三处同一个字面量，
--   少一层映射就少一处可能对不上的地方（与 UserStatus 同理）。
-- ════════════════════════════════════════════════════════════════════════════

create table ai_model
(
  id             uuid         not null,
  -- 界面上的显示名（管理员起的），不是基础模型名
  name           varchar(64)  not null,
  kind           varchar(16)  not null,
  supplier       varchar(16)  not null,
  -- 基础模型名：同一基础模型可注册多份配置（不同 api_base / key）
  base_model     varchar(128) not null,
  api_base       varchar(512),
  -- ★ API key **只存密文**（AES-GCM）+ 随机 IV，两列都是 base64 文本。
  --   明文入库是等保项目的硬伤：库被读走 ≠ 密钥被拿走。
  --   任何视图都不得回传明文（与 UserView 不含 passwordHash 同理）。
  api_key_cipher text,
  api_key_iv     text,
  status         varchar(16)  not null,
  -- 平台启用位。默认 false：刚录入的模型还没加载成功，不应被业务走到
  enabled        boolean      not null default false,
  -- 失败的**可读原因**（如「网关不可达：连接 http://… 超时」），不是「加载失败」四个字
  load_error     varchar(512),
  -- 对话模型：上下文窗口。向量模型恒为 null
  context_window integer,
  -- 向量模型：输出维度。对话模型恒为 null。维度与向量表绑定，改则需全量重灌
  dimension      integer,
  thinking       varchar(16)  not null default 'NONE',
  tools          varchar(16)  not null default 'UNSUPPORTED',
  builtin        boolean      not null default false,
  remark         varchar(512),
  created_at     timestamptz  not null,
  created_by     uuid,
  updated_at     timestamptz  not null,
  updated_by     uuid,
  primary key (id),

  -- ★ check 约束锁住枚举取值（与 iam_role / iam_user 的写法一致）：
  --   非法值在库这一层就进不来，而不是等到某处 switch 漏分支才炸
  constraint chk_ai_model_kind check (kind in ('chat', 'embedding')),
  constraint chk_ai_model_supplier check (supplier in ('本地推理', '外部API')),
  constraint chk_ai_model_status check (status in ('未加载', '加载中', '已加载', '加载失败')),
  constraint chk_ai_model_thinking check (thinking in ('NONE', 'TOGGLEABLE', 'ALWAYS_ON')),
  constraint chk_ai_model_tools check (tools in ('UNSUPPORTED', 'SUPPORTED', 'PARALLEL')),

  -- ★ dimension / contextWindow 的 null 语义也在库层钉住：
  --   对话模型不产出向量 ⇒ dimension 必须为 null；
  --   向量模型没有对话上下文窗口 ⇒ contextWindow 必须为 null。
  --   领域对象同样校验（两边各一道，库这道是最后一道闸）。
  constraint chk_ai_model_chat_dimension check (kind <> 'chat' or dimension is null),
  constraint chk_ai_model_embedding_context check (kind <> 'embedding' or context_window is null)
);

-- 列表页默认按 kind / status 筛选（契约 ModelQuery），为这两个谓词留索引
create index idx_ai_model_kind on ai_model (kind);
create index idx_ai_model_status on ai_model (status);

comment on table ai_model is
  '模型资产目录。status=加载生命周期，enabled=平台启用位，两者正交（见文件头注释）';
comment on column ai_model.api_key_cipher is
  'API key 的 AES-GCM 密文（base64）。★ 明文禁止入库、禁止出现在任何视图里';
comment on column ai_model.api_key_iv is
  'AES-GCM 的随机 IV（base64）。每次加密新生成 —— 同一明文两次加密得到不同密文';
comment on column ai_model.load_error is
  '加载失败的可读原因，必须能照着排查（如「网关不可达：连接 http://… 超时」）';
comment on column ai_model.enabled is
  '平台启用位。加载成功自动置 true、失败/删除置 false；管理员可临时停用而不改 status';
comment on column ai_model.builtin is
  '是否内置。内置模型硬编码不可删，端点删除守卫读它（本段只落字段）';
comment on column ai_model.dimension is
  '向量模型的输出维度。★ 与向量表绑定，改则需全量重灌；对话模型恒为 null';
comment on column ai_model.context_window is
  '对话模型的上下文窗口；向量模型恒为 null';
