package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 分段向量化状态（契约 {@code SegmentEmbeddingStatus}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code 14} 册 §2.1）： 标识符 = 线上值 =
 * 库存值三者同名，与契约四态一字不差。
 *
 * <p>★ 四态对应真实向量化链（{@code 02 §2.2}）： 待向量化 → 向量化中 → 已向量化，外加失败态。 <strong>初值是 {@link
 * #PENDING_EMBEDDING}</strong> —— 分段刚落库时向量化还没做， 不是「已成功」，否则界面会把没向量化的分段显示成可检索。
 *
 * <p>★ {@link #EMBEDDING} 是<strong>编辑后重新向量化</strong>时的过渡态（契约注释 #10）： 内容改了向量就旧了，必须重新排队。
 */
public enum SegmentEmbeddingStatus {
  /** 待向量化（初值）。 */
  PENDING_EMBEDDING,
  /** 向量化中（含编辑后重新向量化的过渡态）。 */
  EMBEDDING,
  /** 已向量化。 */
  EMBEDDED,
  /** 向量化失败。 */
  FAILED
}
