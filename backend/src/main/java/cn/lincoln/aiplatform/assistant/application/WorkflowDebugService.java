package cn.lincoln.aiplatform.assistant.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 编排应用的「试用」（{@code #326} 第 1–3 层；契约 {@code debugWorkflow}）。
 *
 * <p>★ 这个类存在的理由：在此之前画布的「试用」是<strong>前端本地按线性顺序跑</strong>（{@code api/workflow.ts:63} 的 {@code
 * debugLinear}），后端执行引擎虽然能跑，却没有任何请求能到它 —— 于是 {@code as_workflow_record} 恒空、 {@code
 * listWorkflowRecords} 也恒空，两处都没有异常。<strong>「读数为 0」与「功能没接」长得一样</strong>，这是本项目第七次。
 *
 * <p>★ 0.7.3 起<strong>跑与写记录都交给 {@link WorkflowRunRecorder}</strong>（{@code #295}）：定时触发也要
 * 跑同一张图、写同一张表，而"接中断、判三档状态、截预览"这些细节各写一份的话，两处迟早分叉 —— 一边把中断算成 FAILED、另一边算成
 * PAUSED，各自都"看起来对"。这里只剩"图从哪来"这一件它还负责的事。
 */
@Service
public class WorkflowDebugService {

  private final NodeTypeCatalog catalog;
  private final WorkflowRunRecorder recorder;

  public WorkflowDebugService(NodeTypeCatalog catalog, WorkflowRunRecorder recorder) {
    this.catalog = catalog;
    this.recorder = recorder;
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

    WorkflowRunRecorder.Outcome outcome =
        recorder.run(assistantId, compiled.plan(), inputs, null, "MANUAL");

    // ★ ok 必须跟着结果走，不能写死 true：真栈验证时这里返回 ok=True 而记录是 FAILED，
    //   前端「试用」对话框会显示"成功"、记录表里却是失败 —— 正是项目一直在批的静默失败形状。
    //   失败时把原因放进 errors，调用方不用再去查记录表才知道为什么没跑成。
    List<String> errors = outcome.ok() ? null : List.of(outcome.failReason());
    return new Result(
        outcome.ok(),
        errors,
        outcome.output(),
        outcome.trail(),
        outcome.elapsedMs(),
        outcome.executionId());
  }

  /**
   * 试用结果（契约 {@code DebugWorkflowResult}）。
   *
   * @param ok 图能不能跑（{@code false} 表示编译就没过或执行失败，此时 {@code errors} 非空）
   * @param errors 编译错误或失败原因（给人看的句子，不是错误码）
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
