package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

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

  @Test
  @DisplayName("问题优化：implemented=true 但执行器没有它的执行器，同样在发布前拦住")
  void questionNodeIsRejectedAtPublishTime() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("a", "START_NODE"), node("b", "QUESTION_NODE"), node("c", "AI_CHAT_NODE")),
                List.of(edge("a", "b"), edge("b", "c"))));

    assertThat(codes(result)).contains("NODE_NOT_EXECUTABLE");
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
