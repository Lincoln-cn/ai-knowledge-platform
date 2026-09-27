package cn.lincoln.aiplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 刷新令牌（聚合根）。
 *
 * <p>★ 表里**只存哈希**，不存明文 —— 这一点的意义是：库被读走 ≠ 令牌被拿走。 明文令牌只在签发的那个响应里出现一次，之后服务端再也拿不到它（这正是"刷新即轮换"能成立的前提）。
 *
 * <p>★ {@link #replacedBy} 是**轮换链**，不是流水账： 一个已经 {@code replaced} 的令牌如果再次出现，说明它被复制过（或被人从浏览器里捞走了），
 * 此时正确的反应是<strong>撤销整条链</strong>而不是"拒绝这一次" —— 因为攻击者手里那个令牌是有效的，放过它等于把会话送出去一半。
 */
@Entity
@Table(name = "iam_refresh_token")
public class RefreshToken {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** sha256(明文)，64 位十六进制。 */
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** 替换它的那个令牌；为空表示"未被轮换过"。 */
  @Column(name = "replaced_by")
  private UUID replacedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  public RefreshToken(
      UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  /** 这个令牌现在还能用来换 access 吗。 */
  public boolean isUsable(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  /** 被轮换掉了：撤销自己，并记下接替者。 */
  public void rotateTo(UUID replacementId, Instant at) {
    this.revokedAt = at;
    this.replacedBy = replacementId;
  }

  /** 撤销（登出、改密、以及重放被侦测到时撤销整条链）。 */
  public void revoke(Instant at) {
    this.revokedAt = at;
  }

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public String tokenHash() {
    return tokenHash;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant revokedAt() {
    return revokedAt;
  }

  public UUID replacedBy() {
    return replacedBy;
  }
}
