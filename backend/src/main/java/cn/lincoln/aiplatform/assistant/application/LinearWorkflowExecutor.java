package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 最小执行器（{@code #113} 判据 4）：只接受由 {@code START / SEARCH_DATASET / AI_CHAT / REPLY} 四种节点
 * <strong>线性</strong>组成的计划，逐节点执行、每节点写一条执行记录。
 *
 * <p>★ <strong>只跑线性 4 节点</strong>：条件分支、循环、人工介入一律拒绝（不在 {@link #SUPPORTED_TYPES} 里、或带 {@code
 * branchHandle} 的计划直接抛）。真跑 {@code AI_CHAT / REPLY} 需接 {@code conversation} 的 {@code
 * ChatService}，会越模块边界，本版本用可注入的 {@link NodeExecutor} 假实现代替（见 {@link NodeExecutor} 注释）。
 *
 * <p>★ <strong>L1 检查点 = 每节点执行记录与业务同事务</strong>（判据 4）：{@link #execute} 上的
 * {@code @Transactional(rollbackFor = Exception.class)} 保证「执行节点 + 写节点记录」原子 —— 故意让某节点抛异常，这一跑的
 * 节点记录就整体回滚，{@code countByExecutionIdAndStatus(executionId, "SUCCEEDED")} 必须是 0，即
 * <strong>该次执行的节点记录不得显示成功</strong>。
 */
@Component
public class LinearWorkflowExecutor {

  private static final String START = "START_NODE";
  private static final Set<String> SUPPORTED_TYPES =
      Set.of("START_NODE", "SEARCH_DATASET_NODE", "AI_CHAT_NODE", "REPLY_NODE");

  private final WorkflowNodeExecutionRepository records;
  private final Map<String, NodeExecutor> registry;

  public LinearWorkflowExecutor(
      WorkflowNodeExecutionRepository records, List<NodeExecutor> executors) {
    this.records = records;
    Map<String, NodeExecutor> byType = new HashMap<>();
    for (NodeExecutor executor : executors) {
      byType.put(executor.type(), executor);
    }
    this.registry = Map.copyOf(byType);
  }

  /**
   * 跑一个线性计划（{@code executionId} 由调用方给定，便于失败后回查这次执行的节点记录）。
   *
   * <p>节点抛异常 ⇒ 整个事务回滚 ⇒ 该 {@code executionId} 下没有任何 {@code SUCCEEDED} 节点记录（L1）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void execute(UUID executionId, WorkflowCompiler.ExecutionPlan plan, String input) {
    requireLinearFourNode(plan);
    FlowState state = new FlowState();
    for (WorkflowCompiler.PlanStep step : plan.steps()) {
      NodeExecutor executor = registry.get(step.type());
      if (executor == null) {
        throw new IllegalStateException("没有类型 " + step.type() + " 的节点执行器");
      }
      try {
        executor.execute(new WorkflowNodeContext(executionId, step.nodeId(), step.type(), state));
      } catch (Exception ex) {
        // ★ 抛出去让 @Transactional 整体回滚 —— L1：失败那跑的节点记录不得显示成功。
        throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
      }
      records.save(
          new WorkflowNodeExecutionRecord(
              UUID.randomUUID(),
              executionId,
              step.nodeId(),
              step.type(),
              step.order(),
              "SUCCEEDED"));
    }
  }

  /** 只放行线性 4 节点计划；条件分支 / 循环 / 人工介入 / 其它类型一律拒绝。 */
  private void requireLinearFourNode(WorkflowCompiler.ExecutionPlan plan) {
    List<WorkflowCompiler.PlanStep> steps = plan.steps();
    if (steps == null || steps.isEmpty()) {
      throw new IllegalArgumentException("执行计划为空");
    }
    if (!START.equals(steps.get(0).type())) {
      throw new IllegalArgumentException("线性计划必须以 START_NODE 开头");
    }
    for (WorkflowCompiler.PlanStep step : steps) {
      if (step.branchHandle() != null) {
        throw new IllegalArgumentException("本版本只接受线性计划，条件分支（" + step.nodeId() + "）不支持");
      }
      if (!SUPPORTED_TYPES.contains(step.type())) {
        throw new IllegalArgumentException(
            "本版本只跑 " + SUPPORTED_TYPES + " 四种线性节点，不支持 " + step.type());
      }
    }
  }
}
