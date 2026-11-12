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
 * 最小执行器（{@code #113} 判据 4）：接受由 {@code START / SEARCH_DATASET / AI_CHAT / CONDITION / REPLY} 五种节点
 * 组成的计划，逐节点执行、每节点写一条执行记录。
 *
 * <p>★ <strong>条件分支按 {@link #ACTIVE_BRANCH} 跳过</strong>（0.7.2 起）：条件节点执行时把选中的分支 id 写进 {@code
 * FlowState} 的 {@link #ACTIVE_BRANCH}，之后带 {@code branchHandle} 且与它不等的节点直接跳过、<strong>不写执行记录</strong>
 * （写了会让运行记录里出现"跑过但没做任何事"的节点）。{@code branchHandle} 由 {@link WorkflowCompiler} 沿入边向下传播， 汇合点（入边多于一条）回到
 * {@code null}，因此汇合之后的节点照常执行。
 *
 * <p>★ <strong>AI 与回复节点是真实现，不是桩</strong>：{@code AI_CHAT} 走 {@code platform.model.ChatPort}、 {@code
 * REPLY} 从 {@code FlowState} 取正文 —— 正因为端口在 {@code platform} 模块，才不必反过来依赖 {@code conversation} 的
 * {@code ChatService}（那样 {@code ModuleBoundaryTest} 会红）。
 *
 * <p>★ <strong>仍未实现</strong>：人工介入（{@code QUESTION_NODE}，执行中暂停等待输入）与循环 —— 它们不在 {@link
 * #SUPPORTED_TYPES} 里，发布时会被拦。
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
      Set.of(
          "START_NODE",
          "BASE_NODE",
          "SEARCH_DATASET_NODE",
          "CONDITION_NODE",
          "NUMBER_CONDITION_NODE",
          "AI_CHAT_NODE",
          "REPLY_NODE");

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
  @Transactional(rollbackFor = Exception.class, noRollbackFor = WorkflowInterrupted.class)
  public FlowState executeAndReturn(
      UUID executionId, WorkflowCompiler.ExecutionPlan plan, String input) {
    requireRunnablePlan(plan);
    FlowState state = new FlowState();
    int stepsDone = 0;
    for (WorkflowCompiler.PlanStep step : plan.steps()) {
      stepsDone = runOne(executionId, step, plan, input, state, stepsDone);
    }
    return state;
  }

  /**
   * 从一份已有的状态与第 {@code resumeAfter} 步之后接着跑。
   *
   * <p>★ 用「已经走完的步数」而不是节点 id 作为起点：同一个图里同一个节点可能出现在不同计划里， 而步数是这份计划的局部量 ——
   * 但它也意味着**计划变了就不能恢复**，所以调用方要先比一次 {@code WorkflowPause.stepsDone}。
   *
   * <p>★ 跳过前 {@code resumeAfter} 步，不是「再跑一遍」：已经落过执行记录的节点不重跑， 否则记录表里会出现同一步两次。
   */
  @Transactional(rollbackFor = Exception.class, noRollbackFor = WorkflowInterrupted.class)
  public FlowState resumeAndReturn(
      UUID executionId,
      WorkflowCompiler.ExecutionPlan plan,
      String input,
      int resumeAfter,
      FlowState resumed) {
    requireRunnablePlan(plan);
    List<WorkflowCompiler.PlanStep> steps = plan.steps();
    if (resumeAfter < 0 || resumeAfter > steps.size()) {
      throw new IllegalArgumentException(
          "恢复点越界：" + resumeAfter + " 不在 [0, " + steps.size() + "] 内");
    }
    FlowState state = resumed;
    int stepsDone = resumeAfter;
    for (int i = resumeAfter; i < steps.size(); i++) {
      WorkflowCompiler.PlanStep step = steps.get(i);
      stepsDone = runOne(executionId, step, plan, input, state, stepsDone);
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
      // ★ 中断点不算「跑不了」：人工介入节点本来就没有执行器 —— 它要做的是「等人」，
      // 不是「跑点什么」。所以它在 SUPPORTED_TYPES 之外也照常放行，执行到它那里会停下来。
      if (plan.interruptNodeIds().contains(step.nodeId())) {
        continue;
      }
      if (!SUPPORTED_TYPES.contains(step.type())) {
        throw new IllegalArgumentException("本版本只跑 " + SUPPORTED_TYPES + " 这些节点，不支持 " + step.type());
      }
    }
  }

  /**
   * 跑一步，返回新的「已走步数」。
   *
   * <p>★ 被分支跳过、或停在中断点时都返回**原值** —— 那几步不算走完， 恢复时要从中断点本身重来。
   */
  private int runOne(
      UUID executionId,
      WorkflowCompiler.PlanStep step,
      WorkflowCompiler.ExecutionPlan plan,
      String input,
      FlowState state,
      int stepsDone) {
    // ★ 条件分支：这一步落在某条分支上，而当前激活的不是那一条 ⇒ 整步跳过。
    //
    // 编译器把分支 id 沿拓扑序传播到整条链（见 WorkflowCompiler 里 branchOf 那段注释），
    // 所以这里不需要知道「汇合点在哪」—— 汇合点的 branchHandle 是 null，天然放行。
    // 跳过的节点**不写执行记录**：写了会让运行记录里出现「跑过但没做任何事」的节点，
    // 而用户看记录是为了知道「这次到底走了哪条路」。
    String active = state.read(LinearWorkflowExecutor.ACTIVE_BRANCH);
    if (step.branchHandle() != null && active != null && !step.branchHandle().equals(active)) {
      log.debug(
          "#workflow 跳过分支外节点：node={} branch={} 本次走的是 {}",
          step.nodeId(),
          step.branchHandle(),
          active);
      return stepsDone;
    }
    // ★ 人工介入：编译器把这类节点 id 填进 interruptNodeIds（这个字段从 0.7.0 起就有，
    //   但执行器一直没读过它 —— 于是「人工介入节点」跑起来当普通节点执行完、继续往下走，
    //   用户看不出中间少了什么）。现在到这儿就停：抛出去，让调用方把状态存住。
    //   ★ 必须在 registry.get 之前：中断点本来没有执行器（它要做的是「等人」，不是「跑点什么」），
    //     放到后面会先撞「没有类型的执行器」。
    if (plan.interruptNodeIds().contains(step.nodeId())) {
      log.info("#workflow 停在人工介入节点：node={} type={} 已走 {} 步", step.nodeId(), step.type(), stepsDone);
      throw new WorkflowInterrupted(executionId, step.nodeId(), step.type(), stepsDone, state);
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
            UUID.randomUUID(), executionId, step.nodeId(), step.type(), step.order(), "SUCCEEDED"));
    return stepsDone + 1;
  }
}
