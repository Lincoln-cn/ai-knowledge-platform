-- 「对话设置」：平台级通用会话配置 + 应用级流式覆盖（三层继承的第一、二层）
--
-- ★★ 为什么要有这张表（`#181` 之后的设计缺口）
--
--   在此之前，「通用问答」（`appId` 为空）**没有任何配置入口**：
--     · 用哪个模型 —— `ChatTurnWriter.pickModelId` 里 `app == null` 就走
--       `chatModels.pickChatModel()`，也就是"随便挑一个可用的"；
--     · 流式参数 —— 只有全局 `app.chat.*`（yaml），运行期改不了。
--   而应用级的流式参数**一个字段都没有**（`as_assistant` 只有 multi_turn / thinking_enabled /
--   retrieval_*，全是检索与对话行为，没有超时与输出上限）。
--
--   实测后果（`#181`）：CPU + qwen2.5:1.5b 上长回答 60s 被 idle 判吊死、更早还有 30s 的
--   Spring async 上限先砍；而用户在页面上**无处可调**，只能改 yaml 重启。
--
-- ★ 三层继承（解析顺序，前者覆盖后者）：
--     应用（as_assistant.chat_*，可空）  →  通用会话（本表）  →  全局 yaml（app.chat.*）兜底
--
--   ★ 为什么"通用会话"用一行表而不是又往 yaml 里加键：yaml 改了要重启，
--     而这一层是**运行时设置**（用户要能在页面上改、且改完立刻对新会话生效）。
--   ★ 为什么单行用 `id boolean primary key`：比"固定 UUID 魔法值 + 唯一约束"更难写错 ——
--     `check (id)` 让第二行根本插不进去，而不是靠约定。
create table cv_chat_settings
(
    -- 单行表：只允许一行（id 恒 true）
    id                        boolean     not null default true primary key check (id),
    -- 通用问答默认用的对话模型。★ 空 = 沿用「挑一个可用的」（pickChatModel），保留旧行为
    default_model_id          uuid,
    -- 是否流式输出。★ false ⇒ 一次性返回全文（首字=全文，但不受 idle 影响）
    stream_enabled            boolean     not null default true,
    -- 多久没有字节写出就判吊死（毫秒）。★ 这是**唯一**的超时权威
    idle_timeout_ms           integer     not null default 60000,
    -- 心跳间隔（毫秒）。★ 约束：heartbeat * 3 <= idle（连续丢 2 个心跳仍不该被误判）
    heartbeat_interval_ms     integer     not null default 20000,
    -- 首字延迟预算（毫秒，对应 ROADMAP B4「首字 ≤10s」）。超了就按降级处置（下一批实现）
    first_token_budget_ms     integer     not null default 10000,
    -- 单轮最大输出 tokens。★ 空 = 不限制（交给模型自身）
    max_output_tokens         integer,
    created_at                timestamptz not null default now(),
    created_by                uuid,
    updated_at                timestamptz not null default now(),
    updated_by                uuid,
    constraint chk_chat_settings_idle check (idle_timeout_ms > 0),
    constraint chk_chat_settings_heartbeat check (heartbeat_interval_ms > 0),
    constraint chk_chat_settings_heartbeat_vs_idle check (heartbeat_interval_ms * 3 <= idle_timeout_ms),
    constraint chk_chat_settings_first_token check (first_token_budget_ms > 0),
    constraint chk_chat_settings_max_tokens check (max_output_tokens is null or max_output_tokens > 0)
);

comment on table cv_chat_settings is
    '平台级「通用会话」设置（单行）。★ 三层继承的第一层：应用 as_assistant.chat_* > 本表 > app.chat.* 兜底';
comment on column cv_chat_settings.idle_timeout_ms is
    '多久没字节写出就判吊死。★★ 这是 SSE 唯一的超时权威 —— spring.mvc.async.request-timeout 已设 -1 不设限';

insert into cv_chat_settings (id) values (true);

-- ── 应用级覆盖（全部可空 = 继承通用会话）──
-- ★ 列名统一加 `chat_` 前缀：as_assistant 上已有 retrieval_* 一簇（检索行为），
--   两簇混在一起会被读成同一件事（"检索设置里怎么有超时"）。
alter table as_assistant
    add column if not exists chat_stream_enabled boolean,
    add column if not exists chat_idle_timeout_ms integer,
    add column if not exists chat_heartbeat_interval_ms integer,
    add column if not exists chat_first_token_budget_ms integer,
    add column if not exists chat_max_output_tokens integer;

alter table as_assistant
    add constraint chk_as_chat_idle check (chat_idle_timeout_ms is null or chat_idle_timeout_ms > 0),
    add constraint chk_as_chat_heartbeat check (chat_heartbeat_interval_ms is null or chat_heartbeat_interval_ms > 0),
    add constraint chk_as_chat_first_token check (chat_first_token_budget_ms is null or chat_first_token_budget_ms > 0),
    add constraint chk_as_chat_max_tokens check (chat_max_output_tokens is null or chat_max_output_tokens > 0);

comment on column as_assistant.chat_idle_timeout_ms is
    '应用级空闲超时（毫秒）。★ 空 = 继承 cv_chat_settings.idle_timeout_ms';
