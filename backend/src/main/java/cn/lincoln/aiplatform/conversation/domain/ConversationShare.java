package cn.lincoln.aiplatform.conversation.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条会话分享链接（{@code cv_share}）。
 *
 * <h3>★ 它是什么、不是什么</h3>
 *
 * <p>它<strong>是</strong>一个受限的读凭证：范围（会话 / 单条消息）+ 创建时冻结的密级 + 有效期 + 撤销时间。 它 <strong>不是</strong>内容快照 ——
 * 读到的是"链接归属人现在能看到的那些消息"，密级在读取时重新过滤，而不是复用创建时命中的结果。
 *
 * <h3>★★ 撤销与过期是两件事</h3>
 *
 * <p>{@code revokedAt} 一旦写入就是终态：再撤销一次是幂等（200），但不会"复活"。{@code expiresAt} 过后链接不可用，但可以重新生成一条 ——
 * 过期是时间到了，撤销是人主动收回。两者都不删行：审计要能回答"谁生成过、谁撤销的"。
 */
@Entity
@Table(name = "cv_share")
public class ConversationShare {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "token", nullable = false, length = 64)
  private String token;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Column(name = "message_id")
  private UUID messageId;

  @Column(name = "scope", nullable = false, length = 16)
  private String scope;

  @Enumerated(EnumType.STRING)
  @Column(name = "max_classification", nullable = false, length = 16)
  private ClassificationLevel maxClassification;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ConversationShare() {}

  public ConversationShare(
      UUID id,
      String token,
      UUID conversationId,
      UUID messageId,
      String scope,
      ClassificationLevel maxClassification,
      Instant expiresAt,
      UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.token = token;
    this.conversationId = conversationId;
    this.messageId = messageId;
    this.scope = scope;
    this.maxClassification = maxClassification;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public String token() {
    return token;
  }

  public UUID conversationId() {
    return conversationId;
  }

  public UUID messageId() {
    return messageId;
  }

  public String scope() {
    return scope;
  }

  public ClassificationLevel maxClassification() {
    return maxClassification;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant revokedAt() {
    return revokedAt;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** 撤销是终态：只写一次；再撤一次不会改变它（幂等）。 */
  public void revoke(Instant at) {
    if (revokedAt == null) {
      revokedAt = at;
    }
  }

  /** 有效 = 未撤销且未过期。 */
  public boolean usableAt(Instant now) {
    return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }
}
