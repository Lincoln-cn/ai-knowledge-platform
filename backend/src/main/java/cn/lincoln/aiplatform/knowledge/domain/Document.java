package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
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
 * 文档（聚合根）—— 第 5 周 · 0.2.0 批 2：建档元数据。
 *
 * <p>★ <b>建档必填三项</b>（{@code DOC-I6} / {@code 12 §5.1}）： {@link #docType}（文种）· {@link
 * #department}（责任部门）· {@link #classification}（密级）。 这三项分别决定「召回前缀」与「安全过滤」，<strong>不能有默认值</strong> ——
 * 密级默认「未知」要么全放行（泄密）要么全拦（不可用）。
 *
 * <p>★ <b>ceiling 校验</strong>（{@code 13 §6}）： 文档密级必须不高于所属库的 {@code maxClassification}。
 * 判定在建档用例层做（要给出可读原因）， 这里只负责把值存住。
 *
 * <p>★ <b>可见性继承库</strong>：文档不是 {@code OwnedResource} —— 谁能看它由所属知识库决定。 判定走库那一套（{@code
 * KnowledgeBaseService.requireVisible}），<strong>不另写一套</strong>。
 *
 * <p>★ 解析/分块是第 6–7 周的事：{@link #charCount} / {@link #segmentCount} / {@link #processedSegments}
 * 本批只落列、恒为 0。
 */
@Entity
@Table(name = "kb_document")
public class Document {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属知识库。可见性继承它 —— 库不可见 ⇒ 其文档也不可见。 */
  @Column(name = "kb_id", nullable = false)
  private UUID kbId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /** 来源（契约 {@code DocumentItem.source}）。建档路径是本地上传，取 {@code local}。 */
  @Column(name = "source", nullable = false, length = 32)
  private String source;

  /** 文种（合同/制度/报销规定…）。★ 建档必填。 */
  @Column(name = "doc_type", nullable = false, length = 32)
  private String docType;

  /** 责任部门。★ 建档必填。 */
  @Column(name = "department", nullable = false, length = 64)
  private String department;

  /** 文档密级。★ 建档必填；入库时必须 ≤ 库的 ceiling。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "classification", nullable = false, length = 16)
  private ClassificationLevel classification;

  /** 加工状态。初值 {@link DocumentStatus#PENDING_PARSE}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private DocumentStatus status;

  /** 失败的可读原因（{@code DOC-I2}）。状态为 {@code *FAILED} 时必填。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "fail_reason")
  private String failReason;

  /** 失败时的下一步建议（{@code 08 §4.1}）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "fail_hint")
  private String failHint;

  /** 总字符数。本批恒 0，随加工链回填。 */
  @Column(name = "char_count", nullable = false)
  private long charCount;

  /** 总分段数。本批恒 0。 */
  @Column(name = "segment_count", nullable = false)
  private int segmentCount;

  /** 已处理分段数（真实进度）。本批恒 0。 */
  @Column(name = "processed_segments", nullable = false)
  private int processedSegments;

  /** 停用后不参与检索。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

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

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected Document() {}

  /**
   * 建档。
   *
   * <p>★ 三项必填在这里再校验一遍（应用层已经拦过）： 领域不变量不能只靠调用方自觉 —— 库层的 NOT NULL 是最后一道闸，这里是给人看的那道。
   *
   * @param source 来源；null 时取 {@code local}（本地上传建档）
   */
  public Document(
      UUID id,
      UUID kbId,
      String name,
      String source,
      String docType,
      String department,
      ClassificationLevel classification,
      UUID createdBy) {
    if (id == null || kbId == null) {
      throw new IllegalArgumentException("文档 id 与所属知识库 id 不能为空");
    }
    if (name == null || name.isBlank() || name.length() > 255) {
      throw new IllegalArgumentException("文档名称必须是 1–255 字");
    }
    // ★ DOC-I6：三项缺一不可，且没有默认值
    if (docType == null || docType.isBlank()) {
      throw new IllegalArgumentException("文种必填");
    }
    if (department == null || department.isBlank()) {
      throw new IllegalArgumentException("责任部门必填");
    }
    if (classification == null) {
      throw new IllegalArgumentException("密级必填");
    }
    this.id = id;
    this.kbId = kbId;
    this.name = name.trim();
    this.source = source == null || source.isBlank() ? "local" : source.trim();
    this.docType = docType.trim();
    this.department = department.trim();
    this.classification = classification;
    // ★ 初值 = 未解析那一态，不是「成功」
    this.status = DocumentStatus.PENDING_PARSE;
    this.charCount = 0;
    this.segmentCount = 0;
    this.processedSegments = 0;
    this.enabled = true;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
  }

  // ────────────────────────── 行为（失败记录；状态机随加工链补全） ──────────────────────────

  /**
   * 记录一次加工失败（{@code DOC-I2}）。
   *
   * <p>★ 失败必须留下<strong>可读原因</strong>——「加载失败」四个字等于让用户停在原地（{@code 08 §4.1}）。
   *
   * @param reason 可读原因（必填）
   * @param hint 下一步建议（可空）
   */
  public void recordFailure(DocumentStatus failedStatus, String reason, String hint) {
    if (failedStatus != DocumentStatus.SEGMENT_FAILED
        && failedStatus != DocumentStatus.EMBEDDING_FAILED) {
      throw new IllegalArgumentException("只有失败态可以记录失败原因");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("失败原因必填 —— 不许静默失败（DOC-I2）");
    }
    this.status = failedStatus;
    this.failReason = reason;
    this.failHint = hint;
  }

  public void markDeleted(Instant at, UUID by) {
    this.deletedAt = at;
    this.updatedBy = by;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID kbId() {
    return kbId;
  }

  public String name() {
    return name;
  }

  public String source() {
    return source;
  }

  public String docType() {
    return docType;
  }

  public String department() {
    return department;
  }

  public ClassificationLevel classification() {
    return classification;
  }

  public DocumentStatus status() {
    return status;
  }

  public String failReason() {
    return failReason;
  }

  public String failHint() {
    return failHint;
  }

  public long charCount() {
    return charCount;
  }

  public int segmentCount() {
    return segmentCount;
  }

  public int processedSegments() {
    return processedSegments;
  }

  public boolean enabled() {
    return enabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }
}
