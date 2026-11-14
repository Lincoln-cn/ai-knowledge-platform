package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.assistant.application.WorkflowCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 边上的 {@code sourceHandle} 能跨过「JSON → Graph」这一层。
 *
 * <p>为什么要单独钉这一条：条件节点的分支跳靠的就是它 —— 前端契约 {@code WorkflowEdge.sourceHandle}
 * 有值、编译器也按它传播分支，但**反序列化这一步是它们之间唯一的缝**。 这条缝一旦漏（比如 record 的组件名与 JSON 键名不一致），症状是「条件节点的所有分支目标都执行了」，
 * 而每一边的单测都还是绿的：编译器拿到的是它以为的 null，执行器按"没有分支"正常放行。
 *
 * <p>实测取证：把分支边的 handle 写成一个不存在的值，端到端立刻变成"没有任何回复节点执行" （{@code finalOutput} 为 null）—— 说明这条链路是它撑着的。
 */
class WorkflowEdgePayloadTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  @DisplayName("边的 sourceHandle 能反序列化进来")
  void sourceHandleSurvivesDeserialization() throws Exception {
    String json =
        """
        {
          "nodes": [
            {"id": "a", "type": "CONDITION_NODE", "title": "条件"},
            {"id": "b", "type": "REPLY_NODE", "title": "一"},
            {"id": "c", "type": "REPLY_NODE", "title": "二"}
          ],
          "edges": [
            {"id": "e1", "source": "a", "target": "b", "sourceHandle": "c-pay"},
            {"id": "e2", "source": "a", "target": "c", "sourceHandle": "c-break"}
          ]
        }
        """;
    WorkflowCompiler.Graph graph = MAPPER.readValue(json, WorkflowCompiler.Graph.class);
    assertThat(graph.edges()).hasSize(2);
    assertThat(graph.edges().get(0).sourceHandle()).isEqualTo("c-pay");
    assertThat(graph.edges().get(1).sourceHandle()).isEqualTo("c-break");
  }
}
