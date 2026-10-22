package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 可复用的提示词模板（角色名称 + 正文 + 分类）。 */
@Entity
@Table(name = "as_prompt_config")
public class PromptConfig {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

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
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      Instant now) {
    this.id = id;
    this.createdAt = now;
    this.updatedAt = now;
    update(roleName, callWord, typeId, description, icon, now);
  }

  public void update(
      String roleName, String callWord, UUID typeId, String description, String icon, Instant now) {
    if (roleName == null || roleName.isBlank()) {
      throw new IllegalArgumentException("角色名称不能为空");
    }
    String trimmedRole = roleName.strip();
    if (trimmedRole.length() > 64) {
      throw new IllegalArgumentException("角色名称最多 64 字");
    }
    if (callWord == null || callWord.isBlank()) {
      throw new IllegalArgumentException("提示词不能为空");
    }
    String trimmedCall = callWord.strip();
    if (trimmedCall.length() > 1000) {
      throw new IllegalArgumentException("提示词最多 1000 字");
    }
    if (typeId == null) {
      throw new IllegalArgumentException("请选择提示词类型");
    }
    String desc = description == null ? "" : description.strip();
    if (desc.length() > 500) {
      throw new IllegalArgumentException("描述最多 500 字");
    }
    if (icon == null || icon.isBlank()) {
      throw new IllegalArgumentException("提示词图标不能为空");
    }
    if (icon.length() > 16) {
      throw new IllegalArgumentException("提示词图标最多 16 字");
    }
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
