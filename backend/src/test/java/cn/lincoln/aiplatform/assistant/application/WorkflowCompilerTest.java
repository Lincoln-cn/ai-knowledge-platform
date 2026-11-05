package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 判据 1（{@code #113}）：只编译不跑 —— 给定图，单独断言产出的执行计划（拓扑序 / 条件位置 / 中断点清单）。 */
@DisplayName("判据1 · 只编译不跑：图 → 执行计划（拓扑序 / 条件位置 / 中断点清单）")
class WorkflowCompilerTest {

  private final WorkflowCompiler compiler =
      new WorkflowCompiler(new NodeTypeCatalog(new ObjectMapper()));

  private static WorkflowCompiler.Node node(String id, String type) {
    return new WorkflowCompiler.Node(id, type);
  }

  private static WorkflowCompiler.Edge edge(String source, String target, String handle) {
    return new WorkflowCompiler.Edge(source, target, handle);
  }

  @Test
  @DisplayName("线性图编译出拓扑序，branchHandle 全 null，无中断点")
  void compilesLinearGraphInTopologicalOrder() {
    WorkflowCompiler.CompileResult result =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(
                    node("s", "START_NODE"),
                    node("d", "SEARCH_DATASET_NODE"),
                    node("a", "AI_CHAT_NODE"),
                    node("r", "REPLY_NODE")),
                List.of(edge("s", "d", null), edge("d", "a", null), edge("a", "r", null))));

    assertThat(result.ok()).as("线性图必须编译成功").isTrue();
    List<WorkflowCompiler.PlanStep> steps = result.plan().steps();
    assertThat(steps)
        .extracting(WorkflowCompiler.PlanStep::nodeId)
        .as("拓扑序必须是 START→SEARCH→AI_CHAT→REPLY")
        .containsExactly("s", "d", "a", "r");
    assertThat(steps)
        .extracting(WorkflowCompiler.PlanStep::order)
        .as("order 必须是 0,1,2,3 的拓扑序")
        .containsExactly(0, 1, 2, 3);
    assertThat(steps)
        .allSatisfy(
            step -> assertThat(step.branchHandle()).as("线性路径每步的 branchHandle 必须是 null").isNull());
    assertThat(result.plan().interruptNodeIds()).as("纯线性已实现节点没有中断点").isEmpty();
  }

  @Test
  @DisplayName("条件分支：分支节点的 branchHandle 标出从条件节点哪个出口进来")
  void marksBranchHandleOnConditionalBranches() {
    WorkflowCompiler.CompileResult result =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(
                    node("s", "START_NODE"),
                    node("c", "CONDITION_NODE"),
                    node("t", "AI_CHAT_NODE"),
                    node("f", "REPLY_NODE")),
                List.of(edge("s", "c", null), edge("c", "t", "true"), edge("c", "f", "false"))));

    assertThat(result.ok()).as("条件分支图必须编译成功").isTrue();
    Map<String, String> handleByNode = new HashMap<>();
    for (WorkflowCompiler.PlanStep step : result.plan().steps()) {
      handleByNode.put(step.nodeId(), step.branchHandle());
    }
    assertThat(handleByNode.get("t")).as("true 分支必须标 branchHandle=true").isEqualTo("true");
    assertThat(handleByNode.get("f")).as("false 分支必须标 branchHandle=false").isEqualTo("false");
    assertThat(handleByNode.get("s")).as("线性入口 branchHandle=null").isNull();
    assertThat(handleByNode.get("c")).as("条件节点本身从线性路径进来，branchHandle=null").isNull();
  }

  @Test
  @DisplayName("中断点清单：人工介入节点 + 本版本不支持的节点都被点名")
  void listsInterruptNodes() {
    WorkflowCompiler.CompileResult human =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(
                    node("s", "START_NODE"), node("q", "QUESTION_NODE"), node("r", "REPLY_NODE")),
                List.of(edge("s", "q", null), edge("q", "r", null))));
    assertThat(human.ok()).isTrue();
    assertThat(human.plan().interruptNodeIds())
        .as("人工介入节点（QUESTION_NODE）必须进中断点清单")
        .containsExactly("q");

    WorkflowCompiler.CompileResult unplanned =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(
                    node("s", "START_NODE"), node("k", "RERANKER_NODE"), node("r", "REPLY_NODE")),
                List.of(edge("s", "k", null), edge("k", "r", null))));
    assertThat(unplanned.ok()).isTrue();
    assertThat(unplanned.plan().interruptNodeIds())
        .as("本版本不支持的节点（implemented=false，如 RERANKER）必须进中断点清单")
        .containsExactly("k");
  }

  @Test
  @DisplayName("成环 ⇒ 返回错误而不是抛")
  void returnsErrorOnCycle() {
    WorkflowCompiler.CompileResult result =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(node("a", "START_NODE"), node("b", "AI_CHAT_NODE")),
                List.of(edge("a", "b", null), edge("b", "a", null))));

    assertThat(result.ok()).as("成环必须编译失败").isFalse();
    assertThat(result.plan()).as("失败时 plan 为 null").isNull();
    assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("环"));
  }

  @Test
  @DisplayName("图不连通 ⇒ 返回错误而不是抛")
  void returnsErrorWhenDisconnected() {
    WorkflowCompiler.CompileResult result =
        compiler.compile(
            new WorkflowCompiler.Graph(
                List.of(
                    node("s", "START_NODE"),
                    node("a", "AI_CHAT_NODE"),
                    node("orphan", "REPLY_NODE")),
                List.of(edge("s", "a", null))));

    assertThat(result.ok()).as("不连通必须编译失败").isFalse();
    assertThat(result.plan()).isNull();
    assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("不连通"));
  }
}
