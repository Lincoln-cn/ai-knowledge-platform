package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.Visibility;
import java.time.Instant;
import java.util.List;

/**
 * 知识库视图（契约 {@code KnowledgeBase}，{@code frontend/src/api/knowledge.ts}）。
 *
 * <p>★ 视图显式列出每个出网字段，不直接序列化实体 —— 实体上将来会长出内部状态（如存储键、失败原因）， 漏一次就是数据泄漏。
 *
 * <p>★ {@code ownerId} / {@code visibility} / {@code grants} 由后端写入并回传，
 * <strong>不是请求字段</strong>（{@code 07 §六} 阶段 1：契约只加可选字段）。
 *
 * <p>★ {@code maxClassification} 是 ceiling（{@code 13 §6}）：这个库允许装的最高密级。
 */
public record KnowledgeBaseView(
    String id,
    String name,
    String category,
    String description,
    String icon,
    ImportType importType,
    String embeddingModelId,
    String embeddingModelName,
    boolean embeddingLocked,
    boolean enabled,
    int documentCount,
    int segmentCount,
    long charCount,
    Instant createdAt,
    String ownerId,
    Visibility visibility,
    List<Grant> grants,
    ClassificationLevel maxClassification) {

  public static KnowledgeBaseView from(KnowledgeBase base, String embeddingModelName) {
    return new KnowledgeBaseView(
        base.id().toString(),
        base.name(),
        base.category(),
        base.description(),
        base.icon(),
        base.importType(),
        base.embeddingModelId().toString(),
        embeddingModelName == null ? "" : embeddingModelName,
        // ★ 已有向量化内容 ⇒ 向量模型被锁定（KB-I4）。用「已有文档/分段」作代理 ——
        //   向量化内容一定从文档来，反过来不必成立（文档还在加工中）
        base.hasVectorizedContent() || base.documentCount() > 0 || base.segmentCount() > 0,
        base.enabled(),
        base.documentCount(),
        base.segmentCount(),
        base.charCount(),
        base.createdAt(),
        base.ownerId().toString(),
        base.visibility(),
        base.grants(),
        base.maxClassification());
  }
}
