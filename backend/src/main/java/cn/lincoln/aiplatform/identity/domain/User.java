package cn.lincoln.aiplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 用户（聚合根）。
 *
 * <p>JPA 注解直接用在聚合上，理由见 {@link Role} 的类注释（{@code 01 §6.3} 的务实折中）。
 *
 * <p>★ 它只持有 {@code roleId} 而不是一个 {@code Role} 对象引用 —— 聚合之间用 ID 引用， 不互相持有（也让"取用户"不必顺带把角色拖出来）。
 */
@Entity
@Table(name = "iam_user")
public class User {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "username", nullable = false, length = 64)
  private String username;

  @Column(name = "display_name", nullable = false, length = 128)
  private String displayName;

  /**
   * BCrypt 哈希。
   *
   * <p>★ 这里不提供任何"校验密码"的方法 —— 密码比对是 {@code PasswordEncoder} 的职责 （{@code 08
   * §4.2.2}：不手写密码比对）。领域对象只负责把哈希保管好。
   */
  @Column(name = "password_hash", nullable = false, length = 72)
  private String passwordHash;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private UserStatus status;

  /** 首次登录必须改密：前端据此强制跳改密页（契约 {@code LoginResult.mustChangePassword}）。 */
  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  /**
   * 连续登录失败次数（等保：登录失败处理）。
   *
   * <p>★ 它与 {@link #lockedUntil} 是"当前的鉴别状态"，所以落库、不落内存 —— 放内存的话重启一次就送攻击者一轮全新的机会，而重启是运维的日常动作。
   * 对照组：验证码放内存是可以的，因为它 2 分钟就过期（见 V5 迁移的说明）。
   */
  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  /** 锁定截止时刻。null 或已过期表示未锁定。 */
  @Column(name = "locked_until")
  private Instant lockedUntil;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * 软删除时刻（{@code DELETE /users/:id}）。
   *
   * <p>★ 用户必须**软删**：它是审计字段（{@code created_by} / {@code updated_by}）指向的东西，
   * 硬删会让历史记录里的"谁改的"变成一个空指针。而且用户名唯一用的是部分索引 （{@code where deleted_at is null}），软删之后同名账号可以重建。
   */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected User() {}

  public User(
      UUID id,
      String username,
      String displayName,
      String passwordHash,
      UUID roleId,
      UserStatus status,
      boolean mustChangePassword) {
    this.id = id;
    this.username = username;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.roleId = roleId;
    this.status = status;
    this.mustChangePassword = mustChangePassword;
  }

  /**
   * 这个账号现在能不能登录。
   *
   * <p>★ 把判断放在领域对象上，而不是散在 service 里写 {@code if (user.getStatus() == ...)} ——
   * 后者会在"停用"之外多出"待审核"时被漏改（契约里就是三态： {@code 正常 / 停用 / 待审核}，只有"正常"能登录）。
   */
  public boolean canLogin() {
    return status == UserStatus.正常;
  }

  // ────────────────────────── 登录失败与锁定（等保） ──────────────────────────

  /** 现在是否处于锁定期。 */
  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** 锁定还剩多久；未锁定时为 {@link Duration#ZERO}。用来给用户一个能照做的提示。 */
  public Duration remainingLockout(Instant now) {
    return isLocked(now) ? Duration.between(now, lockedUntil) : Duration.ZERO;
  }

  /**
   * 记一次失败的登录尝试；达到阈值即锁定。
   *
   * <p>★ 阈值与时长由调用方传入（来自配置）—— 领域对象认识规则，但不认识配置。
   *
   * <p>★ 触发锁定后把计数<strong>归零</strong>：不归零的话，解锁后第一次失败会立刻再次锁定， 用户的体感是"这个账号永久坏了"，而规则明明写的是"连续失败 3 次"。
   */
  public void recordFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
    this.failedLoginAttempts++;
    if (this.failedLoginAttempts >= maxAttempts) {
      this.lockedUntil = now.plus(lockDuration);
      this.failedLoginAttempts = 0;
    }
  }

  /** 登录成功：清掉失败计数与锁定状态，并记一笔登录时间。★ 由应用服务在事务内调用。 */
  public void recordSuccessfulLogin(Instant now) {
    this.failedLoginAttempts = 0;
    this.lockedUntil = null;
    this.lastLoginAt = now;
  }

  /** 改密成功后清掉"必须改密"标记。 */
  public void markPasswordChanged(String newPasswordHash) {
    this.passwordHash = newPasswordHash;
    this.mustChangePassword = false;
  }

  /** 软删除。★ 不在这里做持久化，由应用服务在事务内调用。 */
  public void softDelete(Instant at) {
    this.deletedAt = at;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  /** 改显示名 / 所属角色（{@code PUT /users/:id}）。 */
  public void updateProfile(String displayName, UUID roleId) {
    this.displayName = displayName;
    this.roleId = roleId;
  }

  public UUID id() {
    return id;
  }

  public String username() {
    return username;
  }

  public String displayName() {
    return displayName;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public UUID roleId() {
    return roleId;
  }

  public UserStatus status() {
    return status;
  }

  public boolean mustChangePassword() {
    return mustChangePassword;
  }

  public int failedLoginAttempts() {
    return failedLoginAttempts;
  }

  public Instant lockedUntil() {
    return lockedUntil;
  }

  public Instant lastLoginAt() {
    return lastLoginAt;
  }
}
