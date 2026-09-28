package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 文档加工状态（契约 {@code DocumentStatus}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code 14} 册 §2.1）： 标识符 = 线上值 =
 * 库存值三者同名。展示文案（「待解析 / 分段中 / …」）只出现在前端 label 映射里。
 *
 * <p>★ 七态对应真实加工链（{@code 02 §2.2} 状态机）： 解析/分段 → 向量化 → 成功，两段各有失败态。 <strong>初值是 {@link
 * #PENDING_PARSE}</strong>（未解析那一态）—— 不是「成功」， 否则界面会把还没加工的文档显示成可检索。
 */
public enum DocumentStatus {
  /** 待解析（初值）。 */
  PENDING_PARSE,
  /** 分段中。 */
  SEGMENTING,
  /** 待向量化。 */
  PENDING_EMBEDDING,
  /** 向量化中。 */
  EMBEDDING,
  /** 成功。 */
  SUCCEEDED,
  /** 分段失败。 */
  SEGMENT_FAILED,
  /** 向量化失败。 */
  EMBEDDING_FAILED
}
