-- ════════════════════════════════════════════════════════════════════════════
-- modelcatalog 模块：ai_model_provider —— 服务商连接与凭据（C 方案）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 一条记录 = 一套“连接 + 凭据 + 出网边界”，不是“厂家”本身：
--     OpenAI 生产 / OpenAI 测试 / 本地 vLLM 各是一条，换 key 只改这一行。
--
-- ★ 密钥只存 AES-GCM 密文 + IV（与 ai_model.api_key_cipher 同一条纪律），
--    任何视图都不得回传明文；设置/清除密钥走单独端点。
--
-- ★ kind 是协议/预设，egress 是是否出网 —— 两个维度必须分开：
--    自建 vLLM 用 OpenAI 协议，但它是 LOCAL，不能被密级闸门当成外部 API。
-- ════════════════════════════════════════════════════════════════════════════

create table ai_model_provider
(
  id             uuid         not null,
  name           varchar(64)  not null,
  kind           varchar(32)  not null,
  api_base       varchar(512) not null,
  auth_style     varchar(24)  not null default 'BEARER',
  egress         varchar(16)  not null default 'EXTERNAL',
  api_key_cipher text,
  api_key_iv     text,
  enabled        boolean      not null default true,
  remark         varchar(512),
  created_at     timestamptz  not null,
  created_by     uuid,
  updated_at     timestamptz  not null,
  updated_by     uuid,
  primary key (id),

  constraint chk_ai_model_provider_kind check (kind in (
    'OPENAI', 'DEEPSEEK', 'MIMO', 'GLM', 'OLLAMA', 'GATEWAY', 'OPENAI_COMPATIBLE'
  )),
  constraint chk_ai_model_provider_auth check (auth_style in ('BEARER', 'X_API_KEY', 'NONE')),
  constraint chk_ai_model_provider_egress check (egress in ('LOCAL', 'EXTERNAL'))
);

create unique index uq_ai_model_provider_name on ai_model_provider (lower(name));
create index idx_ai_model_provider_kind on ai_model_provider (kind);

comment on table ai_model_provider is
  '服务商连接与凭据（C 方案：默认网关 + 显式直连）。一条记录 = 一套连接，换 key 只改这一行';
comment on column ai_model_provider.kind is
  '协议/预设：OPENAI / DEEPSEEK / MIMO / GLM / OLLAMA / GATEWAY / OPENAI_COMPATIBLE';
comment on column ai_model_provider.auth_style is
  '认证头：BEARER（OpenAI 兼容）/ X_API_KEY（JAiRouter）/ NONE（本地）';
comment on column ai_model_provider.egress is
  '出网边界：LOCAL / EXTERNAL。它是 #72 密级闸门的权威来源，不从 kind 推断';
comment on column ai_model_provider.api_key_cipher is
  'API key 的 AES-GCM 密文（base64）。明文禁止入库、禁止出现在任何视图里';
