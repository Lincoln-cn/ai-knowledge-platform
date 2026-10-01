-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：vec_embedding_768 —— 分段的向量存储（第 8 周 · 0.3.0）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 表结构以 Spring AI 的 `PgVectorStore` 为准（02 §vec_embedding）：
--   它的列名在源码里**硬编码、不可配置**（id / content / metadata / embedding），
--   别的字段一律放 metadata。照它建，是为了将来源用框架的向量检索时**不用改表**。
--
-- ★ 表名带维度后缀是**刻意的**：`vector(N)` 是列类型 ⇒ 一张表只能装一个维度（02）。
--   换维度 = 换表，不是 alter 列 —— alter 会把新旧维度的向量混在同一列里，
--   而"这份向量是谁生成的"就没法从表结构上看出来了。
--   当前只有 768：能力探测实测桩服务与本地推理路径都是 768 维。
--   ⚠️ 多维度路由（按 modelId/维度选表）与"按需建表"尚未做 —— 见 CHANGELOG 欠账。
--
-- ★ id = kb_segment.id（一对一）⇒ 重复写入天然可 upsert（03 §3.3 的幂等手段之一）。
--   这也是"事件重投不会造成重复向量"的根据。
--
-- ★ 向量列**不加**校验用的业务字段（模型 id / 维度）→ 它们落在 metadata 里，
--   于是"哪些向量是旧模型生成的、需要重灌"是一个可执行的查询（02 的原话）。
-- ════════════════════════════════════════════════════════════════════════════

-- pgvector 镜像已带；写进来是为了别的环境也能一次迁移到位（幂等）
create extension if not exists vector;

create table vec_embedding_768
(
  -- ★ 值 = kb_segment.id
  id        uuid primary key,
  -- 分段文本（框架读写它；顺带省掉"检索后回表取文本"这一步）
  content   text        not null,
  -- 可过滤字段：kb_id / document_id / model_id / dimension
  metadata  json,
  embedding vector(768) not null
);

-- 近似最近邻索引：★ 参数显式写（框架自建的索引不带 WITH，用的是扩展默认值）
create index idx_vec_768_hnsw
  on vec_embedding_768
  using hnsw (embedding vector_cosine_ops) with (m = 16, ef_construction = 64);

-- 元数据过滤走 jsonpath（框架把 filterExpression 编译成 `metadata::jsonb @@ …`）
create index idx_vec_768_metadata_kb
  on vec_embedding_768 ((metadata::jsonb -> 'kb_id'));

comment on table vec_embedding_768 is
  '分段向量存储（02 §vec_embedding）。★ 列形照 Spring AI PgVectorStore 硬编码的列名，将来接框架检索不用改表';
comment on column vec_embedding_768.id is
  '★ 值 = kb_segment.id（一对一）⇒ 幂等 upsert 的根据（03 §3.3）：事件重投不会造成重复向量';
comment on column vec_embedding_768.metadata is
  'kb_id / document_id / model_id / dimension —— 让"哪些向量是旧模型生成的"成为可执行查询';
