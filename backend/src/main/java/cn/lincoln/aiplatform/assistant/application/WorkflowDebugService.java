package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 编排应用的「试用」（{@code #326} 第 1–3 层；契约 {@code debugWorkflow}）。
 *
 * <p>★ 这个类存在的理由：在此之前画布的「试用」是<strong>前端本地按线性顺序跑</strong>（{@code api/workflow.ts:63} 的 {@code
 * debugLinear}），后端执行引擎虽然能跑，却没有任何请求能到它 —— 于是 {@code as_workflow_record} 恒空、 {@code
 * listWorkflowRecords} 也恒空，两处都没有异常。<strong>「读数为 0」与「功能没接」长得一样</strong>，这是本项目第七次。
 *
 * <p>★ <strong>为什么执行与写记录不在同一个事务里</strong>：{@code LinearWorkflowExecutor#executeAndReturn}
 * 在节点抛异常时整批回滚 （L1：失败那跑的节点记录不得显示成功）。如果记录也放进那个事务，<strong>失败的试用就不会留下任何痕迹</strong> ——
 * 而失败恰恰是最需要回查的那一次。所以这里先执行、拿到成败，再用自己的事务写一行记录（{@code status} 区分 {@code SUCCEEDED} / {@code FAILED}）。
 *
 * <p>★ 调试可以写记录这件事<strong>与 {@link WorkflowRecordRepository} 原来的 javadoc 相反</strong>：那里写着"运行记录刻意不在调试
 * 时写入"，理由是调试不是正式会话、写进去会污染统计。现在改成写，是因为<strong>不写就无从证明执行引擎真的跑过</strong> —— 宁可让统计口径里多一类 {@code
 * conversationId} 为空的记录（可筛），也不要留一个"跑成功了但查不到"的黑洞。那段 javadoc 已同步改写。
 */
@Service
public class WorkflowDebugService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowDebugService.class);

  /** 最终输出的预览长度上限（与列表页展示口径一致，避免记录表存下整段长文本）。 */
  private static final int PREVIEW_LIMIT = 500;

  private final NodeTypeCatalog catalog;
  private final LinearWorkflowExecutor executor;
  private final WorkflowRecordRepository records;

  public WorkflowDebugService(
      NodeTypeCatalog catalog, LinearWorkflowExecutor executor, WorkflowRecordRepository records) {
    this.catalog = catalog;
    this.executor = executor;
    this.records = records;
  }

  /**
   * 跑一次试用并落一行运行记录。
   *
   * @param assistantId 编排应用
   * @param graph 画布当前的图（<strong>用请求里带的草稿，不用已发布的版本</strong>：试用的意义就是"发布之前先看看"）
   * @param inputs 按节点 id 提供的输入，缺省时用调用方给的那一段文本
   */
  public Result debug(UUID assistantId, WorkflowCompiler.Graph graph, String inputs) {
    WorkflowCompiler.CompileResult compiled = new WorkflowCompiler(catalog).compile(graph);
    if (!compiled.ok()) {
      // ★ 编不过就不该产生运行记录：那不是"一次失败的运行"，是"这张图还不成立"。
      return Result.rejected(compiled.errors());
    }

    UUID executionId = UUID.randomUUID();
    Instant startedAt = Instant.now();
    long began = System.nanoTime();

    String failReason = null;
    String output = null;
    try {
      FlowState state = executor.executeAndReturn(executionId, compiled.plan(), inputs);
      output = state.read("reply");
    } catch (RuntimeException ex) {
      failReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      LOG.warn("#工作流试用 执行失败（assistant={}，execution={}）：{}", assistantId, executionId, failReason);
    }

    int elapsedMs = (int) ((System.nanoTime() - began) / 1_000_000L);
    List<TrailStep> trail = trailOf(compiled.plan());

    WorkflowRecord record =
        new WorkflowRecord(
            UUID.randomUUID(),
            assistantId,
            null, // 调试不是正式会话：conversationId 留空，统计口径据此把"试用"与"真问答"分开筛
            inputs,
            trailToJson(trail),
            preview(output),
            failReason == null ? "SUCCEEDED" : "FAILED",
            failReason,
            elapsedMs,
            startedAt,
            Instant.now());
    records.save(record);

    return new Result(true, null, output, trail, elapsedMs, executionId);
  }

  /** 轨迹取自编译后的计划：节点顺序与类型都在计划里，不需要再读一遍节点执行记录表。 */
  private static List<TrailStep> trailOf(WorkflowCompiler.ExecutionPlan plan) {
    List<TrailStep> trail = new ArrayList<>();
    for (WorkflowCompiler.PlanStep step : plan.steps()) {
      trail.add(new TrailStep(step.nodeId(), step.type(), step.order()));
    }
    return trail;
  }

  /**
   * 轨迹序列化成 JSON。
   *
   * <p>★ 手写而不是用序列化器：这里只有三个字段、形状固定，引一个序列化器要处理泛型与转义，反而更容易在中文节点名上出错。
   */
  private static String trailToJson(List<TrailStep> trail) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < trail.size(); i++) {
      TrailStep step = trail.get(i);
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

  private static String preview(String output) {
    if (output == null) {
      return null;
    }
    return output.length() <= PREVIEW_LIMIT ? output : output.substring(0, PREVIEW_LIMIT);
  }

  /**
   * 试用结果（契约 {@code DebugWorkflowResult}）。
   *
   * @param ok 图能不能跑（{@code false} 表示编译就没过，此时 {@code errors} 非空、没有产生运行记录）
   * @param errors 编译错误（给人看的句子，不是错误码）
   */
  public record Result(
      boolean ok,
      List<String> errors,
      String finalOutput,
      List<TrailStep> nodeTrail,
      int elapsedMs,
      UUID executionId) {

    static Result rejected(List<String> errors) {
      return new Result(
          false, errors == null ? List.of() : List.copyOf(errors), null, List.of(), 0, null);
    }
  }

  /** 轨迹的一步（与前端 {@code WorkflowRecord['nodeTrail']} 对齐）。 */
  public record TrailStep(String nodeId, String type, int order) {}

  /** 给调用方按节点 id 组输入时用的空值：没有输入就是空串，不是 {@code null}。 */
  public static Map<String, String> emptyInputs() {
    return new LinkedHashMap<>();
  }
}
