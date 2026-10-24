package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 应用 API Key（{@code as_assistant_api_key}，{@code #195} 的「API Key」子项）。
 *
 * <p>★ 它是<strong>程序化调用</strong>的凭据，与「访问凭证」（免登录公开链接的 token）是两套机制：
 * 后者换来的是一个公开问答页，前者换来的是"以这个应用的身份调用平台"。
 *
 * <p>★ 明文落库是契约要求（{@code ApiKeyItem.key} 在管理页可复制），与 {@code as_access_credential.token} 同口径；不要把它和
 * {@code iam_user.password_hash}（bcrypt）混为一谈。 轮换只新增、不吊销旧的 —— 旧 Key 由管理员单独停用，这正是页面文案承诺的行为。
 *
 * <p>★ 本实体只承载"哪把 Key 属于哪个应用、还启不启用"，不做鉴权缓存、不做哈希； 鉴权侧只需要按 {@code key_value} 找到这一行。
 */
@Entity
@Table(name = "as_assistant_api_key")
public class AssistantApiKey {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assistant_id", nullable = false)
  private UUID assistantId;

  @Column(name = "key_value", nullable = false, length = 64)
  private String keyValue;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  /** JPA 用。 */
  protected AssistantApiKey() {}

  public AssistantApiKey(
      UUID id, UUID assistantId, String keyValue, boolean enabled, Instant createdAt) {
    this.id = id;
    this.assistantId = assistantId;
    this.keyValue = keyValue;
    this.enabled = enabled;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public String keyValue() {
    return keyValue;
  }

  public boolean enabled() {
    return enabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant lastUsedAt() {
    return lastUsedAt;
  }

  /**
   * 停用 / 启用。
   *
   * <p>★ 只改这一个字段：轮换是新增一行，不是改这一行 —— 否则"旧 Key 保留可单独停用"就无从实现。
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
