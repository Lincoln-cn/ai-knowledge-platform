package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 可复用的提示词（角色名称 + 正文 + 分类）。
 *
 * <p>★ 两种用途共用一张表，由 {@link #kind} 区分：{@link PromptKind#ROLE} 是给模型的身份设定（应用配置里选用）， {@link
 * PromptKind#QUESTION} 是给终端用户的问法示例（对话页空态与抽屉）。同一个「名称 + 正文 + 分类」形状， 差的是受益对象 —— 详见 {@link PromptKind}
 * 的说明。
 */
@Entity
@Table(name = "as_prompt_config")
public class PromptConfig {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 用途。存量行由迁移的默认值落在 {@link PromptKind#ROLE}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private PromptKind kind = PromptKind.ROLE;

  /**
   * 提问模板推荐给哪个应用（{@code null} = 通用）。
   *
   * <p>★ 这不是绑定关系，只是候选池的标注：运行期读的是 {@code as_assistant.quick_questions}， 应用被删时模板没有理由跟着失效，所以这里不设外键。
   */
  @Column(name = "app_id")
  private UUID appId;

  @Column(name = "role_name", nullable = false, length = 64)
  private String roleName;

  @Column(name = "call_word", nullable = false, length = 1000)
  private String callWord;

  @Column(name = "type_id", nullable = false)
  private UUID typeId;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "icon", nullable = false, length = 16)
  private String icon;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 需要。 */
  protected PromptConfig() {}

  public PromptConfig(
      UUID id,
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      Instant now) {
    this.id = id;
    this.createdAt = now;
    this.updatedAt = now;
    update(kind, appId, roleName, callWord, typeId, description, icon, now);
  }

  public void update(
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      Instant now) {
    if (roleName == null || roleName.isBlank()) {
      throw new IllegalArgumentException("名称不能为空");
    }
    String trimmedRole = roleName.strip();
    if (trimmedRole.length() > 64) {
      throw new IllegalArgumentException("名称最多 64 字");
    }
    if (callWord == null || callWord.isBlank()) {
      throw new IllegalArgumentException("正文不能为空");
    }
    String trimmedCall = callWord.strip();
    if (trimmedCall.length() > 1000) {
      throw new IllegalArgumentException("正文最多 1000 字");
    }
    if (typeId == null) {
      throw new IllegalArgumentException("请选择提示词类型");
    }
    String desc = description == null ? "" : description.strip();
    if (desc.length() > 500) {
      throw new IllegalArgumentException("描述最多 500 字");
    }
    if (icon == null || icon.isBlank()) {
      throw new IllegalArgumentException("图标不能为空");
    }
    if (icon.length() > 16) {
      throw new IllegalArgumentException("图标最多 16 字");
    }
    this.kind = kind == null ? PromptKind.ROLE : kind;
    // ★ 角色指令没有「推荐给某个应用」这个概念：它在应用配置里被选用，选中的那一刻就落到具体应用上了。
    this.appId = this.kind == PromptKind.QUESTION ? appId : null;
    this.roleName = trimmedRole;
    this.callWord = trimmedCall;
    this.typeId = typeId;
    this.description = desc;
    this.icon = icon.strip();
    this.updatedAt = now;
  }

  public UUID id() {
    return id;
  }

  public PromptKind kind() {
    return kind;
  }

  public UUID appId() {
    return appId;
  }

  public String roleName() {
    return roleName;
  }

  public String callWord() {
    return callWord;
  }

  public UUID typeId() {
    return typeId;
  }

  public String description() {
    return description;
  }

  public String icon() {
    return icon;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
