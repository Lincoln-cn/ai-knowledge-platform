-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_segment.content_tsv —— 全文检索那一路（第 10 周 · 0.3.2）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么需要它：三模式检索里的「全文」那一路靠它（`03 §2.1`）。用 PG 原生 tsvector
--   ⇒ 不需要额外组件，而且能与向量路**在同一条 SQL 里**做 RRF 融合（`03 §2.2` 说"实现极简"）。
--
-- ★★ 中文的唯一正确姿势：**先分词再进 tsvector**。
--   直接 `to_tsvector('simple', 正文)` 会把**整段中文当成一个 token**（中文没有空格）
--   ⇒ 这一路等于永远检索不到东西，**而且不报错**（`09 §10.2 #3`、`02 §重建字段`）。
--   ★ 实测（本机真库，2026-10-05）：
--       to_tsvector('simple',    '合同违约金')  → '合同违约金':1          ← 整串一个 token
--       to_tsvector('chinese_zh','合同违约金')  → '合同':1 '违约金':2     ← 真分词
--
-- ★ 职责切分（这一条最容易做错，`02 §重建字段` 写明了，本迁移是它的落地）：
--   · **扩展**（`create extension zhparser / vector`）—— **要超级用户** ⇒ 放**镜像 initdb**
--     （`deploy/pg/initdb/10-extensions.sql`），**不放迁移**；
--   · **分词配置**（下面两条）—— **不要超管** ⇒ 放本迁移；
--   · **启动自检**（扩展在不在 + 分词对不对）—— 缺了就是静默失效 ⇒ 见 `FullTextIndex`
--     （应用侧探针 + `/actuator/health` 的 `components.fulltext`）。
--   ★ 这条切分的实际好处：生产环境的应用账号**不必是超级用户**也能把库迁到最新。
--
-- ★ 词典可以加业务词，但 **tsvector 是写入时物化的**（`zhparser#59`）：改词典后必须重建
--  （`update kb_segment set content_tsv = to_tsvector('chinese_zh', search_text)` 或
--   `reindex index idx_segment_tsv`）—— 与"改过分段要重新向量化"是同一类问题，**别指望它自动生效**。
-- ════════════════════════════════════════════════════════════════════════════

create text search configuration chinese_zh (parser = zhparser);

-- ★ mapping 里漏掉某一类 token ⇒ 那一类词进不了 tsvector，而且是**静默**的。
--   `x`（unknown）必须加：自定义词的 token 类型就是它（`02 §重建字段` 的注释点明了这条）。
--   ★ 已知上限（实测，已写成 `deploy/verify_image_pg.py` 里的金丝雀）：SCWS 把「元」标成量词
--     `q`、「每人/每天」标成代词 `r` ⇒ 它们不进 tsvector。查询侧同样进不去 ⇒ **对称、不影响命中**；
--     但**单位与频次不参与匹配**（`12 §835` 警告过"500 元/天 与 500 元/次 会混"）。
alter text search configuration chinese_zh
  add mapping for n, v, a, i, e, l, x with simple;

-- ★ 生成源是 `search_text`（已含结构化前缀），**不是** `content`（`12 §4.2` 纪律 1 与 §4.2 的表）
alter table kb_segment
  add column content_tsv tsvector
    generated always as (to_tsvector('chinese_zh', search_text)) stored;

-- GIN：tsvector 的标准索引。命中测试页要在毫秒级出结果，没有它每次都是全表扫。
create index idx_segment_tsv on kb_segment using gin (content_tsv);

comment on column kb_segment.content_tsv is
  '全文检索列（03 §2.1）。生成源是 search_text（含结构化前缀）而不是 content（12 §4.2）。★ 中文靠 zhparser 的 chinese_zh 配置：扩展在镜像 initdb、配置在本迁移。改词典后必须重建，它不会自动重算';
