package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingStatus;

/**
 * 分段视图（契约 {@code Segment}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 视图显式列出每个出网字段，不直接序列化实体 —— 实体上将来会长出内部状态，漏一次就是数据泄漏。
 *
 * <p>★ 契约 {@code Segment.embedding} 的取值是 {@code SegmentEmbeddingStatus} 四态， 与领域枚举同名，避免多一层映射。
 */
public record SegmentView(
    String id,
    String documentId,
    int ordinal,
    String title,
    String content,
    int charCount,
    boolean enabled,
    SegmentEmbeddingStatus embedding) {

  public static SegmentView from(Segment segment) {
    return new SegmentView(
        segment.id().toString(),
        segment.documentId().toString(),
        segment.ordinal(),
        segment.title(),
        segment.content(),
        segment.charCount(),
        segment.enabled(),
        segment.embeddingStatus());
  }
}
