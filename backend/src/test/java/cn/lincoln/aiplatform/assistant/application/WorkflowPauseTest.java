package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 人工介入节点的中断（0.7.4 第 1 步）。
 *
 * <p>★ 为什么这条测试必须走**编译器**而不是手写 {@code ExecutionPlan}： 中断点清单（{@code interruptNodeIds}）是编译器填的 ——
 * 手写计划时第二个参数给个空 list，{@code QUESTION_NODE} 就不在中断点里， 于是它被当成「跑不了的节点」直接拒绝， 测试会绿，但测的是另一件事。
 */
class WorkflowPauseTest {

  /** 一个把配置原样吐出去的执行器，用来造「跑到它就不会失败」的节点。 */
  private static NodeExecutor passthrough(String type) {
    return new NodeExecutor() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public void execute(WorkflowNodeContext context) {
        context.state().write(type + "_done", "1", KeyUpdate.REPLACE);
      }
    };
  }

  private static WorkflowCompiler.Graph graphWithQuestion() {
    Map<String, Object> startCfg =
        Map.of("config", Map.of("fields", List.of(Map.of("name", "entry", "type", "TEXT"))));
    Map<String, Object> questionCfg =
        Map.of("config", Map.of("fields", List.of(Map.of("name", "dept", "type", "TEXT"))));
    return new WorkflowCompiler.Graph(
        List.of(
            new WorkflowCompiler.Node("n-start", "START_NODE", startCfg),
            new WorkflowCompiler.Node("n-ask", "QUESTION_NODE", questionCfg)),
        List.of(new WorkflowCompiler.Edge("n-start", "n-ask", null)));
  }

  @Test
  void stopsAtHumanNodeAndKeepsWhatRanBeforeIt() {
    WorkflowNodeExecutionRepository records = mock(WorkflowNodeExecutionRepository.class);
    LinearWorkflowExecutor executor =
        new LinearWorkflowExecutor(records, List.of(new StartNodeExecutor()));
    WorkflowCompiler.CompileResult compiled =
        new WorkflowCompiler(new NodeTypeCatalogProvider().catalog()).compile(graphWithQuestion());
    assertThat(compiled.ok()).as("这张图必须能编译通过，否则测不到中断").isTrue();
    assertThat(compiled.plan().interruptNodeIds()).as("编译器要把人工介入节点登记成中断点").contains("n-ask");

    UUID executionId = UUID.randomUUID();
    assertThatThrownBy(() -> executor.executeAndReturn(executionId, compiled.plan(), "你好"))
        .isInstanceOf(WorkflowInterrupted.class)
        .satisfies(
            ex -> {
              WorkflowInterrupted interrupted = (WorkflowInterrupted) ex;
              assertThat(interrupted.nodeId()).isEqualTo("n-ask");
              assertThat(interrupted.nodeType()).isEqualTo("QUESTION_NODE");
              assertThat(interrupted.stepsDone()).as("停在中断点时，它前面那一步（START）应该已经算走过了").isEqualTo(1);
              assertThat(interrupted.stateSnapshot())
                  .as("START 把入口输入写进 query；它要在快照里 —— 恢复时要靠它接着跑")
                  .containsKey("query");
            });

    // 中断点自己不许被执行：它没有执行器，被调用就是配置错了
    // 只有 START 那一步落了记录；中断点自己不许被执行（它没有执行器）
    verify(records, times(1)).save(any());
  }

  @Test
  void planWithoutInterruptPointsStillRejectsUnsupportedTypes() {
    // 反面对照：手写计划、中断点为空 ⇒ 仍然走「本版本不支持」那条拒绝路径，
    // 而不是被放行。两条路径不能混成一个。
    WorkflowNodeExecutionRepository records = mock(WorkflowNodeExecutionRepository.class);
    LinearWorkflowExecutor executor =
        new LinearWorkflowExecutor(records, List.of(new StartNodeExecutor()));
    WorkflowCompiler.PlanStep question =
        new WorkflowCompiler.PlanStep("n-ask", "QUESTION_NODE", 1, null, Map.of());

    assertThatThrownBy(
            () ->
                executor.executeAndReturn(
                    UUID.randomUUID(),
                    new WorkflowCompiler.ExecutionPlan(
                        List.of(
                            new WorkflowCompiler.PlanStep(
                                "n-start", "START_NODE", 0, null, Map.of()),
                            question),
                        List.of()),
                    "你好"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
  }

  /** 造一个真目录：走 `node-types.json`，与生产同一条路。 */
  private static final class NodeTypeCatalogProvider {
    private final NodeTypeCatalog catalog;

    NodeTypeCatalogProvider() {
      this.catalog = new NodeTypeCatalog(tools.jackson.databind.json.JsonMapper.builder().build());
    }

    NodeTypeCatalog catalog() {
      return catalog;
    }
  }
}
