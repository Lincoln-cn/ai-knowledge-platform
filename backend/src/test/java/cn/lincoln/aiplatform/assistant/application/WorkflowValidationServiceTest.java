package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 发布前校验的规则（`#198` 第三块）：每条都问"这张图跑得起来吗"。 */
class WorkflowValidationServiceTest {

  private final WorkflowValidationService service =
      new WorkflowValidationService(new NodeTypeCatalog(new ObjectMapper()));

  private static WorkflowValidationService.Node node(String id, String type) {
    return new WorkflowValidationService.Node(id, type, "标题-" + id);
  }

  private static WorkflowValidationService.Edge edge(String source, String target) {
    return new WorkflowValidationService.Edge(source + "-" + target, source, target);
  }

  private static List<String> codes(WorkflowValidationService.Result result) {
    return result.issues().stream().map(WorkflowValidationService.Issue::code).sorted().toList();
  }

  @Test
  @DisplayName("空图：说清没节点，而不是报一堆下游错误")
  void emptyGraphIsOneClearIssue() {
    WorkflowValidationService.Result result =
        service.validate(new WorkflowValidationService.Graph(List.of(), List.of()));

    assertThat(result.passed()).isFalse();
    assertThat(codes(result)).containsExactly("EMPTY_GRAPH");
  }

  @Test
  @DisplayName("最小可运行图：开始 → AI 对话，通过且没有任何结论")
  void minimalRunnableGraphPasses() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(node("a", "START_NODE"), node("b", "AI_CHAT_NODE")),
                List.of(edge("a", "b"))));

    assertThat(codes(result)).isEmpty();
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("条件分支：0.7.2 之后能发布，不再被 NODE_NOT_EXECUTABLE 拦住")
  void conditionNodeIsNoLongerRejectedAtPublishTime() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"),
                    node("b", "CONDITION_NODE"),
                    node("c", "AI_CHAT_NODE")),
                List.of(edge("a", "b"), edge("b", "c"))));

    // 这条断言原来是反的：它守的是「条件分支在发布前就被拦住，而不是发布成功、
    // 点试用时才 500」。0.7.2 给了 CONDITION_NODE 一个真的执行器之后前提没了 ——
    // 现在该拦住的是「目录标了 implemented 却没有执行器」的类型，而 CONDITION_NODE
    // 两边都有。
    assertThat(codes(result)).doesNotContain("NODE_NOT_EXECUTABLE");
    assertThat(result.issues())
        .filteredOn(issue -> "NODE_NOT_EXECUTABLE".equals(issue.code()))
        .isEmpty();
  }

  /**
   * ★ 这条的判据在 {@code #335} 被订正过，原因值得记住：它原本拿 {@code QUESTION_NODE} 当"标了可执行却没有执行器" 的反例，而 {@code
   * 6c779130} 把 QUESTION_NODE 真的实现了 —— 于是它变成"已实现"、只报 INTERRUPT_NODE，测试转红。 这与 {@code
   * WorkflowPauseTest} 上一轮踩的是同一个坑：<strong>拿真实类型当反例</strong>。
   *
   * <p>更根本的问题是：{@code NODE_NOT_EXECUTABLE} 判的是"{@code catalog.executable(type)} 为真、但不在 {@code
   * LinearWorkflowExecutor.SUPPORTED_TYPES} 里"，而真实目录与执行器集合由 {@code
   * WorkflowNodeTypeTest.executableTypesMatchTheExecutor} 钉成<strong>完全相等</strong> ⇒ 用真目录永远构造不出这个
   * 场景。所以要验这一档，必须把"目录状态"注入进来：这里用替身目录说"FUTURE_NODE 能跑"，而引擎里没有它。
   */
  @Test
  @DisplayName("目录说能跑、执行引擎里没有：发布前拦住（用替身目录构造，不拿真实类型当反例）")
  void questionedTypeWithoutExecutorIsRejectedAtPublishTime() {
    NodeTypeCatalog catalog = mock(NodeTypeCatalog.class);
    when(catalog.known(anyString())).thenReturn(true);
    // 默认"目录里标着不能跑"；只有下面这几个是能跑的
    when(catalog.executable(anyString())).thenReturn(false);
    when(catalog.executable("START_NODE")).thenReturn(true);
    when(catalog.executable("AI_CHAT_NODE")).thenReturn(true);
    when(catalog.executable("FUTURE_NODE")).thenReturn(true);

    WorkflowValidationService.Result result =
        new WorkflowValidationService(catalog)
            .validate(
                new WorkflowValidationService.Graph(
                    List.of(
                        node("a", "START_NODE"),
                        node("b", "FUTURE_NODE"),
                        node("c", "AI_CHAT_NODE")),
                    List.of(edge("a", "b"), edge("b", "c"))));

    assertThat(codes(result)).contains("NODE_NOT_EXECUTABLE");
    // 精确一点：它不该同时被当成"未知类型"或"规划中" —— 那两条就说明替身目录没生效
    assertThat(codes(result)).doesNotContain("UNKNOWN_TYPE").doesNotContain("PLANNED_TYPE");
    assertThat(result.passed()).isFalse();
  }

  @Test
  @DisplayName("四种可执行节点：不报 NODE_NOT_EXECUTABLE（防这条规则一概拦死）")
  void fourExecutableTypesDoNotTriggerThatRule() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"),
                    node("b", "SEARCH_DATASET_NODE"),
                    node("c", "AI_CHAT_NODE"),
                    node("d", "REPLY_NODE")),
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "d"))));

    assertThat(codes(result)).doesNotContain("NODE_NOT_EXECUTABLE");
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("两个开始节点：入口不唯一")
  void twoStartsIsAnError() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"), node("b", "START_NODE"), node("c", "AI_CHAT_NODE")),
                List.of(edge("a", "c"))));

    assertThat(codes(result)).contains("MULTIPLE_START");
    assertThat(result.passed()).isFalse();
  }

  @Test
  @DisplayName("节点 id 重复：会把两条线接到同一个节点上")
  void duplicateNodeIdIsAnError() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(node("a", "START_NODE"), node("a", "AI_CHAT_NODE")), List.of()));

    assertThat(codes(result)).contains("DUPLICATE_NODE_ID");
    assertThat(result.passed()).isFalse();
  }

  @Test
  @DisplayName("未知类型是错误；规划中（implemented=false）类型本版本不支持 ⇒ 也是错误")
  void unknownTypeAndUnimplementedTypeAreBothErrors() {
    WorkflowValidationService.Result unknown =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"),
                    node("b", "SOMETHING_NEW_NODE"),
                    node("c", "AI_CHAT_NODE")),
                List.of(edge("a", "c"))));
    assertThat(codes(unknown)).contains("UNKNOWN_TYPE");
    assertThat(unknown.passed()).isFalse();

    WorkflowValidationService.Result planned =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"), node("b", "RERANKER_NODE"), node("c", "AI_CHAT_NODE")),
                List.of(edge("a", "b"), edge("b", "c"))));
    assertThat(codes(planned)).contains("PLANNED_TYPE");
    assertThat(planned.passed())
        .as(
            "新语义（#113 判据 2）：implemented=false = 本版本不支持 ⇒ PLANNED_TYPE 由 WARNING 升为 ERROR，"
                + "含它的图直接拒绝发布（收紧前这里断言的是 isTrue，即只警告仍放行）")
        .isFalse();
  }

  @Test
  @DisplayName("连线指向不存在的节点")
  void danglingEdgeIsAnError() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(node("a", "START_NODE"), node("b", "AI_CHAT_NODE")),
                List.of(edge("a", "b"), edge("b", "ghost"))));

    assertThat(codes(result)).contains("DANGLING_EDGE");
    assertThat(result.passed()).isFalse();
  }

  @Test
  @DisplayName("孤立节点是警告；连不到任何输出节点才是错误")
  void unreachableIsWarningButNoOutputIsError() {
    WorkflowValidationService.Result withOutput =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"), node("b", "AI_CHAT_NODE"), node("c", "REPLY_NODE")),
                List.of(edge("a", "b"))));
    assertThat(codes(withOutput)).contains("UNREACHABLE");
    assertThat(withOutput.passed()).isTrue();

    WorkflowValidationService.Result noOutput =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(node("a", "START_NODE"), node("b", "SEARCH_DATASET_NODE")),
                List.of(edge("a", "b"))));
    assertThat(codes(noOutput)).contains("NO_OUTPUT");
    assertThat(noOutput.passed()).isFalse();
  }
}
