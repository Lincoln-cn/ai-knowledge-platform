package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一个已发布的编排版本（{@code #198} 的第四块）。
 *
 * <p>★ 它<strong>不是</strong>草稿的指针，而是一份<strong>完整快照</strong>：发布那一刻的图原文原样抄进来。 这条选择是有代价的 ——
 * 同一张图会被存两份（草稿 + 每个版本），但换来的是一句能对用户说的保证： 「再改画布，不会悄悄改变线上正在跑的那一版」。
 *
 * <p>★ {@code version} 是<strong>按应用递增</strong>的（1, 2, 3…），由 {@code unique (assistant_id, version)}
 * 保证同应用内不重复。 递增号不是主键：主键是 {@code id}，因为"第 2 版"这个身份只在同一个应用里有意义。
 *
 * <p>★ {@code publishedBy} 只存 id，不存名字快照 —— 审计表另有一条记录回答"当时是谁"（改名的语义差别见 {@code 13 §七}）。
 */
@Entity
@Table(name = "as_workflow_version")
public class WorkflowVersion {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "assistant_id", nullable = false)
  private UUID assistantId;

  @Column(name = "version", nullable = false)
  private int version;

  /** 发布那一刻的完整图 JSON（`{"nodes":[],"edges":[]}` 形状），原样存取。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "graph", nullable = false)
  private String graph;

  /** 发布说明（可以为空串，建表语句的默认值就是它）。 */
  @Column(name = "note", nullable = false)
  private String note;

  @Column(name = "published_by", nullable = false)
  private UUID publishedBy;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;

  protected WorkflowVersion() {}

  public WorkflowVersion(
      UUID id,
      UUID assistantId,
      int version,
      String graph,
      String note,
      UUID publishedBy,
      Instant publishedAt) {
    this.id = id;
    this.assistantId = assistantId;
    this.version = version;
    this.graph = graph;
    this.note = note;
    this.publishedBy = publishedBy;
    this.publishedAt = publishedAt;
  }

  public UUID id() {
    return id;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public int version() {
    return version;
  }

  public String graph() {
    return graph;
  }

  public String note() {
    return note;
  }

  public UUID publishedBy() {
    return publishedBy;
  }

  public Instant publishedAt() {
    return publishedAt;
  }
}
