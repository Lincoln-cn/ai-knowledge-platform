package cn.lincoln.aiplatform.assistant.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 执行准入：这张图除了「能不能保存」「能不能发布」，还要回答「现在就能跑吗」。
 *
 * <p>三件事分三层，判据不同（{@code 06} 机制与 {@code #113}）：
 *
 * <ul>
 *   <li>保存草稿 —— 什么都不校验（画一半是常态）；
 *   <li>发布 —— {@link WorkflowValidationService} 的十种规则，规划中的节点类型只是警告；
 *   <li>执行 —— 本类。比发布更严：规划中的节点类型是错误，图上不许有环。
 * </ul>
 *
 * <p>多加这一层不是重复：发布允许「带着未实现的节点先发出去」（产品要能预告能力），而执行器遇到未实现的 类型只能中途放弃 —— 那是「跑到一半才说不行」，比开跑前拒绝坏得多。
 */
@Service
public class WorkflowAdmissionService {

  /** 执行器唯一的硬性结构要求：图必须能停下来，因此不许有环。 */
  static final String CYCLE = "CYCLE";

  /** 必填参数没配齐：结构没问题，但跑起来到这里一定会失败。 */
  static final String MISSING_REQUIRED_CONFIG = "MISSING_REQUIRED_CONFIG";

  /** 规划中的节点类型：发布时只是警告，执行时是错误。 */
  static final String NOT_EXECUTABLE_TYPE = "NOT_EXECUTABLE_TYPE";

  private final WorkflowValidationService validation;
  private final NodeTypeCatalog catalog;

  public WorkflowAdmissionService(WorkflowValidationService validation, NodeTypeCatalog catalog) {
    this.validation = validation;
    this.catalog = catalog;
  }

  /** 返回合并后的结果：{@code passed} 表示「现在就能跑」。 */
  public WorkflowValidationService.Result admission(WorkflowValidationService.Graph graph) {
    WorkflowValidationService.Result base = validation.validate(graph);
    List<WorkflowValidationService.Issue> issues = new ArrayList<>(base.issues());
    if (graph == null || graph.nodes() == null) {
      return base;
    }
    for (WorkflowValidationService.Node node : graph.nodes()) {
      if (node == null || node.type() == null) {
        continue;
      }
      if (catalog.known(node.type()) && !catalog.implemented(node.type())) {
        issues.add(
            new WorkflowValidationService.Issue(
                "ERROR",
                NOT_EXECUTABLE_TYPE,
                "节点「" + label(node) + "」的类型还没实现，执行到这里会中断",
                "把它换成已实现的类型，或从图上删掉后再调试",
                node.id()));
      }
    }
    // 环检测（CYCLE）已上移到 WorkflowValidationService（发布层，04 BR-04-02 禁止成环），
    // 由上方 base = validation.validate(graph) 继承进来，这里不再重复报，否则一条环会被记两次。
    boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.level()));
    return new WorkflowValidationService.Result(passed, List.copyOf(issues));
  }

  private static String label(WorkflowValidationService.Node node) {
    String title = node.title();
    return title == null || title.isBlank() ? node.id() : title;
  }

  /**
   * 带参数判定的准入：结构层照旧，另按 {@link NodeConfigReader} 判"必填参数齐不齐"。
   *
   * <p>为什么要吃原始 {@code JsonNode}：{@link WorkflowValidationService.Node} 只带 id/type/title，
   * 参数不在结构视图里（见 {@code #113} 与 {@code NodeConfigReader} 的类注释）。
   */
  public WorkflowValidationService.Result admission(
      tools.jackson.databind.JsonNode raw, NodeConfigReader reader) {
    WorkflowValidationService.Graph graph = toGraph(raw);
    WorkflowValidationService.Result base = admission(graph);
    List<WorkflowValidationService.Issue> issues = new ArrayList<>(base.issues());
    if (raw != null && raw.get("nodes") != null && raw.get("nodes").isArray()) {
      for (tools.jackson.databind.JsonNode node : raw.get("nodes")) {
        NodeConfigReader.NodeConfig config = reader.read(node);
        String type = node.get("type") == null ? null : node.get("type").asString();
        String id = node.get("id") == null ? null : node.get("id").asString();
        String missing = missingConfig(type, config);
        if (missing != null) {
          issues.add(
              new WorkflowValidationService.Issue(
                  "ERROR",
                  MISSING_REQUIRED_CONFIG,
                  "节点「" + (id == null ? type : id) + "」缺少必填参数：" + missing,
                  "在执行前把它配上；空着跑会在这里中断，而不是在对话里报错",
                  id));
        }
      }
    }
    boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.level()));
    return new WorkflowValidationService.Result(passed, List.copyOf(issues));
  }

  /** 已实现类型的必填参数。规划中的类型由 {@code NOT_EXECUTABLE_TYPE} 拦，这里不管。 */
  private static String missingConfig(String type, NodeConfigReader.NodeConfig config) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case "AI_CHAT_NODE", "QUESTION_NODE" -> config.hasModel() ? null : "modelId（模型）";
      case "SEARCH_DATASET_NODE" -> config.hasSource() ? null : "kbIds 或 applicationId（检索范围）";
      default -> null;
    };
  }

  private static WorkflowValidationService.Graph toGraph(tools.jackson.databind.JsonNode raw) {
    List<WorkflowValidationService.Node> nodes = new ArrayList<>();
    List<WorkflowValidationService.Edge> edges = new ArrayList<>();
    if (raw != null) {
      if (raw.get("nodes") != null && raw.get("nodes").isArray()) {
        for (tools.jackson.databind.JsonNode node : raw.get("nodes")) {
          nodes.add(
              new WorkflowValidationService.Node(
                  text(node, "id"), text(node, "type"), text(node, "title")));
        }
      }
      if (raw.get("edges") != null && raw.get("edges").isArray()) {
        for (tools.jackson.databind.JsonNode edge : raw.get("edges")) {
          edges.add(
              new WorkflowValidationService.Edge(
                  text(edge, "id"), text(edge, "source"), text(edge, "target")));
        }
      }
    }
    return new WorkflowValidationService.Graph(List.copyOf(nodes), List.copyOf(edges));
  }

  private static String text(tools.jackson.databind.JsonNode node, String field) {
    return node.get(field) == null || !node.get(field).isString()
        ? null
        : node.get(field).asString();
  }
}
