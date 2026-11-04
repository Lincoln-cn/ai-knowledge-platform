-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：补「查询理解」三列的取值范围约束（#297 · 0.4.9）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★★ 为什么是"补约束"而不是"加列"：这三列早在 `V29__assistant_app.sql:49-51` 就建好了
--   （`retrieval_query_count smallint not null default 3` / `retrieval_decompose_enabled
--   boolean not null default true` / `retrieval_max_sub_questions smallint not null default 3`），
--   但 V29 的 CHECK 只护住 type / visibility / retrieval_mode / no_ref_strategy /
--   name_length / threshold / model_by_type —— 查询理解这三项**一个都没有**。
--   而 knowledge 侧对称位置是齐的（`V23` 的 `chk_kb_retrieval_query_count` /
--   `chk_kb_retrieval_max_sub_questions`）⇒ 两个表的口径不一致：同一个越界值
--   （例如 query_count = 6）在知识库上会被库层拦下，在应用上却能落库、读回才被应用层拦。
--
-- ★ 约束范围与 `knowledge/V23` 逐字同构（chk_as_* 对 chk_kb_*）：
--   query_count 0..5（**0 是合法值** = 不做查询理解，见 V23 文件头）、max_sub_questions 1..5。
--   三处同值（库层 · `RetrievalParams` 紧凑构造器 · `AssistantService` 校验），不一致的症状是
--   "界面能填、保存却静默变值"，而错误出现在问答链路上、离配置很远。
--
-- ★ 迁移号接在 V66 之后（全局唯一；`locations: classpath:db/migration` 是同一目录树）。
-- ════════════════════════════════════════════════════════════════════════════

alter table as_assistant
  -- ★ 库层也加约束：应用层校验会被"别的入口"绕过（导入、脚本、以后的管理端点），
  --   而库层的 check 是**任何写入路径都逃不掉**的那一道（与 V20 / V23 / V65 同一条理由）。
  add constraint chk_as_retrieval_query_count
    check (retrieval_query_count >= 0 and retrieval_query_count <= 5),
  add constraint chk_as_retrieval_max_sub_questions
    check (retrieval_max_sub_questions >= 1 and retrieval_max_sub_questions <= 5);

comment on column as_assistant.retrieval_query_count is
  '查询理解产出多少条查询（10 §8.1）：0 = 不做这一步，1 = 只改写，默认 3 = 改写 + 扩展。上限 5（10 §6.2）';
comment on column as_assistant.retrieval_decompose_enabled is
  '是否把多意图问题拆成子问题。默认 true —— 关掉等价于 10 §6.3 的 D2（去掉分解）';
comment on column as_assistant.retrieval_max_sub_questions is
  '子问题数上限，默认 3、上限 5。超出按子问题顺序截断并记事件（10 §3.3.2：不要静默）';
