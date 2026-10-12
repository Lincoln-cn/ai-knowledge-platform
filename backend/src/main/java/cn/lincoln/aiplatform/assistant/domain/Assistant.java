package cn.lincoln.aiplatform.assistant.domain;

import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 应用（智能体）聚合根 —— 第二个 {@link OwnedResource} 落地（{@code 07 §3.1}）。
 *
 * <p>★ <b>形状照抄 {@code kb_knowledge_base}</b>（{@code V12}）：{@code owner_id + visibility} 两列在这里，
 * 判定侧只认 {@link OwnedResource} 这个接口 ⇒ 可见性判定零复制（{@code 07 §8.4}）。
 *
 * <p>★ <b>{@link #type} 创建后不可改</b>：类里<strong>没有</strong> {@code type(...)} 这种 setter， 而且 {@link
 * #applyConfig} 的入参里也没有它 —— 契约 {@code UpsertAssistantBody} 的注释写着"更新时后端应忽略该字段"， 这里用"压根不接受"
 * 把它落到实处（比"收下但不生效"强：后者会让调用方以为自己改成功了）。
 *
 * <p>★ <b>"简单有模型、编排无模型"</b>：库里 {@code chk_as_model_by_type} 兜一道，构造器里再判一次 —— 领域这道让错误在构造时就说清原因，而不是等到
 * flush 抛一个看不懂的约束异常。
 *
 * <p>★ <b>绑定关系用子表</b>（{@link #kbIds} / {@link #promptIds}）：它们要能<strong>反向查</strong> （"哪些应用绑了它" ——
 * 删除保护要用），而 {@code 02 §5.1} 明确"JSON 列仅用于不参与查询的结构化数据"。 这里用 {@code @ElementCollection}：join 表的增删交给
 * Hibernate，不自己写 SQL。
 *
 * <p>⚠️ 本批 <b>不落共享（grants）</b>：{@link #grants()} 返回空列表，理由见 {@code V29} 文末 ——
 * 共享的写入口与判定接线属于"共享"那批（与知识库共享对话框同一族）。
 */
@Entity
@Table(name = "as_assistant")
public class Assistant implements OwnedResource {

  /** AS-I1：名称 1–30 字。 */
  @Column(name = "name", nullable = false, length = 30)
  private String name;

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "icon", nullable = false, length = 64)
  private String icon = "";

  @Column(name = "description", length = 200)
  private String description;

  /** ★ 创建后不可改（见类注释）。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 16)
  private AssistantType type;

  /**
   * 对话模型（{@code mc_model.id}）：{@link AssistantType#SIMPLE} 必填，{@link AssistantType#WORKFLOW} 必须为空。
   */
  @Column(name = "model_id")
  private UUID modelId;

  @Column(name = "role_prompt", nullable = false, length = 4000)
  private String rolePrompt = "";

  @Column(name = "multi_turn", nullable = false)
  private boolean multiTurn = true;

  @Column(name = "thinking_enabled", nullable = false)
  private boolean thinkingEnabled;

  @Column(name = "prologue", nullable = false, length = 1000)
  private String prologue = "";

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "quick_questions")
  private String[] quickQuestions = new String[0];

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "tags")
  private String[] tags = new String[0];

  // ────────────────────────── 检索参数（与知识库同形）──────────────────────────
  // ★ 字段初始化必须有：库层这几列是 `not null default`，而 Hibernate 插入时会带上实体里的值 ——
  //   不初始化就会把一个 null 写进 not null 列（与 KnowledgeBase 的同一处教训）。

  @Enumerated(EnumType.STRING)
  @Column(name = "retrieval_mode", nullable = false, length = 16)
  private RetrievalMode retrievalMode = RetrievalMode.HYBRID;

  @Column(name = "retrieval_threshold", nullable = false)
  private BigDecimal retrievalThreshold = new BigDecimal("0.60");

  @Column(name = "retrieval_top_n", nullable = false)
  private short retrievalTopN = 5;

  @Enumerated(EnumType.STRING)
  @Column(name = "retrieval_no_ref_strategy", nullable = false, length = 16)
  private NoRefStrategy retrievalNoRefStrategy = NoRefStrategy.REFUSE;

  @Column(name = "retrieval_query_count", nullable = false)
  private short retrievalQueryCount = 3;

  @Column(name = "retrieval_decompose_enabled", nullable = false)
  private boolean retrievalDecomposeEnabled = true;

  @Column(name = "retrieval_max_sub_questions", nullable = false)
  private short retrievalMaxSubQuestions = 3;

  // ────────────────────────── 归属与状态 ──────────────────────────

  /** 内置应用不可编辑对话设定，仅供问答。 */
  @Column(name = "builtin", nullable = false)
  private boolean builtin;

  @Column(name = "published", nullable = false)
  private boolean published;

  /** ★ 创建时由后端从 JWT 的 {@code sub} 写入，不接受前端传（{@link OwnedResource}）。 */
  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 16)
  private Visibility visibility = Visibility.PRIVATE;

  /** 幂等键；null 表示本次创建不参与幂等。 */
  @Column(name = "client_request_id", length = 64)
  private String clientRequestId;

  @Column(name = "call_count", nullable = false)
  private long callCount;

  @Column(name = "token_count", nullable = false)
  private long tokenCount;

  /** ★ 口径待第 18 周赞踩落地时定；现在只落列，不参与计算。 */
  @Column(name = "satisfaction")
  private BigDecimal satisfaction;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
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

  // ────────────────────────── 绑定关系 ──────────────────────────

  /** 绑定的知识库。<b>子表</b>而不是数组：删除保护要反查"哪些应用绑了它"。 */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "as_assistant_kb", joinColumns = @JoinColumn(name = "assistant_id"))
  @Column(name = "knowledge_base_id", nullable = false)
  private Set<UUID> kbIds = new LinkedHashSet<>();

  /** 使用的提示词模板。<b>子表</b>，理由同 {@link #kbIds}。 */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "as_assistant_prompt", joinColumns = @JoinColumn(name = "assistant_id"))
  @Column(name = "prompt_config_id", nullable = false)
  private Set<UUID> promptIds = new LinkedHashSet<>();

  protected Assistant() {}

  /**
   * 新建一个应用。
   *
   * @param type 创建后不可改；简单应用必须有 {@code modelId}、编排应用必须为空
   * @param ownerId 由调用方（服务层）从当前身份取出，<b>不接受前端传</b>
   * @throws IllegalArgumentException 类型与模型的组合非法（与库里 {@code chk_as_model_by_type} 同一条规则）
   */
  public Assistant(
      UUID id,
      String name,
      String icon,
      String description,
      AssistantType type,
      UUID modelId,
      String rolePrompt,
      UUID ownerId,
      UUID createdBy) {
    requireTypeModelPair(type, modelId);
    this.id = id;
    this.name = name;
    this.icon = icon == null ? "" : icon;
    this.description = description;
    this.type = type;
    this.modelId = modelId;
    this.rolePrompt = rolePrompt == null ? "" : rolePrompt;
    this.ownerId = ownerId;
    this.createdBy = createdBy;
  }

  /**
   * 覆盖式更新对话设定 —— ★ <b>入参里没有 {@code type}</b>，这是"创建后不可改"在类型层面的落实。
   *
   * <p>为什么不做成"收下 type 但忽略"：那会让调用方以为自己改成功了（{@code UpsertAssistantBody} 注释里
   * "传了也不生效"的口径由服务层负责"忽略"，而实体这边干脆接受不到）。
   */
  public void applyConfig(
      String name,
      String icon,
      String description,
      UUID modelId,
      String rolePrompt,
      boolean multiTurn,
      boolean thinkingEnabled,
      String prologue,
      List<String> quickQuestions,
      List<String> tags,
      UUID updatedBy) {
    requireTypeModelPair(this.type, modelId);
    this.name = name;
    this.icon = icon == null ? "" : icon;
    this.description = description;
    this.modelId = modelId;
    this.rolePrompt = rolePrompt == null ? "" : rolePrompt;
    this.multiTurn = multiTurn;
    this.thinkingEnabled = thinkingEnabled;
    this.prologue = prologue == null ? "" : prologue;
    this.quickQuestions =
        quickQuestions == null ? new String[0] : quickQuestions.toArray(String[]::new);
    this.tags = tags == null ? new String[0] : tags.toArray(String[]::new);
    this.updatedBy = updatedBy;
  }

  /** 覆盖式写入检索参数（与知识库同形，应用覆盖库上的值）。 */
  public void applyRetrieval(
      RetrievalMode mode,
      BigDecimal threshold,
      int topN,
      NoRefStrategy noRefStrategy,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions,
      UUID updatedBy) {
    this.retrievalMode = mode;
    this.retrievalThreshold = threshold;
    this.retrievalTopN = (short) topN;
    this.retrievalNoRefStrategy = noRefStrategy;
    this.retrievalQueryCount = (short) queryCount;
    this.retrievalDecomposeEnabled = decomposeEnabled;
    this.retrievalMaxSubQuestions = (short) maxSubQuestions;
    this.updatedBy = updatedBy;
  }

  /** 覆盖式写入绑定关系（先清后加，交给 Hibernate 处理子表）。 */
  public void bind(List<UUID> kbIds, List<UUID> promptIds, UUID updatedBy) {
    this.kbIds.clear();
    if (kbIds != null) {
      this.kbIds.addAll(kbIds);
    }
    this.promptIds.clear();
    if (promptIds != null) {
      this.promptIds.addAll(promptIds);
    }
    this.updatedBy = updatedBy;
  }

  /**
   * 发布 / 取消发布。
   *
   * <p>★ 这里<b>不做发布校验</b>：校验（模型是否已加载、知识库是否停用、提示词是否为空）要读别的模块的数据，
   * 属于服务层；实体只负责"状态怎么变"。而"校验不过就不许发布"由服务层在调用它之前拦住 —— 见 {@code assistant.application}（第 15 周判据①）。
   */
  public void publish(boolean value, UUID updatedBy) {
    this.published = value;
    this.updatedBy = updatedBy;
  }

  /** 软删（仅聚合根使用，{@code 02 §5.1}）。 */
  public void markDeleted(UUID updatedBy) {
    this.deletedAt = Instant.now();
    this.updatedBy = updatedBy;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String icon() {
    return icon;
  }

  public String description() {
    return description;
  }

  public AssistantType type() {
    return type;
  }

  public UUID modelId() {
    return modelId;
  }

  public String rolePrompt() {
    return rolePrompt;
  }

  public boolean multiTurn() {
    return multiTurn;
  }

  public boolean thinkingEnabled() {
    return thinkingEnabled;
  }

  public String prologue() {
    return prologue;
  }

  public List<String> quickQuestions() {
    return List.of(quickQuestions);
  }

  public List<String> tags() {
    return List.of(tags);
  }

  public RetrievalMode retrievalMode() {
    return retrievalMode;
  }

  public BigDecimal retrievalThreshold() {
    return retrievalThreshold;
  }

  public int retrievalTopN() {
    return retrievalTopN;
  }

  public NoRefStrategy retrievalNoRefStrategy() {
    return retrievalNoRefStrategy;
  }

  public int retrievalQueryCount() {
    return retrievalQueryCount;
  }

  public boolean retrievalDecomposeEnabled() {
    return retrievalDecomposeEnabled;
  }

  public int retrievalMaxSubQuestions() {
    return retrievalMaxSubQuestions;
  }

  public boolean builtin() {
    return builtin;
  }

  public boolean published() {
    return published;
  }

  public List<UUID> kbIds() {
    return List.copyOf(kbIds);
  }

  public List<UUID> promptIds() {
    return List.copyOf(promptIds);
  }

  public long callCount() {
    return callCount;
  }

  public long tokenCount() {
    return tokenCount;
  }

  public BigDecimal satisfaction() {
    return satisfaction;
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

  /** 幂等键（创建请求用）。 */
  public String clientRequestId() {
    return clientRequestId;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  @Override
  public UUID ownerId() {
    return ownerId;
  }

  @Override
  public Visibility visibility() {
    return visibility;
  }

  /**
   * ★ 本批返回空列表：共享（grants）的表与写入口在"共享"那一批一起落（见 {@code V29} 文末）。
   *
   * <p>这不是"暂时糊一下"：现在返回空是<strong>可判定的真相</strong>（库里确实没有任何 assistant 的 grant）， 且 {@code visibility}
   * 默认 {@code PRIVATE} ⇒ 判定侧是 fail-closed 的（看不见，而不是看见不该看的）。
   */
  @Override
  public List<Grant> grants() {
    return List.of();
  }

  /** 简单有模型、编排无模型（与库里 {@code chk_as_model_by_type} 同一条规则，这里让它早失败）。 */
  private static void requireTypeModelPair(AssistantType type, UUID modelId) {
    if (type == AssistantType.SIMPLE && modelId == null) {
      throw new IllegalArgumentException("简单应用必须绑定一个对话模型");
    }
    if (type == AssistantType.WORKFLOW && modelId != null) {
      throw new IllegalArgumentException("编排应用没有应用级模型（模型在各节点里）");
    }
  }
}
