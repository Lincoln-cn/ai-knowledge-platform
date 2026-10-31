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
 *
 * <p>★ 自 {@code T300}（{@code 07 §11.2}）起，{@code department} 换成 {@code orgId}：出网的是 <strong>责任部门的
 * id</strong>，不再是自由字符串。要给人看部门名就由前端拿 {@code orgId} 去组织树 （{@code GET /api/v1/orgs}）查 ——
 * 与密级/文种走字典渲染是同一套模式。★ 可变、可重名的展示名不许 进强身份位置（它同时进召回前缀与 {@code content_hash}）。
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
    String orgId,
    ClassificationLevel classification,
    // ★ 标签（`#26`）：与密级同属**批级元数据**，但它此前只写在库与谓词里，**没进视图** ——
    //   于是页面既显示不了、也改不了（改之前读不到现值，改之后看不到结果）。
    //   缺失的表现是"后端支持标签、界面上完全没有"，正是 `#79` 第三格要抓的那类。
    java.util.List<String> labels,
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
        // ★ 存量文档（V27 回填不上）的 org_id 为空 ⇒ 出网为 null：界面上显示"未归属"，
        //   而不是编一个部门名出来（那会让"没匹配上"这件事彻底看不见）
        document.orgId() == null ? null : document.orgId().toString(),
        document.classification(),
        document.labels(),
        document.parsedContent(),
        document.ocrTotalPages(),
        document.ocrDonePages());
  }
}
