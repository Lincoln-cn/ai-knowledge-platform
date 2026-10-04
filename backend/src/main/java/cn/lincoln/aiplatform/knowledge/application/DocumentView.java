package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.time.Instant;

/**
 * 文档视图（契约 {@code DocumentItem}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 视图显式列出每个出网字段，不直接序列化实体 —— 实体上将来会长出内部状态，漏一次就是数据泄漏。
 *
 * <p>★ {@code docType} / {@code department} / {@code classification} 是批 2
 * 新增的<strong>可选</strong>出网字段（契约只加可选字段， {@code 07 §六} 阶段 1）。它们在后端是建档必填，但契约里先可选 —— 冻结契约不能被后端强制改必填。
 */
public record DocumentView(
    String id,
    String kbId,
    String name,
    String source,
    DocumentStatusView status,
    int segmentCount,
    int processedSegments,
    long charCount,
    boolean enabled,
    String failReason,
    String failHint,
    Instant createdAt,
    Instant updatedAt,
    String docType,
    String department,
    ClassificationLevel classification,
    String parsedContent,
    // ── 第 9 周（0.3.1）新增的**可选**出网字段：扫描件的逐页进度（12 §11.8）──
    //   ★ 契约只加可选字段（07 §六 阶段 1）：老前端不认识它们也不受影响。
    //   ★ 0 = 不涉及 OCR（非 PDF / 纯文本 PDF）⇒ 界面据此不显示进度行，而不是显示一条恒为 0/0 的噪声。
    Integer ocrTotalPages,
    Integer ocrDonePages) {

  /** 契约 {@code DocumentStatus} 的出网形态 —— 与领域枚举同名，避免多一层映射。 */
  public enum DocumentStatusView {
    PENDING_PARSE,
    SEGMENTING,
    PENDING_EMBEDDING,
    EMBEDDING,
    SUCCEEDED,
    SEGMENT_FAILED,
    EMBEDDING_FAILED
  }

  public static DocumentView from(Document document) {
    return new DocumentView(
        document.id().toString(),
        document.kbId().toString(),
        document.name(),
        document.source(),
        DocumentStatusView.valueOf(document.status().name()),
        document.segmentCount(),
        document.processedSegments(),
        document.charCount(),
        document.enabled(),
        document.failReason(),
        document.failHint(),
        document.createdAt(),
        document.updatedAt(),
        document.docType(),
        document.department(),
        document.classification(),
        document.parsedContent(),
        document.ocrTotalPages(),
        document.ocrDonePages());
  }
}
