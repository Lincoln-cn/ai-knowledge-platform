-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：知识库的「多跳迭代」两列（#283 · 0.4.7）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 契约：`GET|PUT /knowledge-bases/:kbId/retrieval` 的 `RetrievalParams` 扩两格 ——
--       `{ mode, threshold, topN, noRefStrategy, multiHopEnabled, maxHops }`
--       （`frontend/src/api/retrieval.ts` 已同步）
--
-- ★★ 为什么这两列必须落到知识库上，而不是只留在命中测试的请求体里：
--   多跳原本**只挂在 `HitTestQuery` 上**（`retrieval/application/HitTestQuery.java`），
--   落库的检索参数里根本没有它 —— 于是用户能在「命中测试」里打开多跳、能亲眼看到
--   多跳带来的新分段，而**真实问答一次都不会跑**。
--   这比"没有这个功能"更糟：它**看起来有**。判据（写进 `RetrievalParams` 类注释）：
--   凡是"界面上能开、读数能看到"的策略开关，都必须在持久化的检索参数里有格子。
--
-- ★ 默认**关**（`false`）：
--   ① 开启后每轮多一次模型调用（`MultiHopService.decide`），是**成本**不是白拿；
--   ② 存量知识库不该因为一次升级而静默改变行为与延迟（B4 首字 ≤10s 的压力在这里）。
--   ⇒ 想付这个成本的人自己打开。
--
-- ★ `max_hops` 默认 2（= 首轮 + 最多再检一轮，`10 §3.4.2`）。
--   库层约束 1..3 与后端 `RetrievalParams.MAX_HOPS_CAP`、`HitTestQuery.MAX_HOPS_CAP` 三处同值 ——
--   不一致的症状是"界面能填 5、后端静默收到 3"，而错误会出现在问答链路上、离配置很远。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_knowledge_base
  -- 是否开启多跳迭代。★ 默认关，理由见文件头
  add column retrieval_multi_hop_enabled boolean not null default false,
  -- 多跳轮数上限（含首轮）。默认 2 = 首轮 + 最多再检一轮
  add column retrieval_max_hops smallint not null default 2;

alter table kb_knowledge_base
  -- ★ 库层也加约束：应用层校验会被"别的入口"绕过（导入、脚本、以后的管理端点），
  --   而库层的 check 是**任何写入路径都逃不掉**的那一道（与 V20 同一条理由）。
  add constraint chk_kb_retrieval_max_hops
    check (retrieval_max_hops >= 1 and retrieval_max_hops <= 3);

comment on column kb_knowledge_base.retrieval_multi_hop_enabled is
  '是否开启多跳迭代（#283）。开启后每轮多一次模型调用；默认关';
comment on column kb_knowledge_base.retrieval_max_hops is
  '多跳轮数上限（含首轮），1..3；默认 2';
