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
 * 一条提示词的历史版本（只追加，不修改）。
 *
 * <p>★ 为什么是"只追加的副表"而不是把 {@code as_prompt_config} 改成"版本表 + 当前指针"：
 * 当前表是所有读路径的入口（应用配置选词、导出包、对话页），改形状会牵连一大片； 让当前表继续当"最新一版"，这张表只负责记住"以前长什么样"。
 *
 * <p>★ {@code savedAt} 的语义要读准：它是**这一版被换下来的时刻**（也就是新版本的保存时间）， 不是它当初被创建的时刻。版本列表里显示的"v2 · 昨天
 * 22:10"就是这个时间。 迁移初始化出来的 v1 用 {@code created_at} 填，因为那一版确实是当初建出来的样子。
 */
@Entity
@Table(name = "as_prompt_config_version")
public class PromptConfigVersion {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "config_id", nullable = false)
  private UUID configId;

  /** 同一 {@code configId} 内从 1 递增，数字大的是新的。 */
  @Column(name = "version_no", nullable = false)
  private int versionNo;

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

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private PromptKind kind = PromptKind.ROLE;

  @Column(name = "app_id")
  private UUID appId;

  @Column(name = "saved_at", nullable = false)
  private Instant savedAt;

  /** 触发这次保存的操作人；迁移初始化的 v1 为 {@code null}。 */
  @Column(name = "saved_by")
  private UUID savedBy;

  /** JPA 需要。 */
  protected PromptConfigVersion() {}

  public PromptConfigVersion(
      UUID id, UUID configId, int versionNo, PromptConfig source, Instant savedAt, UUID savedBy) {
    this.id = id;
    this.configId = configId;
    this.versionNo = versionNo;
    this.roleName = source.roleName();
    this.callWord = source.callWord();
    this.typeId = source.typeId();
    this.description = source.description();
    this.icon = source.icon();
    this.kind = source.kind();
    this.appId = source.appId();
    this.savedAt = savedAt;
    this.savedBy = savedBy;
  }

  public UUID id() {
    return id;
  }

  public UUID configId() {
    return configId;
  }

  public int versionNo() {
    return versionNo;
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

  public PromptKind kind() {
    return kind;
  }

  public UUID appId() {
    return appId;
  }

  public Instant savedAt() {
    return savedAt;
  }

  public UUID savedBy() {
    return savedBy;
  }
}
