package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条提示词的评测集（一组固定问题）。ROADMAP §11.63.3。
 *
 * <p>★ 为什么评测集挂在提示词下、而不是全局共用一套题： 题是"给这条提示词出的"。一套跨提示词通用的题只能问很泛的东西，
 * 而角色设定之间的差别恰恰在具体口径上（"必须标出第几条"、"不确定就说不知道"）。
 *
 * <p>★ 同一条提示词下评测集名字不许重复（迁移里 `uq_as_prompt_eval_set_name`）：两套同名会让"这次测的是哪套"变成猜的。
 */
@Entity
@Table(name = "as_prompt_eval_set")
public class PromptEvalSet {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "prompt_config_id", nullable = false)
  private UUID promptConfigId;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  protected PromptEvalSet() {}

  public PromptEvalSet(
      UUID id, UUID promptConfigId, String name, Instant createdAt, UUID createdBy) {
    this.id = id;
    this.promptConfigId = promptConfigId;
    this.name = name;
    this.createdAt = createdAt;
    this.createdBy = createdBy;
  }

  public UUID id() {
    return id;
  }

  public UUID promptConfigId() {
    return promptConfigId;
  }

  public String name() {
    return name;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID createdBy() {
    return createdBy;
  }
}
