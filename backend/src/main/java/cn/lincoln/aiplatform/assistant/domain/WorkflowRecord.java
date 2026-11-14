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
 * 一条编排的运行记录（{@code #198} 的运行记录端点；契约 {@code frontend/src/api/workflow.ts} 的 {@code WorkflowRecord}）。
 *
 * <p>★ <strong>今天没有任何写入方</strong>。能写这张表的是"把用户画的图真的跑一遍"的执行引擎， 而 {@code 08 §二} 与 {@code 09 §5.3}
 * 把执行引擎排在 V1.0 —— 它在 {@code workflow} 模块里还只有一个 {@code package-info}（准入判据见 {@code
 * caliber/WorkflowEngineAdmissionTest}）。 ★ 画布内调试 {@code POST …/workflow/debug}
 * <strong>刻意不写</strong>本表（设计册与 mock 同一口径：调试不产生正式运行记录）， 所以它落地之后也不是这里的数据来源。
 *
 * <p>★ 正因如此，本类只被"读"这一侧使用：仓储端口上没有 {@code save} （{@link WorkflowRecordRepository}）， 端点也就只能读到 0 行。
 * 这句话刻意写在这里而不是只写在提交信息里 —— 后一个人打开这个类时， 最容易误读成"功能已具备，只是还没数据"。
 *
 * <p>★ {@code nodeTrail} 是<strong>执行当时</strong>的快照（原样 JSON 串，不经本类加工）：
 * 流程改版后回查当前图会把历史记录的节点名换成新版的名字，那是在篡改历史（契约注释同口径）。
 */
@Entity
@Table(name = "as_workflow_record")
public class WorkflowRecord {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "assistant_id", nullable = false)
  private UUID assistantId;

  /** 触发本次运行的会话；可为 null（不是每一跑都来自会话）。刻意没有外键，见迁移文件的注释。 */
  @Column(name = "conversation_id")
  private UUID conversationId;

  /** 触发本次运行的输入原文 —— 没有它，记录只能看出"跑过"，看不出"为什么这样跑"。 */
  @Column(name = "input", nullable = false)
  private String input;

  /** 节点轨迹 JSON 数组（{@code [{nodeId,type,title,status,enteredAt,elapsedMs,...}]}），原样存取。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "node_trail", nullable = false)
  private String nodeTrail;

  /** 最终答复的摘要（截断）。失败的那次是空串 —— 不是 null，界面不必判两种"没有"。 */
  @Column(name = "final_output_preview", nullable = false)
  private String finalOutputPreview;

  /** {@code SUCCEEDED} / {@code FAILED}（{@code 14 §2.4}），库里另有 check 约束钉住取值域。 */
  @Column(name = "status", nullable = false)
  private String status;

  /** 失败原因；成功时为 null（库里另有约束要求 {@code FAILED} 必须给得出原因）。 */
  @Column(name = "fail_reason")
  private String failReason;

  /** 整条记录的总耗时（毫秒）—— 不让界面把 trail 自行相加，两者口径会漂移。 */
  @Column(name = "elapsed_ms", nullable = false)
  private int elapsedMs;

  /** 这一跑开始的时间；列表按它倒序。 */
  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  /** 落库时间（审计/排障用，契约里不出现）。 */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * 这一跑的发起方：{@code MANUAL}（人点试用）/ {@code CRON}（定时触发，{@code #295}）。
   *
   * <p>★ 为什么不用 {@code conversationId} 是否为空来区分：试用也是空的，而"有人盯着跑"与"没人盯着跑"
   * 在排障时是完全不同的两个问题（前者能问用户当时输入了什么，后者只能看记录）。
   */
  @Column(name = "trigger_source", nullable = false, length = 16)
  private String triggerSource;

  protected WorkflowRecord() {}

  /** 人点试用（{@code MANUAL}）与定时触发（{@code CRON}）之外的来源暂不存在，缺省按人点。 */
  public WorkflowRecord(
      UUID id,
      UUID assistantId,
      UUID conversationId,
      String input,
      String nodeTrail,
      String finalOutputPreview,
      String status,
      String failReason,
      int elapsedMs,
      Instant startedAt,
      Instant createdAt) {
    this(
        id,
        assistantId,
        conversationId,
        input,
        nodeTrail,
        finalOutputPreview,
        status,
        failReason,
        elapsedMs,
        startedAt,
        createdAt,
        "MANUAL");
  }

  public WorkflowRecord(
      UUID id,
      UUID assistantId,
      UUID conversationId,
      String input,
      String nodeTrail,
      String finalOutputPreview,
      String status,
      String failReason,
      int elapsedMs,
      Instant startedAt,
      Instant createdAt,
      String triggerSource) {
    this.id = id;
    this.assistantId = assistantId;
    this.conversationId = conversationId;
    this.input = input;
    this.nodeTrail = nodeTrail;
    this.finalOutputPreview = finalOutputPreview;
    this.status = status;
    this.failReason = failReason;
    this.elapsedMs = elapsedMs;
    this.startedAt = startedAt;
    this.createdAt = createdAt;
    this.triggerSource = triggerSource == null ? "MANUAL" : triggerSource;
  }

  public UUID id() {
    return id;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public UUID conversationId() {
    return conversationId;
  }

  public String input() {
    return input;
  }

  public String nodeTrail() {
    return nodeTrail;
  }

  public String finalOutputPreview() {
    return finalOutputPreview;
  }

  public String status() {
    return status;
  }

  public String failReason() {
    return failReason;
  }

  public int elapsedMs() {
    return elapsedMs;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** {@code MANUAL} 或 {@code CRON}。 */
  public String triggerSource() {
    return triggerSource;
  }
}
