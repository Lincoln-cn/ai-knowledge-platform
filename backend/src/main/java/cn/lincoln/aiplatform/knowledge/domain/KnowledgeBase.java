package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.Visibility;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 知识库（聚合根）—— 也是第一个 {@link OwnedResource} 落地（{@code 07 §3.1}）。
 *
 * <p>★ <b>ceiling 而不是密级</b>：{@link #maxClassification} 是「这个库<em>允许装</em>的最高密级」（{@code 13 §6}），
 * 不是「谁能看这个库」。两者正交 —— 谁能看是 {@code Role.maxClassification} 的事。 把它们混成一个字段，会让「库很敏感」与「库里装的东西很敏感」再也分不开。
 *
 * <p>★ <b>ownerId 不可变</b>：转让是显式动作，不是改个字段； 创建时由后端从 JWT 的 {@code sub} 写入，<em>不接受前端传</em>（{@link
 * OwnedResource} 类注释）。
 *
 * <p>★ <b>幂等</b>：{@link #clientRequestId} 是创建请求的幂等键。 同一键重复创建返回<em>既有库</em>，而不是「名称已存在」——
 * 建库成功但后续上传失败后， 重试不应被卡死、也不该留下孤儿库。
 */
@Entity
@Table(name = "kb_knowledge_base")
public class KnowledgeBase implements OwnedResource {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** KB-I1：1–30 字。 */
  @Column(name = "name", nullable = false, length = 30)
  private String name;

  /** 分类显示名（契约 {@code category: string}；分类字典随 FR-03-03 后补）。 */
  @Column(name = "category", nullable = false, length = 64)
  private String category;

  /** KB-I2：≤200 字。 */
  @Column(name = "description", length = 200)
  private String description;

  @Column(name = "icon", nullable = false, length = 64)
  private String icon;

  /** BR-03-01：创建时选定，之后不可更改。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "import_type", nullable = false, length = 16)
  private ImportType importType;

  /** 绑定的向量模型（{@code mc_model.id}）。 */
  @Column(name = "embedding_model_id", nullable = false)
  private UUID embeddingModelId;

  /**
   * ceiling（{@code 13 §6}）：这个库允许装的最高密级。
   *
   * <p>★ 必填。缺了它，「文档密级 ≤ ceiling」的入库校验就没有依据 —— 默认值要么全放行要么全拦，都是错的。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "max_classification", nullable = false, length = 16)
  private ClassificationLevel maxClassification;

  /** 停用后不参与检索，数据保留。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 16)
  private Visibility visibility;

  /** 幂等键；null 表示本次创建不参与幂等。 */
  @Column(name = "client_request_id", length = 64)
  private String clientRequestId;

  @Column(name = "document_count", nullable = false)
  private int documentCount;

  @Column(name = "segment_count", nullable = false)
  private int segmentCount;

  @Column(name = "char_count", nullable = false)
  private long charCount;

  /** 已有向量化内容 ⇒ 向量模型被锁定（KB-I4）。 */
  @Column(name = "has_vectorized_content", nullable = false)
  private boolean hasVectorizedContent;

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

  @OneToMany(
      mappedBy = "knowledgeBase",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private List<KnowledgeBaseGrant> grantRows = new ArrayList<>();

  protected KnowledgeBase() {}

  /**
   * 新建知识库。
   *
   * <p>★ {@code visibility} 一律收窄到 {@link Visibility#PRIVATE}（{@code 08 §4.4.4}）： 权限放开容易、收回难。{@code
   * ownerId} 只能是当前用户 —— 调用方从 JWT 的 {@code sub} 取，请求体里的同名字段被忽略。
   */
  public KnowledgeBase(
      UUID id,
      String name,
      String category,
      String description,
      String icon,
      ImportType importType,
      UUID embeddingModelId,
      ClassificationLevel maxClassification,
      UUID ownerId,
      String clientRequestId,
      UUID createdBy) {
    if (name == null || name.isBlank() || name.length() > 30) {
      throw new IllegalArgumentException("知识库名称必须是 1–30 字");
    }
    if (description != null && description.length() > 200) {
      throw new IllegalArgumentException("知识库描述不能超过 200 字");
    }
    if (maxClassification == null) {
      throw new IllegalArgumentException("库的密级上限必填");
    }
    this.id = id;
    this.name = name.trim();
    this.category = category;
    this.description = description;
    this.icon = icon == null ? "" : icon;
    this.importType = importType;
    this.embeddingModelId = embeddingModelId;
    this.maxClassification = maxClassification;
    this.ownerId = ownerId;
    this.clientRequestId = clientRequestId;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
    this.visibility = Visibility.PRIVATE;
    this.enabled = true;
    this.documentCount = 0;
    this.segmentCount = 0;
    this.charCount = 0;
    this.hasVectorizedContent = false;
    this.grantRows = new ArrayList<>();
  }

  // ────────────────────────── OwnedResource ──────────────────────────

  @Override
  public UUID ownerId() {
    return ownerId;
  }

  @Override
  public Visibility visibility() {
    return visibility;
  }

  @Override
  public List<Grant> grants() {
    return grantRows.stream().map(KnowledgeBaseGrant::toGrant).toList();
  }

  // ────────────────────────── 行为 ──────────────────────────

  public void rename(String newName) {
    if (newName == null || newName.isBlank() || newName.length() > 30) {
      throw new IllegalArgumentException("知识库名称必须是 1–30 字");
    }
    this.name = newName.trim();
  }

  public void changeDescription(String desc) {
    if (desc != null && desc.length() > 200) {
      throw new IllegalArgumentException("知识库描述不能超过 200 字");
    }
    this.description = desc;
  }

  public void changeCategory(String category) {
    this.category = category;
  }

  public void changeIcon(String icon) {
    this.icon = icon == null ? "" : icon;
  }

  /** 改 ceiling（{@code 13 §6}）。★ 尚无文档时改是安全的； 有文档后的收紧/放宽校验（越权文档要逐一处置）随文档段补。 */
  public void changeMaxClassification(ClassificationLevel level) {
    if (level == null) {
      throw new IllegalArgumentException("库的密级上限必填");
    }
    this.maxClassification = level;
  }

  public void enable() {
    this.enabled = true;
  }

  public void disable() {
    this.enabled = false;
  }

  public void markDeleted(Instant at, UUID by) {
    this.deletedAt = at;
    this.updatedBy = by;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String category() {
    return category;
  }

  public String description() {
    return description;
  }

  public String icon() {
    return icon;
  }

  public ImportType importType() {
    return importType;
  }

  public UUID embeddingModelId() {
    return embeddingModelId;
  }

  public ClassificationLevel maxClassification() {
    return maxClassification;
  }

  public boolean enabled() {
    return enabled;
  }

  public String clientRequestId() {
    return clientRequestId;
  }

  public int documentCount() {
    return documentCount;
  }

  public int segmentCount() {
    return segmentCount;
  }

  public long charCount() {
    return charCount;
  }

  public boolean hasVectorizedContent() {
    return hasVectorizedContent;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  public void setUpdatedBy(UUID by) {
    this.updatedBy = by;
  }
}
