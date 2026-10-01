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
    this.title = clampTitle(planned.title());
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
   * 标题长度上限（{@code 12 §3.4}：无标题块取首个句子截取 ≤256 字）。
   *
   * <p>★ 为什么在这里夹而不是只在分块器里夹：标题来自<strong>三处</strong>（章节标题、无标题块的首句、 表格块的表头首行），任何一处漏夹都会变成一次**插入失败**。
   * 真事故：库列是 {@code varchar(255)}，而分块器的上限是 256 ⇒ 首段稍长的文档一上传就 {@code value too long for type
   * character varying(255)}，整篇建档 500。 夹在<strong>落库的必经之路上</strong>，三处来源就都被覆盖了。
   */
  public static final int MAX_TITLE_LENGTH = 256;

  static String clampTitle(String title) {
    if (title == null) {
      return null;
    }
    return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
  }

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

  /** 改标题（不动正文 ⇒ 向量不用重算）。★ 超长<strong>明确拒绝</strong>：这是用户输入，不该被静默截断。 */
  public void updateTitle(String title, UUID updatedBy) {
    if (title != null && title.length() > MAX_TITLE_LENGTH) {
      throw new IllegalArgumentException(
          "分段标题不能超过 " + MAX_TITLE_LENGTH + " 字（当前 " + title.length() + " 字）");
    }
    this.title = title;
    this.updatedBy = updatedBy;
  }

  // ────────────────────────── 行为：向量化（第 8 周 · 0.3.0） ──────────────────────────

  /**
   * 本段的向量已写入向量表。
   *
   * <p>★ 与 {@code vec_embedding_<维度>} 的一行一一对应（{@code id = segmentId}）。
   * 「写入成功」与「状态已改」是同一件事的两面：只写向量不改状态，重试会重跑全部； 只改状态不写向量，检索会缺内容 —— 两者都看不见。
   */
  public void markEmbedded() {
    this.embeddingStatus = SegmentEmbeddingStatus.EMBEDDED;
  }

  /**
   * 本段向量化失败。
   *
   * <p>★ 失败原因记在<strong>文档级</strong> {@code failReason} 上，分段级只留状态： 一个批次里 3 段超时，可读原因是一样的，逐段复制只会让原因散开。
   * 状态是用来<strong>筛重试范围</strong>的（{@code 03 §3.5}：只重试 {@code embedding_status <> EMBEDDED} 的分段，
   * 代价从分钟级降到秒级）。
   */
  public void markEmbeddingFailed() {
    this.embeddingStatus = SegmentEmbeddingStatus.FAILED;
  }

  /**
   * 退回待向量化（重新向量化时用）。
   *
   * <p>★ 为什么必须显式退回：向量化器只处理 {@code embedding_status <> EMBEDDED} 的分段 （续跑口径，{@code 03 §3.5}）——
   * 不退回的话"重新向量化"会一个分段都不做。
   */
  public void markPendingEmbedding() {
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
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
