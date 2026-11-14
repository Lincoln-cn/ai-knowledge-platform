package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowPause;
import cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跑一张编译好的计划，并落一行运行记录 —— 人点的「试用」与定时触发**共用这一份**（{@code #295} / 0.7.3）。
 *
 * <p>★ 为什么抽出来：这两条路的差别只在"图从哪来"（草稿 or 已发布版本）与"谁发起的"（{@code MANUAL} / {@code
 * CRON}），而"跑、接中断、判状态、写记录"这四步完全一样。各写一份的下场不是编译不过，是 <strong>两处的状态判定悄悄分叉</strong> —— 一边把中断算成
 * FAILED、另一边算成 PAUSED，而各自都"看起来对"。
 *
 * <p>★ <strong>为什么执行与写记录不在同一个事务里</strong>：{@code LinearWorkflowExecutor#executeAndReturn}
 * 在节点抛异常时整批回滚（L1：失败那跑的节点记录不得显示成功）。如果记录也放进那个事务， <strong>失败的运行就不会留下任何痕迹</strong> —— 而失败恰恰是最需要回查的那一次。
 */
@Service
public class WorkflowRunRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunRecorder.class);

  /** 最终输出的预览长度上限（与列表页展示口径一致，避免记录表存下整段长文本）。 */
  private static final int PREVIEW_LIMIT = 500;

  private final LinearWorkflowExecutor executor;
  private final WorkflowRecordRepository records;
  private final WorkflowPauseRepository pauses;

  public WorkflowRunRecorder(
      LinearWorkflowExecutor executor,
      WorkflowRecordRepository records,
      WorkflowPauseRepository pauses) {
    this.executor = executor;
    this.records = records;
    this.pauses = pauses;
  }

  /**
   * 跑一次并写一行记录。
   *
   * @param assistantId 编排应用
   * @param plan 已编译好的计划（编译失败不该走到这里 —— 那不是"一次失败的运行"，是"这张图还不成立"）
   * @param input 入口输入
   * @param conversationId 触发本次运行的会话；可为 {@code null}
   * @param triggerSource {@code MANUAL}（人点试用）或 {@code CRON}（定时触发）
   */
  public Outcome run(
      UUID assistantId,
      WorkflowCompiler.ExecutionPlan plan,
      String input,
      UUID conversationId,
      String triggerSource) {
    UUID executionId = UUID.randomUUID();
    Instant startedAt = Instant.now();
    long began = System.nanoTime();

    String failReason = null;
    String output = null;
    boolean paused = false;
    try {
      FlowState state = executor.executeAndReturn(executionId, plan, input);
      output = state.read("reply");
    } catch (WorkflowInterrupted interrupted) {
      // ★ 中断不是失败：它是「这次执行停在这里、状态还在、可以接着跑」。
      //   所以单独一档，不能落进下面那个 catch 的 FAILED 分支。
      paused = true;
      output = null;
      // ★ 把暂停那一刻的状态存住 —— 没有它，「恢复」无从谈起：已走到的节点丢了，重跑一遍等于从头来。
      pauses.save(
          new WorkflowPause(
              executionId,
              assistantId,
              interrupted.nodeId(),
              interrupted.nodeType(),
              interrupted.stateSnapshot(),
              interrupted.stepsDone(),
              Instant.now(),
              null));
    } catch (RuntimeException ex) {
      failReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      LOG.warn(
          "#工作流运行 执行失败（assistant={}，execution={}，source={}）：{}",
          assistantId,
          executionId,
          triggerSource,
          failReason);
    }

    int elapsedMs = (int) ((System.nanoTime() - began) / 1_000_000L);
    List<WorkflowDebugService.TrailStep> trail = trailOf(plan);

    records.save(
        new WorkflowRecord(
            UUID.randomUUID(),
            assistantId,
            conversationId,
            input,
            trailToJson(trail),
            preview(output),
            statusOf(failReason, paused),
            failReason,
            elapsedMs,
            startedAt,
            Instant.now(),
            triggerSource));

    return new Outcome(
        failReason == null, failReason, output, paused, trail, elapsedMs, executionId);
  }

  /** 一次运行的结果（不含"编译失败"，那种情况调用方连记录都不该产生）。 */
  public record Outcome(
      boolean ok,
      String failReason,
      String output,
      boolean paused,
      List<WorkflowDebugService.TrailStep> trail,
      int elapsedMs,
      UUID executionId) {}

  /**
   * 只写一行失败记录 —— 用在"根本没跑起来"的情形（没有已发布版本 / 图读不出来 / 图编不过）。
   *
   * <p>★ 为什么也要落记录：定时触发是<strong>没人盯着</strong>的路径。不落记录的话，"配了定时但从来没跑过" 与"跑了但每次都失败"在界面上完全一样 ——
   * 而这正是本项目反复出现的静默失败形状。
   *
   * @param startedAt 这次尝试的时刻（与真实运行一样进 {@code started_at}，让它在列表里排在该在的位置）
   */
  public void recordFailure(
      UUID assistantId, String input, String failReason, Instant startedAt, String triggerSource) {
    records.save(
        new WorkflowRecord(
            UUID.randomUUID(),
            assistantId,
            null,
            input,
            "[]",
            "",
            "FAILED",
            failReason,
            0,
            startedAt,
            Instant.now(),
            triggerSource));
  }

  /** 轨迹取自编译后的计划：节点顺序与类型都在计划里，不需要再读一遍节点执行记录表。 */
  private static List<WorkflowDebugService.TrailStep> trailOf(WorkflowCompiler.ExecutionPlan plan) {
    List<WorkflowDebugService.TrailStep> trail = new ArrayList<>();
    for (WorkflowCompiler.PlanStep step : plan.steps()) {
      trail.add(new WorkflowDebugService.TrailStep(step.nodeId(), step.type(), step.order()));
    }
    return trail;
  }

  /**
   * 轨迹序列化成 JSON。
   *
   * <p>★ 手写而不是用序列化器：这里只有三个字段、形状固定，引一个序列化器要处理泛型与转义，反而更容易在中文节点名上出错。
   */
  static String trailToJson(List<WorkflowDebugService.TrailStep> trail) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < trail.size(); i++) {
      WorkflowDebugService.TrailStep step = trail.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"nodeId\":\"")
          .append(escape(step.nodeId()))
          .append("\",")
          .append("\"type\":\"")
          .append(escape(step.type()))
          .append("\",")
          .append("\"order\":")
          .append(step.order())
          .append('}');
    }
    return sb.append(']').toString();
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * 运行记录的状态。
   *
   * <p>★ 三档而不是两档：中断既不是成功也不是失败 —— 它表示「停在这里、状态还在、可以接着跑」。 把它算成 FAILED 会让「跑过但停在中途」在列表里与「跑挂了」长得一样。
   */
  static String statusOf(String failReason, boolean paused) {
    if (paused) {
      return "PAUSED";
    }
    return failReason == null ? "SUCCEEDED" : "FAILED";
  }

  /**
   * 输出预览。
   *
   * <p>★ 空输出返回 <b>空串</b>而不是 {@code null}：{@code as_workflow_record.final_output_preview} 是 {@code
   * text not null}（{@code V62__assistant_workflow_record.sql:37}）。这个差别只有真栈能发现 —— 单元测试用的是 Mock
   * 仓储，不校验 SQL 约束，所以它在单元测试里一直是绿的。
   */
  private static String preview(String output) {
    if (output == null || output.isBlank()) {
      return "";
    }
    return output.length() <= PREVIEW_LIMIT ? output : output.substring(0, PREVIEW_LIMIT);
  }
}
