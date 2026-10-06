-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：知识库的检索参数（第 10 周 · 0.3.2）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 契约：`GET|PUT /knowledge-bases/:kbId/retrieval` → `RetrievalParams`
--       `{ mode, threshold, topN, noRefStrategy }`（`frontend/src/api/retrieval.ts`）
--
-- ★ 为什么参数挂在**知识库**上而不是单独一张表：mock 里那句话就是口径 ——
--   「检索参数是知识库的一部分：读与写都先问同一个判定」（`mock/handlers/retrieval.ts`）。
--   挂 KB 顺带让权限判定复用既有的知识库可见性（07 §3.3 硬规矩 1），不必新造一套。
--
-- ★★ 默认值与 mock **一字不差**（`mock/handlers/retrieval.ts` 的 `findParams`）：
--      HYBRID / 0.6 / 5 / REFUSE
--   理由不是"抄一份"：mock 是**行为真源**（切后端时页面一行不改），
--   两边默认值不一致的症状是"演示时好用、部署后行为不同" —— 本项目最贵的那类问题。
--   ★ 另一条同样重要的：**新库读参数要回默认值，不是 404**（mock 的注释写明了这条），
--     所以这四列都 `not null default` —— 建库那一刻它们就有值，不需要应用侧兜底。
--
-- ★ 约束范围与 mock 的校验对齐（`PUT` 那条 + `POST /retrieval/test` 那条）：
--   threshold ∈ [0,1] · topN ∈ [1,20] · mode 三态 · noRefStrategy 两态。
--   ★ 库层也加 check：应用层校验会被"别的入口"绕过（导入、脚本、以后的管理端点），
--     而库层的约束是**任何写入路径都逃不掉**的那一道。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_knowledge_base
  -- 检索模式：向量 / 全文 / 混合（03 §2.1）。默认混合 —— 它内部就是"向量 + 全文先 RRF"（10 §3.2）
  add column retrieval_mode varchar(16) not null default 'HYBRID',
  -- 相似度阈值。★ 顺序是"先阈值后 TopN"，反了会把噪声带进上下文（03 §2.3 校验规则）
  add column retrieval_threshold numeric(3, 2) not null default 0.60,
  -- 取前 N 条
  add column retrieval_top_n integer not null default 5,
  -- 无引用时的策略：拒答 / 自由回答（03 §2.4）
  add column retrieval_no_ref_strategy varchar(16) not null default 'REFUSE';

alter table kb_knowledge_base
  add constraint chk_kb_retrieval_mode
    check (retrieval_mode in ('VECTOR', 'FULLTEXT', 'HYBRID')),
  add constraint chk_kb_retrieval_threshold
    check (retrieval_threshold >= 0 and retrieval_threshold <= 1),
  add constraint chk_kb_retrieval_top_n
    check (retrieval_top_n >= 1 and retrieval_top_n <= 20),
  add constraint chk_kb_no_ref_strategy
    check (retrieval_no_ref_strategy in ('REFUSE', 'FREE_ANSWER'));

comment on column kb_knowledge_base.retrieval_mode is
  '检索模式三态（契约 RetrievalMode）。默认 HYBRID —— 它内部是"向量 + 全文先 RRF"，是 10 §3.2 的推荐默认';
comment on column kb_knowledge_base.retrieval_threshold is
  '相似度阈值。★ 顺序是先阈值后 TopN（03 §2.3）：反过来会取到 10 条都不达标的噪声。换向量模型后要重新校准（10 §5.4）';
comment on column kb_knowledge_base.retrieval_top_n is
  '取前 N 条。范围 1–20，与 mock 的 PUT 校验一致';
comment on column kb_knowledge_base.retrieval_no_ref_strategy is
  '无引用时的策略：REFUSE（严谨场景）/ FREE_ANSWER（开放咨询），03 §2.4';
