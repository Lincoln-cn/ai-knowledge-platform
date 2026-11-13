package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.assistant.application.WorkflowCompiler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 前端提交的图 → 编译器 {@link WorkflowCompiler.Node} 的配置读数（★ 本批的根判据）。
 *
 * <p>节点配置有**两种真实形状**，这条测试逐条钉住：
 *
 * <ol>
 *   <li><strong>契约形状</strong>（{@code frontend/src/pages/workflow/lib/graph-format.ts} 的 {@code
 *       fromLogicFlow}）：{@code {id, type, title, position, config}}，<strong>没有 {@code
 *       properties}</strong>。这是生产路径真正收到的东西（{@code WorkflowDebugController} 反序列化的就是它）， 也是 {@code
 *       POST /workflow/debug} 与恢复重编译共同的入口。
 *   <li><strong>画布内部形状</strong>：{@code {id, type, properties:{config:{...}}}}，来自 {@code
 *       toLogicFlow}。
 * </ol>
 *
 * <p>★ 这条测试曾经是红的：只认 {@code properties.config} 时，上面第一种形状读出来的配置是空 map， 于是<strong>所有执行器都在空转</strong>
 * —— {@code SEARCH_DATASET_NODE} 必抛"没有绑定任何知识库"、 {@code AI_CHAT_NODE} 拿不到模型、{@code REPLY_NODE}
 * 读不到正文。反证：把 {@code WorkflowCompiler.Node#mergeConfig} 里合并顶层 {@code config} 的那一行删掉，本类第 1 条必红。
 */
class WorkflowGraphPayloadTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** {@code fromLogicFlow} 产出的节点形状（逐字照搬该函数的返回对象字段）。 */
  private static final String CONTRACT_GRAPH =
      """
      {"nodes":[{"id":"start-1","type":"START_NODE","title":"开始",
        "position":{"x":100,"y":200},"config":{"prologue":"你好"}}],
       "edges":[]}
      """;

  /** {@code toLogicFlow} 产出的节点形状：配置在 {@code properties.config} 里。 */
  private static final String CANVAS_GRAPH =
      """
      {"nodes":[{"id":"start-1","type":"START_NODE","properties":
        {"stepName":"开始","config":{"prologue":"你好"},"width":240,"height":72}}],
       "edges":[]}
      """;

  @Test
  void contractShapeReachesTheExecutor() {
    WorkflowCompiler.Graph graph =
        MAPPER.treeToValue(MAPPER.readTree(CONTRACT_GRAPH), WorkflowCompiler.Graph.class);

    // 未知属性（title / position）不能让反序列化炸掉 —— 否则整张图编译不出来。
    assertThat(graph.nodes()).hasSize(1);
    assertThat(graph.nodes().getFirst().config())
        .as("契约形状（顶层 config）必须被读到，否则执行器全部空转")
        .containsEntry("prologue", "你好");
  }

  @Test
  void canvasShapeStillWorks() {
    WorkflowCompiler.Graph graph =
        MAPPER.treeToValue(MAPPER.readTree(CANVAS_GRAPH), WorkflowCompiler.Graph.class);

    assertThat(graph.nodes().getFirst().config())
        .as("画布内部形状（properties.config）改完之后仍要能读到")
        .containsEntry("prologue", "你好");
  }

  @Test
  void contractWinsWhenBothShapesArePresent() {
    WorkflowCompiler.Node node =
        new WorkflowCompiler.Node(
            "n1",
            "START_NODE",
            Map.of("config", Map.of("prologue", "旧的")),
            Map.of("prologue", "新的"));

    assertThat(node.config()).as("两种形状同时出现时，调用方显式给的契约形状优先").containsEntry("prologue", "新的");
  }

  @Test
  void configIsNeverNull() {
    assertThat(new WorkflowCompiler.Node("n1", "START_NODE").config()).isEmpty();
    assertThat(new WorkflowCompiler.Node("n1", "START_NODE", null, null).config())
        .as("properties 与 config 都给 null 时也要给空 map，不返回 null")
        .isEmpty();
  }
}
