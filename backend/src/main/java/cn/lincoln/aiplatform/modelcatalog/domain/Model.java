package cn.lincoln.aiplatform.modelcatalog.domain;

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
 * 模型资产（聚合根）。
 *
 * <p>它回答的是「这个模型现在能不能被平台用」——不是配置快照，而是<strong>带生命周期的资产</strong>。
 *
 * <p><b>两个正交维度</b>：
 *
 * <table>
 *   <tr><th>维度</th><th>回答的问题</th></tr>
 *   <tr><td>{@link #status}（四态）</td><td>加载这件事走到哪一步了（界面进度与失败原因）</td></tr>
 *   <tr><td>{@link #enabled}</td><td>平台是否允许业务走这个模型</td></tr>
 * </table>
 *
 * 合成一列的话，「加载成功但被管理员临时停用」就无处表达。
 *
 * <p>★ <b>状态机是这个聚合的重点</b>（契约的四态不是装饰，它们决定了界面能不能真的走全）： {@link #startLoad()} 任意态→加载中； {@link
 * #markLoaded()} 仅 加载中→已加载； {@link #markFailed(String)} 仅 加载中→加载失败。 非法转移一律拒绝并说清原因 ——
 * 不是靠调用方自觉，否则「加载失败直接 markLoaded」这种跳步会在某处悄悄发生， 界面却显示一切正常。
 *
 * <p>JPA 注解直接用在聚合上，理由见 {@code 01 §6.3} 的务实折中（与 {@code identity} 的 {@code User} / {@code Role} 一致）。
 */
@Entity
@Table(name = "ai_model")
public class Model {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 界面上的显示名（管理员起的），不是基础模型名。 */
  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private ModelKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "supplier", nullable = false, length = 16)
  private Supplier supplier;

  /** 基础模型名：同一基础模型可注册多份配置（不同 apiBase / key）。 */
  @Column(name = "base_model", nullable = false, length = 128)
  private String baseModel;

  @Column(name = "api_base", length = 512)
  private String apiBase;

  /**
   * API key 的 AES-GCM 密文（base64）。
   *
   * <p>★ <b>明文禁止落进这个字段、禁止出现在任何视图里</b>（与 {@code UserView} 不含 {@code passwordHash} 同理）： 库被读走 ≠
   * 密钥被拿走。加密与解密是 {@code ApiKeyCipher} 的职责，领域对象只负责把密文保管好 —— 与 {@code User.passwordHash} 同一条纪律。
   */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "api_key_cipher")
  private String apiKeyCipher;

  /** AES-GCM 的随机 IV（base64）。每次加密新生成 —— 同一明文两次加密得到不同密文。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "api_key_iv")
  private String apiKeyIv;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ModelStatus status;

  /** 平台启用位。加载成功自动置 true、失败/删除置 false；管理员可临时停用而不改 status。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  /**
   * 加载失败的<strong>可读原因</strong>。
   *
   * <p>★ 必须是能照着排查的那句话（如「网关不可达：连接 http://… 超时」），不是「加载失败」四个字 —— 后者等于让用户停在原地（{@code 08 §4.1}）。
   */
  @Column(name = "load_error", length = 512)
  private String loadError;

  /** 对话模型：上下文窗口。向量模型恒为 null（check 约束 + 领域校验双重钉住）。 */
  @Column(name = "context_window")
  private Integer contextWindow;

  /** 向量模型：输出维度。对话模型恒为 null。维度与向量表绑定，改则需全量重灌。 */
  @Column(name = "dimension")
  private Integer dimension;

  @Enumerated(EnumType.STRING)
  @Column(name = "thinking", nullable = false, length = 16)
  private ThinkingMode thinking;

  @Enumerated(EnumType.STRING)
  @Column(name = "tools", nullable = false, length = 16)
  private ToolsMode tools;

  /** 是否内置。内置模型硬编码不可删，端点删除守卫读它。 */
  @Column(name = "builtin", nullable = false)
  private boolean builtin;

  @Column(name = "remark", length = 512)
  private String remark;

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

  protected Model() {}

  /**
   * 新建模型。
   *
   * <p>★ 新建一律落 {@link ModelStatus#NOT_LOADED} + {@code enabled=false}：刚录入的资产还没加载成功， 不应被业务走到。
   */
  public Model(
      UUID id,
      String name,
      ModelKind kind,
      Supplier supplier,
      String baseModel,
      String apiBase,
      String apiKeyCipher,
      String apiKeyIv,
      Integer contextWindow,
      Integer dimension,
      ThinkingMode thinking,
      ToolsMode tools,
      boolean builtin,
      String remark,
      UUID createdBy) {
    this.id = id;
    this.name = name;
    this.kind = kind;
    this.supplier = supplier;
    this.baseModel = baseModel;
    this.apiBase = apiBase;
    this.apiKeyCipher = apiKeyCipher;
    this.apiKeyIv = apiKeyIv;
    validateCapacity(kind, contextWindow, dimension);
    this.contextWindow = contextWindow;
    this.dimension = dimension;
    this.thinking = thinking == null ? ThinkingMode.NONE : thinking;
    this.tools = tools == null ? ToolsMode.UNSUPPORTED : tools;
    this.builtin = builtin;
    this.remark = remark;
    this.createdBy = createdBy;
    this.status = ModelStatus.NOT_LOADED;
    this.enabled = false;
  }

  /**
   * 校验 {@code dimension} / {@code contextWindow} 的 null 语义。
   *
   * <p>★ 对话模型不产出向量 ⇒ {@code dimension} 必须为 null；向量模型没有对话上下文窗口 ⇒ {@code contextWindow} 必须为 null。
   * 领域这道与库层 check 约束是同一条规则的两道闸 —— 库那道是最后一道，领域这道让错误在构造时就说清原因， 而不是等到 flush 才抛一个看不懂的 {@code
   * ConstraintViolationException}。
   */
  private static void validateCapacity(ModelKind kind, Integer contextWindow, Integer dimension) {
    if (kind == ModelKind.CHAT && dimension != null) {
      throw new IllegalArgumentException(
          "对话模型不产出向量，dimension 必须为 null（向量维度是 embedding 模型的字段；填了它，界面会把「改维度需全量重灌」的警示挂到错误的模型上）");
    }
    if (kind == ModelKind.EMBEDDING && contextWindow != null) {
      throw new IllegalArgumentException(
          "向量模型没有对话上下文窗口，contextWindow 必须为 null（填了它，界面会把「上下文窗口」渲染到一个根本没有对话能力的模型上）");
    }
  }

  // ────────────────────────── 四态状态机 ──────────────────────────

  /**
   * 发起加载：任意态 → {@link ModelStatus#LOADING}。
   *
   * <p>★ <strong>任意态都可以发起</strong>，包括 {@code 加载失败} 的重试与 {@code 已加载} 的重载 —— 重载是运维的日常动作（换了 apiBase /
   * key 之后总要再点一次）。
   */
  public void startLoad() {
    this.status = ModelStatus.LOADING;
  }

  /**
   * 加载成功：{@code 加载中} → {@link ModelStatus#LOADED}，并清 {@code loadError}、置 {@code enabled=true}。
   *
   * <p>★ 只接受来自 {@code 加载中} 的转移。{@code 未加载} 直接 {@code markLoaded()}、{@code 加载失败} 直接 {@code
   * markLoaded()} 都是<strong>非法转移</strong> —— 后者意味着「失败原因还没被处理就当成功」，界面会把一个其实没起来的模型显示成可用。
   */
  public void markLoaded() {
    requireStatus(ModelStatus.LOADING, "标记加载成功");
    this.status = ModelStatus.LOADED;
    this.loadError = null;
    this.enabled = true;
  }

  /**
   * 加载失败：{@code 加载中} → {@link ModelStatus#LOAD_FAILED}，存<strong>可读原因</strong>，并置 {@code
   * enabled=false}。
   *
   * @param reason 能照着排查的那句话（如「网关不可达：连接 http://… 超时」）。 ★ 不是「加载失败」四个字 ——
   *     后者等于让用户停在原地。空原因与仅重复状态名的原因都会被拒绝。
   */
  public void markFailed(String reason) {
    requireStatus(ModelStatus.LOADING, "标记加载失败");
    String readable = requireReadableReason(reason);
    this.status = ModelStatus.LOAD_FAILED;
    this.loadError = readable;
    this.enabled = false;
  }

  /**
   * 关掉启用位（删除时调）。
   *
   * <p>★ 删资产之前先把 {@code enabled} 关掉，而不是只删行 —— 删除是应用服务在事务里做的多步动作， 先关掉可以让"删了一半"的中间态里没有仍在对外服务的模型。
   */
  public void clearEnable() {
    this.enabled = false;
  }

  // ────────────────────────── 更新变更方法 ──────────────────────────

  /** 改显示名。★ 不是基础模型名（那是另一个字段，且不可改）。 */
  public void name(String name) {
    this.name = name;
  }

  /** 改备注。 */
  public void remark(String remark) {
    this.remark = remark;
  }

  /** 改思考模式能力声明（只对对话模型有意义）。 */
  public void thinking(ThinkingMode thinking) {
    this.thinking = thinking == null ? ThinkingMode.NONE : thinking;
  }

  /** 改工具调用能力声明（只对对话模型有意义）。 */
  public void tools(ToolsMode tools) {
    this.tools = tools == null ? ToolsMode.UNSUPPORTED : tools;
  }

  /**
   * 改向量维度。
   *
   * <p>★ 调用方（{@code ModelService.update}）必须先检查 {@code status != LOADED} —— 已加载的模型改维度需全量重灌向量表，
   * 不能悄悄改。这里不做状态检查是因为"改维度"本身是合法操作，非法的是"在错误的时机改"。
   */
  public void dimension(Integer dimension) {
    this.dimension = dimension;
  }

  /** 改上游地址（换了供应商端点是日常运维动作）。 */
  public void apiBase(String apiBase) {
    this.apiBase = apiBase;
  }

  /** 更新 API key 的密文与 IV。★ 明文永远不进这个方法 —— 调用方先加密。 */
  public void apiKey(String cipher, String iv) {
    this.apiKeyCipher = cipher;
    this.apiKeyIv = iv;
  }

  /** 记录更新者。 */
  public void updatedBy(UUID updatedBy) {
    this.updatedBy = updatedBy;
  }

  /** 只有「加载中」才允许走到加载结果。★ 非法转移必须拒绝并说清原因，并且<strong>不改状态</strong>。 */
  private void requireStatus(ModelStatus expected, String action) {
    if (this.status != expected) {
      throw new IllegalStateException(
          "不能"
              + action
              + "：当前状态是「"
              + this.status
              + "」，只有「"
              + expected
              + "」才可以"
              + "（状态未改变）。加载失败后想重新加载，请先 startLoad() 回到「加载中」");
    }
  }

  /**
   * 失败原因必须是能照着排查的那句话。
   *
   * <p>★ 拒绝空原因与「加载失败」这类只重复状态名的文案 —— 一个只说"加载失败"的原因等于让用户自己猜， 而他会去点那个一模一样、注定再失败一次的按钮。
   */
  private static String requireReadableReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("加载失败原因不能为空 —— 请写能照着排查的那句话，如「网关不可达：连接 http://… 超时」");
    }
    String trimmed = reason.trim();
    if (trimmed.equals("LOAD_FAILED")
        || trimmed.equals("加载失败")
        || trimmed.equals("失败")
        || trimmed.equals("error")) {
      throw new IllegalArgumentException(
          "加载失败原因不能只重复状态名（「" + trimmed + "」）—— 请写能照着排查的那句话，如「网关不可达：连接 http://… 超时」");
    }
    return trimmed;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public ModelKind kind() {
    return kind;
  }

  public Supplier supplier() {
    return supplier;
  }

  public String baseModel() {
    return baseModel;
  }

  public String apiBase() {
    return apiBase;
  }

  public String apiKeyCipher() {
    return apiKeyCipher;
  }

  public String apiKeyIv() {
    return apiKeyIv;
  }

  public ModelStatus status() {
    return status;
  }

  public boolean enabled() {
    return enabled;
  }

  public String loadError() {
    return loadError;
  }

  public Integer contextWindow() {
    return contextWindow;
  }

  public Integer dimension() {
    return dimension;
  }

  public ThinkingMode thinking() {
    return thinking;
  }

  public ToolsMode tools() {
    return tools;
  }

  public boolean builtin() {
    return builtin;
  }

  public String remark() {
    return remark;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public UUID updatedBy() {
    return updatedBy;
  }
}
