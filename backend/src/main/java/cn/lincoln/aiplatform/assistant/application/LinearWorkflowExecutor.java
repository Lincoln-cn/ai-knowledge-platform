package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /**
   * 本版本执行器真正能跑的节点类型。
   *
   * <p>★ 对外可见是因为 {@link WorkflowValidationService} 要用它做<strong>发布前</strong>拦截： 这个集合之外的类型在运行时才会抛
   * {@code IllegalArgumentException}，而发布校验不拦就等于 "发布成功、点试用时 500"。判据必须与执行器读同一份常量，不许各写一份 —— 两处各写一份，
   * 将来加一个节点类型时必然漏改一处。
   */
  public static final Set<String> SUPPORTED_TYPES =
      Set.of("START_NODE", "SEARCH_DATASET_NODE", "CONDITION_NODE", "AI_CHAT_NODE", "REPLY_NODE");

  /** 流程状态里放「这次走的是哪条分支」的键：值 = 条件节点那条出边的 {@code sourceHandle}。 */
  public static final String ACTIVE_BRANCH = "active_branch";

  private static final Logger log = LoggerFactory.getLogger(LinearWorkflowExecutor.class);

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
    executeAndReturn(executionId, plan, input);
  }

  /**
   * 与 {@link #execute} 同一条执行，但把最终状态交回给调用方。
   *
   * <p>★ 为什么加这个方法而不是把 {@code execute} 改成有返回值：{@code execute} 有四个测试直接用，改签名等于让它们跟着改 ——
   * 而这几个测试测的是"节点按顺序跑、状态按语义写"，与"要不要拿到返回值"无关。多一个方法，测试一行不用动。
   *
   * <p>★ 事务语义与 {@code execute} 完全一致（节点抛异常整批回滚）。调用方若要**在失败时也留痕**，必须像 {@code WorkflowDebugService}
   * 那样先执行、再用自己的事务写记录 —— 记录放进这个事务会在失败时一起消失。
   */
  @Transactional(rollbackFor = Exception.class)
  public FlowState executeAndReturn(
      UUID executionId, WorkflowCompiler.ExecutionPlan plan, String input) {
    requireRunnablePlan(plan);
    FlowState state = new FlowState();
    for (WorkflowCompiler.PlanStep step : plan.steps()) {
      // ★ 条件分支：这一步落在某条分支上，而当前激活的不是那一条 ⇒ 整步跳过。
      //
      // 编译器把分支 id 沿拓扑序传播到整条链（见 WorkflowCompiler 里 branchOf 那段注释），
      // 所以这里不需要知道"汇合点在哪"—— 汇合点的 branchHandle 是 null，天然放行。
      // 跳过的节点**不写执行记录**：写了会让运行记录里出现"跑过但没做任何事"的节点，
      // 而用户看记录是为了知道"这次到底走了哪条路"。
      String active = state.read(LinearWorkflowExecutor.ACTIVE_BRANCH);
      if (step.branchHandle() != null && active != null && !step.branchHandle().equals(active)) {
        log.debug(
            "#workflow 跳过分支外节点：node={} branch={} 本次走的是 {}",
            step.nodeId(),
            step.branchHandle(),
            active);
        continue;
      }
      NodeExecutor executor = registry.get(step.type());
      if (executor == null) {
        throw new IllegalStateException("没有类型 " + step.type() + " 的节点执行器");
      }
      try {
        executor.execute(
            new WorkflowNodeContext(
                executionId, step.nodeId(), step.type(), input, state, step.config()));
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
    return state;
  }

  /**
   * 只放行本版本真能跑的节点类型。
   *
   * <p>★ 0.7.2 之前这里还额外拒绝「任何带 {@code branchHandle} 的计划」—— 因为那时执行器不会跳过分支，
   * 一张有条件节点的图跑起来会把两边都执行一遍。现在分支跳过已经实现，这条限制随之取消； 但**条件节点本身仍要在 {@link #SUPPORTED_TYPES}
   * 里**，否则发布校验会先拦住。
   */
  private void requireRunnablePlan(WorkflowCompiler.ExecutionPlan plan) {
    List<WorkflowCompiler.PlanStep> steps = plan.steps();
    if (steps == null || steps.isEmpty()) {
      throw new IllegalArgumentException("执行计划为空");
    }
    if (!START.equals(steps.get(0).type())) {
      throw new IllegalArgumentException("线性计划必须以 START_NODE 开头");
    }
    for (WorkflowCompiler.PlanStep step : steps) {
      if (!SUPPORTED_TYPES.contains(step.type())) {
        throw new IllegalArgumentException("本版本只跑 " + SUPPORTED_TYPES + " 这些节点，不支持 " + step.type());
      }
    }
  }
}
