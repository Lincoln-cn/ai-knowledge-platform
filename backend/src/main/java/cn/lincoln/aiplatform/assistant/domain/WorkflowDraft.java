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
 * 编排草稿：一个应用对应一张画布图（{@code #198} 的第一块）。
 *
 * <p>★ 它<strong>不是</strong>版本。版本是"发布那一刻固化的图"，草稿是"现在画布上是什么"。 两者刻意分开：草稿允许不合规（边画边存），版本必须过校验（发布前）。
 */
@Entity
@Table(name = "as_workflow_draft")
public class WorkflowDraft {

  @Id
  @Column(name = "assistant_id")
  private UUID assistantId;

  /** 原始图 JSON（`{"nodes":[],"edges":[]}` 形状），原样存取 —— 后端这一层不理解节点类型。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "graph", nullable = false)
  private String graph;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkflowDraft() {}

  public WorkflowDraft(UUID assistantId, String graph, Instant updatedAt) {
    this.assistantId = assistantId;
    this.graph = graph;
    this.updatedAt = updatedAt;
  }

  public void replace(String graph, Instant at) {
    this.graph = graph;
    this.updatedAt = at;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public String graph() {
    return graph;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
