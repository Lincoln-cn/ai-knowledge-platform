package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowValidationService;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 从契约 JSON 里取出校验需要的那几个字段（{@code validate} 与 {@code publish} 共用）。
 *
 * <p>★ 为什么不绑成请求体记录：图里还带着 {@code position} / {@code config} 等这一层不判的东西，绑记录就得让它们跟着
 * fail-fast，结果"多传一个字段"变成 400 —— 而发布/校验端点最不该因为这件事拒绝。 两个端点判的是同一张图，所以解析也只有一处：
 * 两边各写一份的话，"校验通过、发布却说图不对"迟早会出现。
 */
final class WorkflowGraphReader {

  private WorkflowGraphReader() {}

  /** 图原文（落库存快照用）。不是对象 ⇒ 空图，交给校验器给出"流程里没有任何节点"。 */
  static String json(JsonNode node) {
    return node != null && node.isObject() ? node.toString() : "{\"nodes\":[],\"edges\":[]}";
  }

  /** 发布说明：缺省是空串（与建表默认值一致），不是 null。 */
  static String note(JsonNode body) {
    JsonNode note = body == null ? null : body.path("note");
    return note != null && note.isString() ? note.asString() : "";
  }

  /** 只取校验要判的字段；缺字段给 null，由校验器决定这算不算错。 */
  static WorkflowValidationService.Graph graph(JsonNode node) {
    List<WorkflowValidationService.Node> nodes = new ArrayList<>();
    for (JsonNode item : node.path("nodes")) {
      nodes.add(
          new WorkflowValidationService.Node(
              text(item, "id"), text(item, "type"), text(item, "title")));
    }
    List<WorkflowValidationService.Edge> edges = new ArrayList<>();
    for (JsonNode item : node.path("edges")) {
      edges.add(
          new WorkflowValidationService.Edge(
              text(item, "id"), text(item, "source"), text(item, "target")));
    }
    return new WorkflowValidationService.Graph(nodes, edges);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.asString() : null;
  }
}
