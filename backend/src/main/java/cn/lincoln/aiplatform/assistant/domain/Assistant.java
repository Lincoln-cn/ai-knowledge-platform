package cn.lincoln.aiplatform.assistant.domain;

import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p>⚠️ 共享（grants）的<strong>判定已接线</strong>（{@link #grants()} 进 {@code ScopeFilter.canSee} 与列表 SQL 的
 * EXISTS 谓词）；写入口在 {@code AssistantService.replaceGrants} / {@code setVisibility}（{@code #195}
 * 的共享写子项）。 ★ 没有 grants 的旧数据不受影响：{@code visibility} 默认 {@code PRIVATE}，判定侧是 fail-closed 的。
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

  /** 是否开启多跳迭代（{@code #283}）。默认关 —— 开启后每轮多一次模型调用，是成本不是白拿。 */
  @Column(name = "retrieval_multi_hop_enabled", nullable = false)
  private boolean retrievalMultiHopEnabled = false;

  /** 多跳轮数上限（含首轮），1–3。默认 2。 */
  @Column(name = "retrieval_max_hops", nullable = false)
  private short retrievalMaxHops = 2;

  @Column(name = "retrieval_query_count", nullable = false)
  private short retrievalQueryCount = 3;

  /**
   * LLM listwise 重排（用户 2026-11-08：应用配置与对话配置都能改）。
   *
   * <p>三档：{@code null} = 跟随全局（{@code app.retrieval.rerank.listwise-enabled}，默认关）；{@code true} =
   * 本应用打开； {@code false} = 本应用强制关。
   *
   * <p>★ 用包装类型 {@code Boolean} 而不是 {@code boolean}："没配过"与"显式关"必须能区分 —— 否则全局开关一开，
   * 所有应用都被静默改写行为。{@code #294} 的导入端点还会反序列化旧 bundle，那里没有这一项， 基本类型会直接撞 {@code
   * FAIL_ON_NULL_FOR_PRIMITIVES}。
   */
  @Column(name = "retrieval_rerank_listwise")
  private Boolean retrievalRerankListwise;

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

  // ────────────────────────── 触发器（#295 / 0.7.3） ──────────────────────────

  /**
   * 触发方式；{@code null} = 不自动触发。
   *
   * <p>★ 与 {@link #triggerCron} / {@link #triggerNextAt} 要么同时为 null、要么同时有值 —— 库里 {@code
   * chk_as_assistant_trigger_cron} 钉住这条。半配置（有 cron 没 next_at）会让调度器永远 扫不到它，而界面上又显示"已启用定时"。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", length = 16)
  private TriggerType triggerType;

  /** 5 段 UNIX cron（分 时 日 月 周）。 */
  @Column(name = "trigger_cron", length = 64)
  private String triggerCron;

  /**
   * 下次该跑的时刻 —— 调度器唯一的到期判据，也是乐观锁的比对基准。
   *
   * <p>★ 不每次全表扫 cron 现算：那样"到期"无法索引，也没法回答"这一班错过了没有"。
   */
  @Column(name = "trigger_next_at")
  private Instant triggerNextAt;

  /** 上次真的跑起来的时刻（不是"上次扫到"）。排障第一问是"它到底跑过没"。 */
  @Column(name = "trigger_last_at")
  private Instant triggerLastAt;

  /**
   * 服务停机跨过的周期数。
   *
   * <p>★ 每次只补跑一次，这里记被跳过几次 —— 把期间每一次都补跑会在启动瞬间把外部模型打满。
   */
  @Column(name = "trigger_missed", nullable = false)
  private int triggerMissed;

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

  /** 共享授权。★ 子表（可进 EXISTS 谓词），不是 JSON 列 —— 与知识库同一形状。 */
  @OneToMany(
      mappedBy = "assistant",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  private List<AssistantGrant> grantRows = new ArrayList<>();

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
      boolean multiHopEnabled,
      int maxHops,
      UUID updatedBy) {
    this.retrievalMode = mode;
    this.retrievalThreshold = threshold;
    this.retrievalTopN = (short) topN;
    this.retrievalNoRefStrategy = noRefStrategy;
    this.retrievalQueryCount = (short) queryCount;
    this.retrievalDecomposeEnabled = decomposeEnabled;
    this.retrievalMaxSubQuestions = (short) maxSubQuestions;
    if (maxHops < 1 || maxHops > 3) {
      throw new IllegalArgumentException("多跳轮数应在 1～3 之间");
    }
    this.retrievalMultiHopEnabled = multiHopEnabled;
    this.retrievalMaxHops = (short) maxHops;
    this.updatedBy = updatedBy;
  }

  /**
   * 单独设置 LLM listwise 的三档值（用户 2026-11-08）—— 与 {@link #applyRetrieval} 分开。
   *
   * <p>★ 为什么不做成 {@code applyRetrieval} 的第 11 个参数：那个方法有 6 处调用点， 每处都补一个 {@code Boolean} 会让"这次
   * 请求根本没带这一项"（{@code #294} 的导入端点就是这样） 与"显式传 null"混在一起。单独一个 setter 让语义清楚： <b>调了才改，不调就不动</b>。
   *
   * @param value {@code null} = 跟随全局；{@code true} = 打开；{@code false} = 强制关
   */
  public void changeRerankListwise(Boolean value) {
    this.retrievalRerankListwise = value;
  }

  /**
   * 启用定时触发（{@code #295}）。
   *
   * @param cron 已校验过的 5 段 cron（校验在 {@code TriggerCron}，领域只认结果）
   * @param nextAt 第一次该跑的时刻 —— <b>由调用方算好传进来</b>：领域层不解析 cron，它只持有"下次什么时候"
   * @throws IllegalArgumentException 三者缺一（有 cron 没下次时刻的实体存进去就是半配置）
   */
  public void schedule(String cron, Instant nextAt, UUID updatedBy) {
    if (cron == null || cron.isBlank()) {
      throw new IllegalArgumentException("启用定时触发必须给出 cron");
    }
    if (nextAt == null) {
      throw new IllegalArgumentException("启用定时触发必须给出下一次触发的时刻");
    }
    this.triggerType = TriggerType.CRON;
    this.triggerCron = cron.trim();
    this.triggerNextAt = nextAt;
    this.updatedBy = updatedBy;
  }

  /**
   * 关掉定时触发。
   *
   * <p>★ 把三列一起清空（而不是只把 {@code triggerType} 置 null 留着 cron）：留着的那份 cron 会在 下次有人打开界面时看起来像"还配着" ——
   * 用户关掉的意图是"别再跑了"，不是"参数先存着"。
   *
   * <p>★ 连 {@code triggerLastAt} 与 {@code triggerMissed} 一起清：它们描述的是"上一次配置跑得怎么样"（什么时候跑的、中间漏过几轮），
   * 换一份配置之后这两格就属于**另一次约定的历史**。留着的话，重新开启一个 cron 会立刻显示"上次是补跑，中间漏了 3 次" ——
   * 而那是上一次的事（同一类坏味：`#40`"同一状态四处各写一份"，这里是"旧状态挂在新配置上"）。 真正要留的运行历史在 {@code as_workflow_record}
   * 里，不靠这两列。
   */
  public void clearTrigger(UUID updatedBy) {
    this.triggerType = null;
    this.triggerCron = null;
    this.triggerNextAt = null;
    this.triggerLastAt = null;
    this.triggerMissed = 0;
    this.updatedBy = updatedBy;
  }

  /**
   * 记一次真的跑过了：更新 last / next / missed。
   *
   * @param firedAt 这一跑开始的时刻
   * @param nextAt 下次该跑的时刻（按 cron 顺延）
   * @param missed 本次被跳过几个周期（服务停机跨过的轮数；正常情况是 0）
   */
  public void markTriggerFired(Instant firedAt, Instant nextAt, int missed) {
    if (nextAt == null) {
      throw new IllegalArgumentException("跑完必须算出下一次触发的时刻，否则这一跑之后就再也不会触发");
    }
    this.triggerLastAt = firedAt;
    this.triggerNextAt = nextAt;
    this.triggerMissed = Math.max(0, missed);
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
   * 覆盖式写入共享授权（契约 {@code setAssistantGrants} / {@code 07 §8.1}）。
   *
   * <p>★ <strong>覆盖式而不是增量</strong>：共享对话框干的本来就是"这份清单长这样"；增量语义在两个人同时改时不可解释。
   *
   * <p>★ <strong>可见性跟着授权走</strong>：有授权 = {@code SHARED}，清单空 = 回到 {@code PRIVATE}。 不留"SHARED
   * 但一条授权都没有"的中间态 —— 那种应用对谁都不共享，界面上却显示着"已共享"。
   *
   * <p>★ 主体是否存在<strong>不在这里校验</strong>：那要查账号 / 角色 / 部门目录（{@code GrantSubjectPort}）， 属于应用层。
   * 领域对象只负责把"清单长这样"落成状态。
   */
  public void replaceGrants(List<Grant> grants, UUID updatedBy) {
    grantRows.clear();
    if (grants != null) {
      for (Grant grant : grants) {
        grantRows.add(
            new AssistantGrant(
                UUID.randomUUID(),
                this,
                grant.subject().kind(),
                grant.subject().id(),
                grant.actions().toArray(String[]::new),
                grant.expiresAt(),
                grant.canDelegate()));
      }
    }
    this.visibility = grantRows.isEmpty() ? Visibility.PRIVATE : Visibility.SHARED;
    this.updatedBy = updatedBy;
  }

  /**
   * 直接设置可见性（契约 {@code setAssistantVisibility}）。
   *
   * <p>★ 非 {@code SHARED} 时<strong>清掉 grants</strong>：留着旧授权，日后切回 {@code SHARED} 会"复活"一批 谁都不记得给过的共享。
   * 而 {@code SHARED} 且 grants 为空的情形由服务层挡住（否则同上：显示已共享，实际谁都不共享）。
   */
  public void setVisibility(Visibility visibility, UUID updatedBy) {
    this.visibility = visibility;
    if (visibility != Visibility.SHARED) {
      grantRows.clear();
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

  /**
   * 记下创建请求的幂等键（★ 只在创建时写一次，没有改的口子）。
   *
   * <p>为什么单独一个方法而不是进构造器：它是"这次创建请求的标识"，不是应用的属性 —— 构造器已经收 9 个参数了， 再加一个会让调用点更难读；而它确实只在创建路径上出现一次。
   */
  public void recordClientRequestId(String clientRequestId) {
    this.clientRequestId = clientRequestId;
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

  public boolean retrievalMultiHopEnabled() {
    return retrievalMultiHopEnabled;
  }

  public int retrievalMaxHops() {
    return retrievalMaxHops;
  }

  /**
   * LLM listwise 重排的三档值（用户 2026-11-08）。
   *
   * @return {@code null} = 跟随全局；{@code true} = 本应用打开；{@code false} = 本应用强制关
   */
  public Boolean retrievalRerankListwise() {
    return retrievalRerankListwise;
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

  /** 触发方式；{@code null} = 不自动触发（{@code #295}）。 */
  public TriggerType triggerType() {
    return triggerType;
  }

  /** 5 段 cron；未启用时为 {@code null}。 */
  public String triggerCron() {
    return triggerCron;
  }

  /** 下次该跑的时刻；未启用时为 {@code null}。 */
  public Instant triggerNextAt() {
    return triggerNextAt;
  }

  /** 上次真的跑起来的时刻；从没跑过时为 {@code null}。 */
  public Instant triggerLastAt() {
    return triggerLastAt;
  }

  /** 服务停机跨过的周期数。 */
  public int triggerMissed() {
    return triggerMissed;
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
   * 共享授权（{@code 07 §8.1}）：判定侧只认 {@code Grant}，不认 {@link AssistantGrant} 实体。
   *
   * <p>★ 这是 {@code ScopeFilter.canSee} 与列表 SQL 的 EXISTS 谓词共同的真相源 —— 两处若各读一份， 就会出现"详情能看、列表看不到"。
   */
  @Override
  public List<Grant> grants() {
    return grantRows.stream().map(AssistantGrant::toGrant).toList();
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
