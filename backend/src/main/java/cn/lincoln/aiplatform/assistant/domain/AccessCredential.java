package cn.lincoln.aiplatform.assistant.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 免登录访问凭证（契约 {@code frontend/src/api/assistants.ts} 的 {@code AccessCredential}）。
 *
 * <p>★ 它<strong>不实现</strong> {@code OwnedResource}：凭证自己没有归属与可见性 —— 那两件事
 * <strong>走它所属的应用</strong>。给凭证补一份 {@code ownerId/visibility} 等于允许"凭证的归属与应用的归属不一致"
 * 这个状态存在，而那个状态没人能说清该怎么判（{@code 07 §3.3} 的"判定只有一处"在这里表现为"判定发生在应用那一层"）。
 *
 * <h3>★★ {@link #maxClassification}：创建时冻结（{@code 13 §5.2}）</h3>
 *
 * <p>它是<strong>这条凭证被允许看到的最敏感内容</strong>，取<strong>创建者当时的 clearance</strong>，
 * 且<strong>不接受请求体传</strong>（否则调用方能把自己的凭证抬到超过自己 —— 那是一条自建越权通道）。两条后果：
 *
 * <ol>
 *   <li>访客每次访问都用它<strong>重新过滤</strong>，不是复用创建时命中的那些结果（{@code 13 §5.2} 第 ② 级）；
 *   <li>应用内容<strong>升密后原链接立即失效</strong>（{@code 13 §5.3}）。
 * </ol>
 *
 * <p>★ 契约把它定成<strong>必填回执</strong>（不是可选）：「没有冻结值的链接」只能靠"创建时命中过"放行， 而那是 {@code 13 §5.2} 明确写的「⚠️ 不得」。
 *
 * <h3>★ token 存储的取舍（明文，不是哈希）</h3>
 *
 * <p>库里存的是<strong>可取回的明文</strong>。理由：契约的{@code listCredentials} 要返回 {@code token}
 * （凭证管理页要把链接显示出来给人复制），而 MVP 不做"仅创建时下发一次"那套。
 *
 * <p>★ 必须说清的代价：拿到库的读权限（或一次备份）的人可以直接用这些 token 免登录访问。这与"只存哈希"相比是
 * 一笔<strong>真实的账</strong>，不是风格问题。若将来改成只存哈希，契约的 {@code token} 语义必须变成 「仅创建时返回一次」——
 * 那是一处<strong>契约变更</strong>，要先改 {@code frontend/src/api/assistants.ts} 的注释与前端消费点，不是一次内部重构。
 *
 * <h3>撤销是终态</h3>
 *
 * <p>{@link #revoke} 之后不能再 {@link #setEnabled(boolean, UUID)} 打开 —— 契约的 {@code revokeCredential}
 * 注释写着"撤销后不可重新启用，只能重新生成"。库里还有 {@code chk_as_credential_revoked_is_disabled} 兜一道（防将来某条新写入路径漏判）。
 */
@Entity
@Table(name = "as_access_credential")
public class AccessCredential {

  /**
   * 令牌前缀。
   *
   * <p>★ 与设计册里那两套凭证（应用级 {@code pk_live_*} 与后来的会话级）保持一致的前缀习惯：
   * 一眼能看出"这是哪种令牌"，排障时不用把它贴到别处试。它<strong>不是</strong>安全机制。
   */
  public static final String TOKEN_PREFIX = "pk_live_";

  /** 32 字节熵的 URL 安全串（Base64 无填充 ⇒ 长度 43）。 */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属应用。★ 归属与可见性判定走它（见类注释）。 */
  @Column(name = "assistant_id", nullable = false)
  private UUID assistantId;

  @Column(name = "token", nullable = false, length = 128)
  private String token;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  /** null = 长期有效。 */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /** ★★ 创建时冻结的密级上限 = 创建者当时的 clearance。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "max_classification", nullable = false, length = 16)
  private ClassificationLevel maxClassification;

  @Column(name = "access_count", nullable = false)
  private long accessCount;

  @Column(name = "last_access_at")
  private Instant lastAccessAt;

  /** ★ 撤销是终态。 */
  @Column(name = "revoked_at")
  private Instant revokedAt;

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

  /**
   * 乐观锁。
   *
   * <p>★ 为什么这张表要而 {@code as_assistant} 不要：这里有一条"读-改-写"的竞争路径 —— 访客访问时 {@link #recordAccess()} 与管理员撤销
   * {@link #revoke} 可能<strong>同时</strong>发生。 没有它，一次撤销可能被一次并发的访问计数覆盖掉（撤销丢了，而没人会报这个 bug）。
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected AccessCredential() {}

  /**
   * 新建一条凭证。
   *
   * @param maxClassification ★ <strong>由调用方从请求者的 {@code ScopeFilter.maxClassification} 取出</strong>，
   *     不是请求体里来的（见类注释）
   * @param expiresAt null = 长期有效
   */
  public AccessCredential(
      UUID id,
      UUID assistantId,
      String token,
      ClassificationLevel maxClassification,
      Instant expiresAt,
      UUID createdBy) {
    if (maxClassification == null) {
      // ★ fail-closed 的方向：没有 clearance 就不许建凭证（而不是"默认给最高"或"默认给公开"）。
      //   "默认给公开"看起来更保守，但它会造出一条**谁都不知道自己密级不对**的链接。
      throw new IllegalArgumentException("创建访问凭证需要请求者当前的密级上限（clearance）");
    }
    this.id = id;
    this.assistantId = assistantId;
    this.token = token;
    this.maxClassification = maxClassification;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.enabled = true;
  }

  /** 生成一个令牌：{@code pk_live_} + 32 字节熵的 URL 安全串。 */
  public static String newToken() {
    byte[] entropy = new byte[32];
    RANDOM.nextBytes(entropy);
    return TOKEN_PREFIX + ENCODER.encodeToString(entropy);
  }

  /**
   * 启停（契约 {@code setCredentialEnabled}）。
   *
   * <p>★ 撤销过的凭证<strong>不许</strong>再被打开：撤销是终态，只能重新生成（{@link #revoke}）。 这一句是"终态"这个词在代码里的唯一含义 ——
   * 少了它，{@code revokedAt} 只是一个装饰。
   */
  public void setEnabled(boolean value, UUID updatedBy) {
    if (revokedAt != null) {
      throw new InvalidCredentialException("这条凭证已撤销，不能重新启用。撤销是终态，请重新生成一条");
    }
    this.enabled = value;
    this.updatedBy = updatedBy;
  }

  /**
   * 撤销（契约 {@code revokeCredential}）—— <strong>终态</strong>。
   *
   * <p>幂等：已撤销的再撤销不改时间戳（否则审计里"撤销了两次"看起来像有人反复动手）。
   */
  public void revoke(UUID updatedBy) {
    if (revokedAt == null) {
      this.revokedAt = Instant.now();
    }
    // ★ 撤销必须同时关掉 enabled：库层有 chk_as_credential_revoked_is_disabled 兜着，
    //   而领域这边不能依赖库报错（那句约束异常读不出"为什么"）
    this.enabled = false;
    this.updatedBy = updatedBy;
  }

  /** 记一次访问（契约 {@code accessCount} / {@code lastAccessAt}）。 */
  public void recordAccess() {
    this.accessCount++;
    this.lastAccessAt = Instant.now();
  }

  /** 软删（仅聚合根使用，{@code 02 §5.1}）。 */
  public void markDeleted(UUID updatedBy) {
    this.deletedAt = Instant.now();
    this.updatedBy = updatedBy;
  }

  /**
   * 这条凭证现在能不能用（状态维度）。
   *
   * <p>★ 三层分开报：撤销是终态、停用可恢复、过期是时间到了 —— 三者的处置完全不同， 合成一句"不可用"会让排障只能猜（见 {@code
   * AccessCredentialService}）。
   */
  public boolean usable(Instant now) {
    return revokedAt == null && enabled && (expiresAt == null || expiresAt.isAfter(now));
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public String token() {
    return token;
  }

  public boolean enabled() {
    return enabled;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  /** ★★ 创建时冻结的密级上限。 */
  public ClassificationLevel maxClassification() {
    return maxClassification;
  }

  public long accessCount() {
    return accessCount;
  }

  public Instant lastAccessAt() {
    return lastAccessAt;
  }

  public Instant revokedAt() {
    return revokedAt;
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

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
