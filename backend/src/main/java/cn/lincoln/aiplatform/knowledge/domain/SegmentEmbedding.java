package cn.lincoln.aiplatform.knowledge.domain;

import java.util.UUID;

/**
 * 一个分段的向量（第 8 周 · 0.3.0）—— 向量表的一行。
 *
 * <p>★ {@code segmentId} 就是向量表的<strong>主键</strong>（{@code 02 §vec_embedding}）：一对一， 于是"同一个分段重复写"天然是
 * upsert（{@code 03 §3.3} 的幂等手段）。 事件重投、用户重复点「开始向量化」都不会造成重复向量。
 *
 * <p>★ {@code modelId} 与 {@code dimension} <strong>随行记录</strong>（落进表的 metadata 列）：
 * 换模型后"哪些向量是旧模型生成的、需要重灌"必须是一个可执行的查询，而不是靠记忆（{@code 02} 的原话）。
 *
 * @param segmentId 分段 id（= 向量表主键）
 * @param kbId 所属知识库（过滤用）
 * @param documentId 所属文档（过滤用）
 * @param modelId 生成这份向量的模型（可追溯）
 * @param content 分段原文（框架读它；顺带省掉检索后回表取文本）
 * @param vector 向量值，长度即维度
 */
public record SegmentEmbedding(
    UUID segmentId, UUID kbId, UUID documentId, UUID modelId, String content, float[] vector) {

  public int dimension() {
    return vector == null ? 0 : vector.length;
  }
}
