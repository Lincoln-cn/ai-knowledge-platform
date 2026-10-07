-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：知识库的查询理解参数（第 12 周 · 0.4.0）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 契约：`RetrievalParams` 新增三个**可选**字段（`10 §8.1`）——
--       `{ queryCount?, decomposeEnabled?, maxSubQuestions? }`
--
-- ★ 为什么是"加可选字段"而不是改形状：前端契约是冻结的（`api/identity.ts:46-47` 那条纪律），
--   只加可选字段 ⇒ 老前端不读也照常工作。库层对应地给 `not null default`，
--   于是"新库读参数"和 V20 的四列一样**回默认值而不是 404**。
--
-- ★★ 默认值必须与三处一致，缺一处就是那个老毛病（"演示时好用、部署后不一样"）：
--      库层（本迁移）· 后端 `HitTestQuery.queryCountOrDefault` 等三个方法 ·
--      mock 的 `findParams`（`frontend/src/mock/handlers/retrieval.ts`）· 前端控件初值
--   取值来自 `10 §6.2`：queryCount **3** · maxSubQuestions **3** · decomposeEnabled **true**。
--
-- ★ 为什么 decomposeEnabled 默认 **true**：多意图问题本来就该被拆开，"默认不做"等于把这一层
--   功能关在配置里 —— 而它正是这一版要交付的东西。
--
-- ★ 上限为什么是 5 而不是 3：默认给 3（推荐值），但允许调到 5（`10 §6.2` 的"上限"一列）。
--   ★ `queryCount` 允许 **0**：0 = 不做查询理解（直接用原始问题）—— 这是一个**合法配置**，
--   不是"没填"。库层用 `>= 0` 的 check 把这件事写明确，免得后人"顺手"把它收紧成 1。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_knowledge_base
  -- 查询理解的条数：0 = 不做，1 = 只改写，≥2 = 改写 + 扩展（10 §3.1.1）
  add column retrieval_query_count smallint not null default 3,
  -- 多问题分解开关（关掉 = 10 §6.3 的 D2 形状：子问题合并为一个）
  add column retrieval_decompose_enabled boolean not null default true,
  -- 子问题数上限（> 上限按顺序截断并记事件，10 §3.3.2）
  add column retrieval_max_sub_questions smallint not null default 3;

alter table kb_knowledge_base
  add constraint chk_kb_retrieval_query_count
    check (retrieval_query_count >= 0 and retrieval_query_count <= 5),
  add constraint chk_kb_retrieval_max_sub_questions
    check (retrieval_max_sub_questions >= 1 and retrieval_max_sub_questions <= 5);

comment on column kb_knowledge_base.retrieval_query_count is
  '查询理解产出多少条查询（10 §8.1）：0 = 不做这一步，1 = 只改写，默认 3 = 改写 + 扩展。上限 5（10 §6.2）';
comment on column kb_knowledge_base.retrieval_decompose_enabled is
  '是否把多意图问题拆成子问题。默认 true —— 关掉等价于 10 §6.3 的 D2（去掉分解）';
comment on column kb_knowledge_base.retrieval_max_sub_questions is
  '子问题数上限，默认 3、上限 5。超出按子问题顺序截断并记事件（10 §3.3.2：不要静默）';
