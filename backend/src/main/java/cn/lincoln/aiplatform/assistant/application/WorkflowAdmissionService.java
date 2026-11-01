package cn.lincoln.aiplatform.assistant.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    issues.addAll(cycles(graph));
    boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.level()));
    return new WorkflowValidationService.Result(passed, List.copyOf(issues));
  }

  /** 只报环，不报别的 —— 结构与可达性归 {@link WorkflowValidationService}。 */
  private static List<WorkflowValidationService.Issue> cycles(
      WorkflowValidationService.Graph graph) {
    Map<String, List<String>> next = new HashMap<>();
    for (WorkflowValidationService.Node node : graph.nodes()) {
      if (node != null && node.id() != null) {
        next.computeIfAbsent(node.id(), key -> new ArrayList<>());
      }
    }
    if (graph.edges() != null) {
      for (WorkflowValidationService.Edge edge : graph.edges()) {
        if (edge == null || edge.source() == null || edge.target() == null) {
          continue;
        }
        next.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
      }
    }
    List<WorkflowValidationService.Issue> found = new ArrayList<>();
    Set<String> done = new HashSet<>();
    Set<String> onPath = new HashSet<>();
    for (String id : next.keySet()) {
      if (walk(id, next, done, onPath, found)) {
        break;
      }
    }
    return found;
  }

  /** 深搜找第一个环；找到即停 —— 一处环就够说明「跑不完」，列全三处没有额外信息。 */
  private static boolean walk(
      String id,
      Map<String, List<String>> next,
      Set<String> done,
      Set<String> onPath,
      List<WorkflowValidationService.Issue> found) {
    if (done.contains(id)) {
      return false;
    }
    if (!onPath.add(id)) {
      return false;
    }
    for (String target : next.getOrDefault(id, List.of())) {
      if (onPath.contains(target)) {
        found.add(
            new WorkflowValidationService.Issue(
                "ERROR",
                CYCLE,
                "图上有环：「" + target + "」转了一圈又回到自己，执行器会一直转下去",
                "删掉构成环的那条连线；需要重复执行请在节点配置里设次数，不要用连线绕",
                target));
        onPath.remove(id);
        return true;
      }
      if (walk(target, next, done, onPath, found)) {
        onPath.remove(id);
        return true;
      }
    }
    onPath.remove(id);
    done.add(id);
    return false;
  }

  private static String label(WorkflowValidationService.Node node) {
    String title = node.title();
    return title == null || title.isBlank() ? node.id() : title;
  }
}
