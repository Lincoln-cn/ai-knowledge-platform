package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 判据 4（只跑线性 4 节点）+ 判据 5（NodeExecutor 注册表按 type 分发）。
 *
 * <p>★ 纯逻辑测试：不碰数据库，用 mock 仓储 + 记录调用顺序的假执行器。L1 事务回滚那条在 {@code LinearWorkflowExecutorL1Test}（真库）。
 */
@DisplayName("判据4/5 · 最小执行器：只跑线性 4 节点 + NodeExecutor 注册表按 type 分发")
class LinearWorkflowExecutorTest {

  /** 记录节点调用顺序的假执行器。 */
  private static final class Stub implements NodeExecutor {
    private final String type;
    private final List<String> calls;

    Stub(String type, List<String> calls) {
      this.type = type;
      this.calls = calls;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public void execute(WorkflowNodeContext ctx) {
      calls.add(ctx.nodeId());
    }
  }

  private static WorkflowCompiler.PlanStep step(
      String nodeId, String type, int order, String handle) {
    return new WorkflowCompiler.PlanStep(nodeId, type, order, handle);
  }

  private static LinearWorkflowExecutor executorWith(List<String> calls) {
    WorkflowNodeExecutionRepository repo = Mockito.mock(WorkflowNodeExecutionRepository.class);
    return new LinearWorkflowExecutor(
        repo,
        List.of(
            new Stub("START_NODE", calls),
            new Stub("SEARCH_DATASET_NODE", calls),
            new Stub("AI_CHAT_NODE", calls),
            new Stub("REPLY_NODE", calls)));
  }

  @Test
  @DisplayName("线性 4 节点计划逐节点按拓扑序跑（判据 4 结构 + 判据 5 注册表分发）")
  void runsLinearFourNodePlan() {
    List<String> calls = new ArrayList<>();
    LinearWorkflowExecutor executor = executorWith(calls);
    executor.execute(
        UUID.randomUUID(),
        new WorkflowCompiler.ExecutionPlan(
            List.of(
                step("s", "START_NODE", 0, null),
                step("d", "SEARCH_DATASET_NODE", 1, null),
                step("a", "AI_CHAT_NODE", 2, null),
                step("r", "REPLY_NODE", 3, null)),
            List.of()),
        "in");

    assertThat(calls).as("4 个节点按拓扑序各跑一次（注册表按 type 分发到各执行器）").containsExactly("s", "d", "a", "r");
  }

  @Test
  @DisplayName("条件节点被拒（本版本只跑线性 4 节点）")
  void rejectsConditionalNode() {
    LinearWorkflowExecutor executor = executorWith(new ArrayList<>());
    assertThatThrownBy(
            () ->
                executor.execute(
                    UUID.randomUUID(),
                    new WorkflowCompiler.ExecutionPlan(
                        List.of(
                            step("s", "START_NODE", 0, null), step("c", "CONDITION_NODE", 1, null)),
                        List.of()),
                    "in"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CONDITION_NODE");
  }

  @Test
  @DisplayName("人工介入节点被拒（本版本不支持人工介入）")
  void rejectsHumanInTheLoop() {
    LinearWorkflowExecutor executor = executorWith(new ArrayList<>());
    assertThatThrownBy(
            () ->
                executor.execute(
                    UUID.randomUUID(),
                    new WorkflowCompiler.ExecutionPlan(
                        List.of(
                            step("s", "START_NODE", 0, null), step("q", "QUESTION_NODE", 1, null)),
                        List.of()),
                    "in"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUESTION_NODE");
  }

  @Test
  @DisplayName("带分支进入（branchHandle）的计划被拒（只跑线性）")
  void rejectsBranchEntry() {
    LinearWorkflowExecutor executor = executorWith(new ArrayList<>());
    assertThatThrownBy(
            () ->
                executor.execute(
                    UUID.randomUUID(),
                    new WorkflowCompiler.ExecutionPlan(
                        List.of(
                            step("s", "START_NODE", 0, null), step("r", "REPLY_NODE", 1, "true")),
                        List.of()),
                    "in"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("线性");
  }
}
