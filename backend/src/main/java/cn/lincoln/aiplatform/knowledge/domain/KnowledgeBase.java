package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

  // ────────────────────────── 检索参数（第 10 周 · 0.3.2）──────────────────────────
  // ★ 为什么挂在库上：mock 里那句话就是口径 ——「检索参数是知识库的一部分：读与写都先问同一个判定」
  //   （`mock/handlers/retrieval.ts`）。挂 KB 顺带让权限与可见性判定复用知识库那一套（07 §3.3）。
  // ★ 字段初始化必须有：库层这四列是 `not null default`，而 Hibernate 插入时会带上实体里的值 ——
  //   不初始化就会显式插 NULL、直接撞 NOT NULL 约束（"库里有默认值"救不了它）。

  /** 检索模式（契约 {@code RetrievalMode}）。★ 默认 HYBRID，与 mock 的默认值一字不差。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "retrieval_mode", nullable = false, length = 16)
  private RetrievalMode retrievalMode = RetrievalMode.HYBRID;

  /** 相似度阈值。★ 顺序是"先阈值后 TopN"，反了会把噪声带进上下文（{@code 03 §2.3}）。 */
  @Column(name = "retrieval_threshold", nullable = false, precision = 3, scale = 2)
  private BigDecimal retrievalThreshold = new BigDecimal("0.60");

  /** 取前 N 条。范围 1–20（与 mock 的 PUT 校验一致，库层也有 check）。 */
  @Column(name = "retrieval_top_n", nullable = false)
  private int retrievalTopN = 5;

  /** 无引用时的策略（契约 {@code NoRefStrategy}）。默认 REFUSE。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "retrieval_no_ref_strategy", nullable = false, length = 16)
  private NoRefStrategy retrievalNoRefStrategy = NoRefStrategy.REFUSE;

  // ── 查询理解（第 12 周 · 0.4.0，契约的三个**可选**字段，10 §8.1）──

  /** 查询理解条数：{@code 0} = 不做，{@code 1} = 只改写，≥2 = 改写 + 扩展。默认 3。 */
  @Column(name = "retrieval_query_count", nullable = false)
  private short retrievalQueryCount = 3;

  /** 是否拆子问题。★ 默认 true —— 关掉等于把这一版要交付的能力关在配置里。 */
  @Column(name = "retrieval_decompose_enabled", nullable = false)
  private boolean retrievalDecomposeEnabled = true;

  /** 子问题数上限。默认 3、上限 5（{@code 10 §6.2}）。 */
  @Column(name = "retrieval_max_sub_questions", nullable = false)
  private short retrievalMaxSubQuestions = 3;

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

  /**
   * 改检索参数（{@code PUT /knowledge-bases/:id/retrieval}）。
   *
   * <p>★ 契约是 <strong>Partial</strong>（前端只传改动的字段）⇒ 每个参数只在非空时覆盖。 范围校验在这里做一次、库层的 check
   * 再做一次：应用层给可读原因，库层保证"任何写入路径都逃不掉"。
   */
  public void changeRetrievalParams(
      RetrievalMode mode,
      Double threshold,
      Integer topN,
      NoRefStrategy noRefStrategy,
      Integer queryCount,
      Boolean decomposeEnabled,
      Integer maxSubQuestions) {
    if (mode != null) {
      this.retrievalMode = mode;
    }
    if (threshold != null) {
      if (threshold < 0 || threshold > 1) {
        throw new IllegalArgumentException("相似度阈值应在 0～1 之间");
      }
      // ★ 显式定标到 2 位：列是 numeric(3,2)，而 0.605 这种值让 PG 自己舍入不如我们说了算
      this.retrievalThreshold =
          BigDecimal.valueOf(threshold).setScale(2, java.math.RoundingMode.HALF_UP);
    }
    if (topN != null) {
      if (topN < 1 || topN > 20) {
        throw new IllegalArgumentException("TopN 应在 1～20 之间");
      }
      this.retrievalTopN = topN;
    }
    if (noRefStrategy != null) {
      this.retrievalNoRefStrategy = noRefStrategy;
    }
    if (queryCount != null) {
      // ★ 0 是合法值（不做查询理解），所以校验是 `0..5` 而不是 `1..5` —— 把它收紧成 1
      //   会让"关掉这一层"变成一个静默的 400
      if (queryCount < 0 || queryCount > 5) {
        throw new IllegalArgumentException("查询理解条数应在 0～5 之间");
      }
      this.retrievalQueryCount = queryCount.shortValue();
    }
    if (decomposeEnabled != null) {
      this.retrievalDecomposeEnabled = decomposeEnabled;
    }
    if (maxSubQuestions != null) {
      if (maxSubQuestions < 1 || maxSubQuestions > 5) {
        throw new IllegalArgumentException("子问题数应在 1～5 之间");
      }
      this.retrievalMaxSubQuestions = maxSubQuestions.shortValue();
    }
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

  public RetrievalMode retrievalMode() {
    return retrievalMode;
  }

  /** ★ 返 double 而不是 BigDecimal：契约里 threshold 是 number，多一层类型只会让调用方多一次转换。 */
  public double retrievalThreshold() {
    return retrievalThreshold.doubleValue();
  }

  public int retrievalTopN() {
    return retrievalTopN;
  }

  public NoRefStrategy retrievalNoRefStrategy() {
    return retrievalNoRefStrategy;
  }

  /** 查询理解条数（{@code 0} = 不做）。 */
  public int retrievalQueryCount() {
    return retrievalQueryCount;
  }

  public boolean retrievalDecomposeEnabled() {
    return retrievalDecomposeEnabled;
  }

  public int retrievalMaxSubQuestions() {
    return retrievalMaxSubQuestions;
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
