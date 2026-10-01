package cn.lincoln.aiplatform.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 文档分段（子实体，第 7 周 · 0.2.2 批 2b）—— 文档是聚合根（{@code 02 §5.1}）。
 *
 * <p>★ <b>没有 {@code deletedAt}</b>：软删仅聚合根（{@code 02 §5.1}）。 分段随文档软删级联 ——
 * 给子实体加软删列会制造「文档已删、分段还在」的孤儿态。
 *
 * <p>★ <b>content 与 searchText 分两列</b>（{@code 12 §4.2} 纪律 1）：
 *
 * <ul>
 *   <li>{@link #content} = 原文片段 —— 引用展示时用户看到的必须是干净原文，<strong>前缀绝不进这里</strong>
 *   <li>{@link #searchText} = 结构化前缀 + 分隔 + 正文 —— 向量化输入 + 全文索引源
 * </ul>
 *
 * <p>混成一列的后果：引用展示会把「制度·采购部·报销规定｜第一章」当成正文显示， 用户以为文档里真有这句话。
 *
 * <p>★ <b>contentHash</b>（{@code SEG-I7}）：sha256(文种|部门|标题|章节路径|正文)。 改了前缀组成 ⇒ hash 必须变 ⇒ 重向量判定才有依据。
 *
 * <p>★ <b>embeddingStatus</b> 四态与契约 {@code SegmentEmbeddingStatus} 一字不差。 初值 {@link
 * SegmentEmbeddingStatus#PENDING_EMBEDDING} —— 向量化还没做（那是下一周）。
 */
@Entity
@Table(name = "kb_segment")
public class Segment {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属文档（聚合根）。 */
  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  /** 所属知识库（冗余列，查询时不必每次 join 文档表）。 */
  @Column(name = "kb_id", nullable = false)
  private UUID kbId;

  /** 文档内序号（从 0 起）。 */
  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  /** 分段标题（{@code 12 §3.4}）。 */
  @Column(name = "title", length = 255)
  private String title;

  /** 章节路径 {@code H1 > H2 > H3}。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "title_path")
  private String titlePath;

  /** ★ 原文片段 —— 前缀绝不进这一列（{@code 12 §4.2} 纪律 1）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "content", nullable = false)
  private String content;

  /** ★ 前缀 + 正文 —— 向量化输入 + 全文索引源（{@code 12 §4.2}）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "search_text", nullable = false)
  private String searchText;

  /** {@code SEG-I7}：重向量判定依据（sha256 十六进制 64 字符）。 */
  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "char_count", nullable = false)
  private int charCount;

  @Column(name = "is_table", nullable = false)
  private boolean isTable;

  @Column(name = "is_code", nullable = false)
  private boolean isCode;

  /** L4 硬切标记（{@code 12 §3.2} 规则 4）。 */
  @Column(name = "oversized_risk", nullable = false)
  private boolean oversizedRisk;

  /** 停用后不参与检索。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  /** 向量化状态。初值 {@link SegmentEmbeddingStatus#PENDING_EMBEDDING}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "embedding_status", nullable = false, length = 32)
  private SegmentEmbeddingStatus embeddingStatus;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected Segment() {}

  /**
   * 从分段计划落库。
   *
   * <p>★ {@code searchText} 必须是 {@link Segmenter#plan} 的产物 —— 不许调用方自己再拼一遍前缀 （判据 ④：预览与入库同源）。
   *
   * @param planned 分段计划条目（含前缀拼接与哈希）
   */
  public Segment(UUID id, UUID documentId, UUID kbId, PlannedSegment planned, UUID createdBy) {
    if (id == null || documentId == null || kbId == null) {
      throw new IllegalArgumentException("分段 id / 文档 id / 知识库 id 不能为空");
    }
    if (planned == null) {
      throw new IllegalArgumentException("分段计划不能为空");
    }
    if (planned.content() == null || planned.content().isEmpty()) {
      throw new IllegalArgumentException("分段正文不能为空");
    }
    this.id = id;
    this.documentId = documentId;
    this.kbId = kbId;
    this.ordinal = planned.ordinal();
    this.title = planned.title();
    this.titlePath = planned.titlePath();
    // ★ content 是原文，searchText 是前缀 + 正文 —— 两列各有用途（12 §4.2 纪律 1）
    this.content = planned.content();
    this.searchText = planned.searchText();
    this.contentHash = planned.contentHash();
    this.charCount = planned.charCount();
    this.isTable = planned.isTable();
    this.isCode = planned.isCode();
    this.oversizedRisk = planned.oversizedRisk();
    this.enabled = true;
    // ★ 初值 = 待向量化，不是「已成功」—— 向量化是下一周的事
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
  }

  // ────────────────────────── 行为 ──────────────────────────

  /**
   * 编辑正文（{@code PUT /segments/:id}）。
   *
   * <p>★ 内容改了向量就旧了 ⇒ {@code embeddingStatus} 置回 {@code PENDING_EMBEDDING}（契约 #10）。
   *
   * <p>★ {@code searchText} 与 {@code contentHash} 由调用方（应用层）重算后传入 —— 前缀要用该文档的 文种/部门/名，领域层拿不到文档元数据。
   *
   * @param content 新正文（SEG-I1 已在应用层校验）
   * @param searchText 重算后的前缀 + 正文
   * @param contentHash 重算后的哈希（SEG-I7）
   */
  public void updateContent(String content, String searchText, String contentHash, UUID updatedBy) {
    if (content == null || content.isEmpty()) {
      throw new IllegalArgumentException("分段正文不能为空");
    }
    this.content = content;
    this.searchText = searchText;
    this.contentHash = contentHash;
    this.charCount = content.length();
    // ★ 内容变了向量就旧了 —— 必须重新排队（契约 #10）
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
    this.updatedBy = updatedBy;
  }

  /** 改标题（不动正文 ⇒ 向量不用重算）。 */
  public void updateTitle(String title, UUID updatedBy) {
    this.title = title;
    this.updatedBy = updatedBy;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID documentId() {
    return documentId;
  }

  public UUID kbId() {
    return kbId;
  }

  public int ordinal() {
    return ordinal;
  }

  public String title() {
    return title;
  }

  public String titlePath() {
    return titlePath;
  }

  /** 原文片段（不含前缀）。 */
  public String content() {
    return content;
  }

  /** 前缀 + 正文（向量化输入）。 */
  public String searchText() {
    return searchText;
  }

  public String contentHash() {
    return contentHash;
  }

  public int charCount() {
    return charCount;
  }

  public boolean isTable() {
    return isTable;
  }

  public boolean isCode() {
    return isCode;
  }

  public boolean oversizedRisk() {
    return oversizedRisk;
  }

  public boolean enabled() {
    return enabled;
  }

  public SegmentEmbeddingStatus embeddingStatus() {
    return embeddingStatus;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
