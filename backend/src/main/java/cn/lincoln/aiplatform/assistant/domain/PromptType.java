package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 提示词类型（分类法）。 */
@Entity
@Table(name = "as_prompt_type")
public class PromptType {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "type_name", nullable = false, length = 64)
  private String typeName;

  @Column(name = "icon", nullable = false, length = 16)
  private String icon;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 需要。 */
  protected PromptType() {}

  public PromptType(UUID id, String typeName, String icon, Instant now) {
    this.id = id;
    this.createdAt = now;
    this.updatedAt = now;
    rename(typeName, icon, now);
  }

  public void rename(String typeName, String icon, Instant now) {
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("类型名称不能为空");
    }
    String trimmed = typeName.strip();
    if (trimmed.length() > 64) {
      throw new IllegalArgumentException("类型名称最多 64 字");
    }
    if (icon == null || icon.isBlank()) {
      throw new IllegalArgumentException("类型图标不能为空");
    }
    if (icon.length() > 16) {
      throw new IllegalArgumentException("类型图标最多 16 字");
    }
    this.typeName = trimmed;
    this.icon = icon.strip();
    this.updatedAt = now;
  }

  public UUID id() {
    return id;
  }

  public String typeName() {
    return typeName;
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
