package cn.lincoln.aiplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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

  /** 登录成功后记一笔。★ 由应用服务在事务内调用，不在这里做持久化。 */
  public void recordLogin(Instant at) {
    this.lastLoginAt = at;
  }

  /** 改密成功后清掉"必须改密"标记。 */
  public void markPasswordChanged(String newPasswordHash) {
    this.passwordHash = newPasswordHash;
    this.mustChangePassword = false;
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

  public Instant lastLoginAt() {
    return lastLoginAt;
  }
}
