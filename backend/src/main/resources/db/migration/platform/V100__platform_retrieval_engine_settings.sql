-- ════════════════════════════════════════════════════════════════════════════
-- platform 模块：检索引擎的运行时开关（0.8.4 · 检索配置页）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 契约：`GET|PUT /retrieval/settings` → `RetrievalEngineSettings`
--       `{ queryUnderstandingEnabled, queryUnderstandingRetry, rerankEnabled,
--          rerankTopK, rerankForwardBatch, listwiseEnabled, listwiseTopK, updatedAt }`
--
-- ★★ 为什么这几格必须落库，而不是继续留在 `application.yml`：
--   它们**已经在界面上被读数引用**了 —— `ROADMAP §11.45.1` 的 HNSW 读数、`§11.45.2`
--   的四组 A/B（窗口 / 批大小 / 重试），以及 `#342` 的失败率。用户读到"窗口 20 会让
--   单跳从 14.3s 涨到 19s"，却只能改环境变量重启后端才能试 —— 那等于读数只许看不许动。
--   `V65__knowledge_base_retrieval_multi_hop.sql` 已经把这条纪律写死过一次：
--   **「凡是界面上能开、读数能看到的策略开关，都必须在持久化的检索参数里有格子」**。
--   0.8.x 把 QU / 重试 / 重排窗口 / 批大小变成了可读数的旋钮，它们今天**没有格子**。
--
-- ★ 为什么是**单行全局表**而不是挂在知识库上：
--   这四格改的是**引擎行为**（一次查询理解要不要走、重排要不要跑、跑多宽），不是
--   "某个库该怎么检索"。挂在 KB 上会让 5 个库有 5 套引擎参数，而其中的成本与延迟
--   是**整机共享**的 —— 那种粒度会让"谁把机器拖慢了"无法回答。
--
-- ★ 为什么不做成 `@ConfigurationProperties` + `@RefreshScope`：
--   `RerankProperties` / `RetrievalBudgetProperties` 是 record（构造即校验、不可变），
--   全仓 15 处引用它们的构造器签名。加 refresh 作用域要么改全部签名，要么让
--   "值的来源"在运行期从 yml 变成"某个被刷新的代理"—— 后者会让测试注入的假值
--   与生产读到的值走两条路。这里改成：**库是运行时真源，yml 只在库读不到时兜底**
--   （读不到会打 WARN 并说清用了哪套值，见 `RetrievalEngineSettingsStore`）。
--
-- ★ 为什么"批大小 0"是合法值：
--   `RerankProperties.forwardBatch` 的约定是「0 = 整个窗口一次前向」。库层直接用同一
--   语义，不发明第二种表示；约束写成 `0..100` 而不是 `1..100`。
-- ════════════════════════════════════════════════════════════════════════════

create table pf_retrieval_settings (
  -- 单行表：第二行插不进去（与 `cv_chat_settings` 同一个手法，不靠"约定只有一行"）
  id boolean primary key check (id),
  -- 查询理解（问题改写）总开关。关掉 = 每轮少一次 LLM 调用，但所有 B1/B3 读数都会变成
  -- "没改写"的口径（`#342` 记过：这台机器上曾经 10 条里 8 条解析失败，读数一直是没改写的）
  query_understanding_enabled boolean not null default true,
  -- 解析失败是否重试一次。实测关掉省 1.4s 中位延迟、掉 1.1 点 B3 ⇒ 是取舍不是白拿
  query_understanding_retry boolean not null default true,
  -- cross-encoder 重排开关。关掉 = 省 4.74s，B3 −5 点（20 条子集口径）
  rerank_enabled boolean not null default true,
  -- 重排窗口：送进 cross-encoder 的条数。窗口 10 → 20 实测 B1 +5 点、B3 不动、耗时翻倍
  rerank_top_k integer not null default 10 check (rerank_top_k between 1 and 50),
  -- 前向批大小：0 = 整个窗口一次算完。批内按最长补齐，快慢取决于长度分布 ⇒ 写死任何值都是猜
  rerank_forward_batch integer not null default 5 check (rerank_forward_batch between 0 and 100),
  -- LLM listwise 仲裁（默认关：3.12s/条，会破 B4 的 10s 单跳线）
  listwise_enabled boolean not null default false,
  listwise_top_k integer not null default 5 check (listwise_top_k between 1 and 20),
  updated_at timestamptz,
  updated_by uuid
);

-- 默认行与 `application.yml` 的默认值**一字不差**（`app.retrieval.rerank.enabled=true`、
-- `top-k=10`、`forward-batch=5`、`listwise-enabled=false`、`listwise-top-k=5`、
-- `retrieval.query-understanding-enabled=true`、`query-understanding-retry-on-parse-failure=true`）。
-- 理由同 `V20`：两边默认值不一致的症状是"演示时好用、部署后行为不同"。
insert into pf_retrieval_settings (id) values (true);

comment on table pf_retrieval_settings is
  '检索引擎的运行时开关（单行）。库是运行时真源，application.yml 只在库读不到时兜底。';
comment on column pf_retrieval_settings.rerank_top_k is
  '重排窗口（送进 cross-encoder 的条数）。窗口 10 → 20 实测 B1 +5、B3 不动、耗时翻倍。';
comment on column pf_retrieval_settings.rerank_forward_batch is
  '前向批大小，0 = 整个窗口一次前向。批内按最长补齐，快慢取决于长度分布。';
comment on column pf_retrieval_settings.query_understanding_retry is
  '解析失败是否重试一次。关掉省 1.4s 中位延迟、掉 1.1 点 B3。';
