package cn.lincoln.aiplatform.assistant.application;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 节点参数视图：把画布发来的 {@code config} 读成"执行准入关心的那几个字段"。
 *
 * <p>为什么要单独一个类，而不是往 {@link WorkflowValidationService.Node} 上挂 config（{@code #113}）：
 *
 * <ul>
 *   <li>{@code Node} 刻意只带 {@code id/type/title} —— 校验层只判结构，参数语义不归它；
 *   <li>配置键今天散在画布侧（{@code frontend/src/pages/workflow/lib/graph-format.ts} 的 {@code modelId} /
 *       {@code kbIds}），后端若有两处读，就必有一处先漂；
 *   <li>它是纯函数：{@code JsonNode} 进、视图出，可以离线验、可以金丝雀。
 * </ul>
 *
 * <p>缺字段一律给 {@code null}（或空列表），**不抛** —— 「没配」是一种正常状态，要由准入层翻译成一条 可读的错误，而不是在读取处炸成一个 500。
 */
@Component
public class NodeConfigReader {

  /** 一个节点的参数视图。字段名与画布契约一致，不另起一套。 */
  public record NodeConfig(String modelId, List<String> kbIds, String applicationId) {

    /** 有没有选模型。 */
    public boolean hasModel() {
      return modelId != null && !modelId.isBlank();
    }

    /** 有没有挑知识库或应用（检索类节点二选一）。 */
    public boolean hasSource() {
      return (kbIds != null && !kbIds.isEmpty())
          || (applicationId != null && !applicationId.isBlank());
    }
  }

  /** 读一个节点的 config；没有 config 时返回"全空"的视图，而不是 null。 */
  public NodeConfig read(JsonNode node) {
    if (node == null) {
      return new NodeConfig(null, List.of(), null);
    }
    JsonNode config = node.get("config");
    if (config == null || !config.isObject()) {
      return new NodeConfig(null, List.of(), null);
    }
    return new NodeConfig(
        text(config, "modelId"), texts(config, "kbIds"), text(config, "applicationId"));
  }

  private static String text(JsonNode config, String field) {
    JsonNode value = config.get(field);
    return value == null || !value.isString() ? null : value.asString();
  }

  private static List<String> texts(JsonNode config, String field) {
    JsonNode value = config.get(field);
    if (value == null || !value.isArray()) {
      return List.of();
    }
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode item : value) {
      if (item != null && item.isString() && !item.asString().isBlank()) {
        out.add(item.asString());
      }
    }
    return List.copyOf(out);
  }
}
