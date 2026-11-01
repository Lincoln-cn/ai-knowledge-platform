package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 带参数判定的准入：`MISSING_REQUIRED_CONFIG`（离线、无容器）。 */
class WorkflowAdmissionConfigTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final NodeConfigReader reader = new NodeConfigReader();
  private final WorkflowAdmissionService admission =
      new WorkflowAdmissionService(
          new WorkflowValidationService(new NodeTypeCatalog(mapper)), new NodeTypeCatalog(mapper));

  private WorkflowValidationService.Result check(String json) {
    JsonNode raw = mapper.readTree(json);
    return admission.admission(raw, reader);
  }

  private static String graph(String nodes, String edges) {
    return "{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
  }

  private static String chat(String id, String config) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"AI_CHAT_NODE\",\"title\":\""
        + id
        + "\""
        + (config == null ? "" : ",\"config\":" + config)
        + "}";
  }

  private static String reply(String id) {
    return "{\"id\":\"" + id + "\",\"type\":\"REPLY_NODE\",\"title\":\"" + id + "\"}";
  }

  private static String start(String id) {
    return "{\"id\":\"" + id + "\",\"type\":\"START_NODE\",\"title\":\"" + id + "\"}";
  }

  private static String edge(String s, String t) {
    return "{\"id\":\"" + s + "-" + t + "\",\"source\":\"" + s + "\",\"target\":\"" + t + "\"}";
  }

  @Test
  @DisplayName("AI 对话节点没选模型：结构全对也拒绝，并且点名缺的是 modelId")
  void chatNodeWithoutModelIsRefused() {
    WorkflowValidationService.Result result =
        check(
            graph(
                start("s1") + "," + chat("a1", null) + "," + reply("r1"),
                edge("s1", "a1") + "," + edge("a1", "r1")));
    assertThat(result.passed()).isFalse();
    assertThat(result.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.code()).isEqualTo(WorkflowAdmissionService.MISSING_REQUIRED_CONFIG);
              assertThat(issue.nodeId()).isEqualTo("a1");
              assertThat(issue.message()).contains("modelId");
            });
  }

  @Test
  @DisplayName("配上模型之后同一条图就能跑 —— 拒绝确实来自参数，不是来自结构")
  void theSameGraphPassesOnceTheModelIsChosen() {
    WorkflowValidationService.Result result =
        check(
            graph(
                start("s1") + "," + chat("a1", "{\"modelId\":\"m-1\"}") + "," + reply("r1"),
                edge("s1", "a1") + "," + edge("a1", "r1")));
    assertThat(result.passed()).isTrue();
    assertThat(result.issues())
        .noneMatch(issue -> WorkflowAdmissionService.MISSING_REQUIRED_CONFIG.equals(issue.code()));
  }

  @Test
  @DisplayName("检索节点既没挑库也没挑应用：报缺检索范围；挑了任意一个就放过")
  void searchNodeNeedsASource() {
    String bare = "{\"id\":\"k1\",\"type\":\"SEARCH_DATASET_NODE\",\"title\":\"k1\"}";
    String withKb =
        "{\"id\":\"k1\",\"type\":\"SEARCH_DATASET_NODE\",\"title\":\"k1\",\"config\":{\"kbIds\":[\"kb-1\"]}}";
    WorkflowValidationService.Result refused =
        check(
            graph(
                start("s1") + "," + bare + "," + reply("r1"),
                edge("s1", "k1") + "," + edge("k1", "r1")));
    WorkflowValidationService.Result ok =
        check(
            graph(
                start("s1") + "," + withKb + "," + reply("r1"),
                edge("s1", "k1") + "," + edge("k1", "r1")));
    assertThat(refused.issues()).anySatisfy(issue -> assertThat(issue.message()).contains("kbIds"));
    assertThat(ok.passed()).isTrue();
  }
}
