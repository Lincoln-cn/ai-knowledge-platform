package cn.lincoln.aiplatform.platform.vector;

import java.util.Optional;
import java.util.UUID;

/**
 * 向量索引端口（跨模块 SPI）—— ★ 只认识「向量 + 元数据 + 维度」，**不认识知识库**。
 *
 * <p>这条边界是刻意的（{@code 01 §3.3}）：切分语义与知识库归属都是调用方的事， 于是换向量库（今天 pgvector、明天独立向量库）不影响上层。端口里唯一的"业务痕迹"是
 * {@link Entry#kbId()}，而它是<strong>元数据</strong>（JSON 里的一列），不是可见性判定 —— "这个库该不该被看见"必须先由 {@code
 * knowledge} 判定完 （{@code 07 §3.3}）。
 *
 * <p>★ <strong>本端口不带相似度检索</strong>（第 11 周挪走的）：密级与启用位这些谓词必须与 {@code embedding <=> ?} 出现在<strong>同一条
 * SQL</strong> 里才谈得上 pre-filter（{@code 13 §三}），而本端口的模块声明里不认识文档与知识库。 所以"带业务谓词的相似度检索"归 {@code
 * knowledge}（{@code SegmentSearchPort.vectorSearch}）， 而"表叫什么、表形是什么"仍由这里提供（{@link #tableFor} /
 * {@link #ensureTable}）。
 *
 * <h3>为什么端口在 {@code platform}、实现在 {@code vectorindex}</h3>
 *
 * <p>矩阵（{@code 01 §三.2}）里 {@code knowledge} <strong>不依赖</strong> {@code vectorindex} ——
 * 设计里负责"写向量"的模块叫 {@code ingestion}，它没有落地，写入实际住在 {@code knowledge}。
 * 而"表名叫什么、表形是什么"这件事<strong>只能有一处</strong>，否则就是 issue #5 那个形状： 一边硬编码 {@code
 * vec_embedding_768}、一边另有说法。所以按本项目既有约定 —— <strong>跨模块 SPI 放 {@code platform}、实现放提供方模块</strong> （同
 * {@code platform.access.AccessScopePort}、{@code platform.model.EmbeddingPort}）—— 端口在这。
 *
 * <h3>它存在的直接原因：issue #5</h3>
 *
 * <p>向量维度是<strong>列类型</strong>（{@code vector(N)}）⇒ 一张表只能装一个维度 （{@code 02
 * §vec_embedding}）。谁都不许把表名写死，否则"换一个不同维度的向量模型"的表现是 <strong>报错</strong>而不是落到新表。因此连"按 id
 * 查/按文档删"这类操作也在这层做 —— 调用方不该知道"这条向量在哪张表"。
 */
public interface VectorIndexPort {

  /**
   * 维度 → 表名（{@code vec_embedding_<dimension>}）。
   *
   * <p>纯函数：不建表、不查库，便于日志与断言。
   */
  String tableFor(int dimension);

  /**
   * 确保该维度的表存在（缺则按 {@code V16} 的形状建，含 HNSW 索引）。
   *
   * <p>★ <strong>幂等</strong>，但它只在<strong>写路径</strong>上被调用（{@link #upsert} 内部已调）。
   * 检索<strong>不建表</strong>：检索是只读事务，在里面跑 DDL 会被 PG 拒绝 （{@code cannot execute CREATE TABLE in a
   * read-only transaction}）； 而且语义上更对 —— 表不存在等于"这个维度还没有任何向量"，那是**空结果**，不是错误。
   */
  void ensureTable(int dimension);

  /** 写入（幂等 upsert：{@code on conflict (id) do update}）。维度取自 {@link Entry#vector()} 的长度。 */
  void upsert(Entry entry);

  /** 按分段 id 取（它跨所有维度表找 —— 调用方不必知道这条向量在哪张表）。 */
  Optional<Entry> findById(UUID segmentId);

  /** 某文档的向量条数（清点时用）。 */
  long countByDocument(UUID documentId);

  void deleteById(UUID segmentId);

  void deleteByDocument(UUID documentId);

  /**
   * 一条向量。
   *
   * @param segmentId 主键（= {@code kb_segment.id}，一对一）
   * @param vector 向量本身 —— ★ 它的长度决定进哪张表（维度是表级属性）
   */
  record Entry(
      UUID segmentId, UUID kbId, UUID documentId, UUID modelId, String content, float[] vector) {}
}
