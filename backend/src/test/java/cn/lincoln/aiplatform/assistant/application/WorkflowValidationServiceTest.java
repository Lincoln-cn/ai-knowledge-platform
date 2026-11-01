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
  @DisplayName("未知类型是错误，规划中类型只是警告")
  void unknownTypeIsErrorButPlannedIsOnlyWarning() {
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
    assertThat(planned.passed()).isTrue();
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
