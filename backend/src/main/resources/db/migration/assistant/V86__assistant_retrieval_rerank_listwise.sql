-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：应用的「LLM listwise 重排」一格（用户 2026-11-08 要求）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 用户原话：「LLM listwise 不默认开启，既然已经做了，就做成配置，在应用配置 和
-- 对话配置中都可以对不同的对话进行配置」。
--
-- 三档语义（`null` 是第三档，所以列必须可空）：
--   · `null`  = 跟随全局（`app.retrieval.rerank.listwise-enabled`，默认关）
--   · `true`  = 这个应用打开
--   · `false` = 这个应用强制关（即使全局开着）
--
-- ★ 为什么不做成 `not null default false`：那样"没配过"与"显式关"就无法区分，
--   全局开关打开后所有应用都会被静默改写行为 —— 这正是 `#283` 时期
--   "应用配了、问答不用"那类假象的反面。
--
-- ★ 迁移号必须**全局唯一**（`locations: classpath:db/migration` 是同一个目录树）。
--   本文件第一版写成 V70，被 `assistant/V70__knowledge_document_parse_expected_at.sql`…
--   （实际占位的是 `knowledge/V70__knowledge_document_parse_expected_at.sql`）撞了 ⇒
--   Flyway 启动即报 `Found more than one migration with version 70`，整个 Spring 上下文起不来
--   （`AssistantImportHttpTest` 的 82 秒超时就是这么来的 —— 看起来像测试挂了，其实是迁移号撞车）。
--   本项目已有 V54 / V29 两次同族事故，这是第三次：**加迁移前必须先扫全局最大号**，
--   而且不能只看自己模块的目录。
-- ════════════════════════════════════════════════════════════════════════════

alter table as_assistant
  -- null = 跟随全局；true/false = 该应用显式打开/关闭
  add column retrieval_rerank_listwise boolean;

comment on column as_assistant.retrieval_rerank_listwise is
  'LLM listwise 重排（用户 2026-11-08）。null=跟随全局 app.retrieval.rerank.listwise-enabled（默认关）；'
  'true=本应用打开；false=本应用强制关。默认关的理由见 RerankProperties 类注释（单条 3.12s，破 B4）';
