package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
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

  /**
   * 组装视图。
   *
   * <p>★ 计数**不从实体读**（{@code #35}）：实体上那四个冗余列从来没被写过，卡片因此永远显示 0， 而那不会报错。{@code counts}
   * 是读的时候从库里算出来的实时值（{@code KnowledgeBaseRepository#countsFor}）。
   */
  public static KnowledgeBaseView from(
      KnowledgeBase base, String embeddingModelName, KnowledgeBaseRepository.Counts counts) {
    return new KnowledgeBaseView(
        base.id().toString(),
        base.name(),
        base.category(),
        base.description(),
        base.icon(),
        base.importType(),
        base.embeddingModelId().toString(),
        embeddingModelName == null ? "" : embeddingModelName,
        // ★ 已有向量化内容（或已有文档/分段）⇒ 向量模型被锁定（KB-I4）
        counts.hasVectorizedContent() || counts.documentCount() > 0 || counts.segmentCount() > 0,
        base.enabled(),
        counts.documentCount(),
        counts.segmentCount(),
        counts.charCount(),
        base.createdAt(),
        base.ownerId().toString(),
        base.visibility(),
        base.grants(),
        base.maxClassification());
  }
}
