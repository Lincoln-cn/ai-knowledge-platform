package cn.lincoln.aiplatform.assistant.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 发布前校验（契约 {@code POST /assistants/{appId}/workflow/validate}，`#198` 第三块）。
 *
 * <p>★ 只做<strong>图结构</strong>与<strong>类型目录</strong>两件事，不碰节点参数的业务含义
 * （比如"检索节点选的知识库还在不在"要查库，属于发布固化时那一块）。 这里能拦住的是"这张图根本跑不起来"，即使用户把每个节点的参数都填对了。
 *
 * <p>★ 每条 issue 都带 {@code code} + 可读的 {@code nodeId}：界面要能高亮到具体节点。 只回一句"校验失败"等于让用户自己找。
 */
@Service
public class WorkflowValidationService {

  private final NodeTypeCatalog catalog;

  public WorkflowValidationService(NodeTypeCatalog catalog) {
    this.catalog = catalog;
  }

  /** 校验一张图（{@code graph} 是契约 JSON 原样，本类不关心它的完整结构）。 */
  public Result validate(Graph graph) {
    List<Issue> issues = new ArrayList<>();
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      issues.add(issue("ERROR", "EMPTY_GRAPH", "流程里没有任何节点", "至少放一个开始节点和一个输出节点", null));
      return new Result(false, issues);
    }

    Map<String, Node> byId = new LinkedHashMap<>();
    for (Node node : graph.nodes()) {
      if (node.id() == null || node.id().isBlank()) {
        issues.add(issue("ERROR", "NODE_WITHOUT_ID", "有一个节点没有 id", "重新拖一个节点，或重开画布", null));
        continue;
      }
      if (byId.putIfAbsent(node.id(), node) != null) {
        issues.add(
            issue("ERROR", "DUPLICATE_NODE_ID", "节点 id 重复：" + node.id(), "删掉其中一个", node.id()));
      }
      if (!catalog.known(node.type())) {
        issues.add(
            issue(
                "ERROR",
                "UNKNOWN_TYPE",
                "画布上有未知类型：" + node.type(),
                "这个节点可能来自更新的版本，请升级后再编辑",
                node.id()));
      } else if (!catalog.executable(node.type())) {
        // 判据 2：executable=false = 本版本跑不了 ⇒ 从 WARNING 升为 ERROR，直接拒绝发布。
        issues.add(
            issue(
                "ERROR",
                "PLANNED_TYPE",
                "节点「" + node.type() + "」本版本还不支持（还没实现）",
                "先把它换成已实现的节点，否则发布后这一步不会执行",
                node.id()));
      }
    }

    long starts = byId.values().stream().filter(n -> "START_NODE".equals(n.type())).count();
    if (starts == 0) {
      issues.add(issue("ERROR", "NO_START", "缺少开始节点", "加一个开始节点：流程从它进入", null));
    } else if (starts > 1) {
      issues.add(
          issue(
              "ERROR",
              "MULTIPLE_START",
              "有 " + starts + " 个开始节点",
              "只保留一个 —— 多个入口时系统不知道用户从哪进",
              null));
    }

    List<Edge> edges = graph.edges() == null ? List.of() : graph.edges();
    Map<String, List<String>> outgoing = new HashMap<>();
    for (Edge edge : edges) {
      if (!byId.containsKey(edge.source()) || !byId.containsKey(edge.target())) {
        issues.add(issue("ERROR", "DANGLING_EDGE", "有一条连线指向不存在的节点", "删掉这条线再重新连", edge.source()));
        continue;
      }
      outgoing.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
    }

    // 判据 1/2（04 BR-04-02 禁止成环）：图里不许有环 —— 有环执行器会一直转下去，这里直接拦下。
    issues.addAll(cycles(graph));

    // ★ 这里曾有一条 NODE_NOT_EXECUTABLE 规则：拿 LinearWorkflowExecutor.SUPPORTED_TYPES 再判一遍
    // 「目录说能跑，执行器真的能跑吗」。`#337` 把那份手写集合删掉了 —— 它本身就是"同一件事两处各判一遍"
    // 的第二份，而目录侧那条 `PLANNED_TYPE`（catalog.executable）已经覆盖了用户能撞到的全部情形。
    // 剩下的那一格「目录 executable=true 但主代码没有执行器」不可能躲过构建：`WorkflowNodeTypeTest`
    // 的 executableTypesMatchTheExecutor 扫 `src/main/java` 里所有 NodeExecutor 的 type()，
    // 与目录 executable 集合断言**完全相等**。构建期能钉住的事不必在发布时再判一次。

    String startId =
        byId.values().stream()
            .filter(n -> "START_NODE".equals(n.type()))
            .map(Node::id)
            .findFirst()
            .orElse(null);
    if (startId != null) {
      Set<String> reached = new HashSet<>();
      Deque<String> queue = new ArrayDeque<>();
      queue.add(startId);
      reached.add(startId);
      while (!queue.isEmpty()) {
        for (String next : outgoing.getOrDefault(queue.poll(), List.of())) {
          if (reached.add(next)) {
            queue.add(next);
          }
        }
      }
      for (Node node : byId.values()) {
        if (!reached.contains(node.id())) {
          issues.add(
              issue(
                  "WARNING",
                  "UNREACHABLE",
                  "节点「" + node.title() + "」从开始节点走不到",
                  "连一条线到它，或删掉",
                  node.id()));
        }
      }
      boolean reachableOutput =
          byId.values().stream()
              .anyMatch(
                  n ->
                      ("REPLY_NODE".equals(n.type()) || "AI_CHAT_NODE".equals(n.type()))
                          && reached.contains(n.id()));
      if (!reachableOutput) {
        issues.add(
            issue("ERROR", "NO_OUTPUT", "流程没有任何可到达的输出节点", "加一个「回复」或「AI 对话」节点，否则用户提问后拿不到答复", null));
      }
    }

    boolean passed = issues.stream().noneMatch(i -> "ERROR".equals(i.level()));
    return new Result(passed, issues);
  }

  /*
   * 这里曾有一条 INTERRUPT_NODE 拒绝规则：把 QUESTION_NODE / FORM_NODE 这两类人工介入节点按类型硬拦下来，
   * 理由是「本版本不支持中断点」。
   *
   * #338 把它删了，因为那个理由已经不成立：0.7.4 落地了中断与恢复（WorkflowInterrupted → as_workflow_pause
   * → WorkflowResumeService），执行到人工介入节点会停下来等用户答复、答复到了再从下一步接着跑；目录里这两个
   * 节点也是 executable:true。再拦一道的结果是「能跑的图发不出去」。
   *
   * 删除时同步补了一条测试（WorkflowResumeServiceTest#resumeStartsAfterTheInterruptAndFinishesTheGraph）：
   * 恢复链路原来是原地打转的（恢复点传成了中断点本身），而原来四条测试只覆盖「还没开始跑就被挡下」的路径，
   * 所以这条一直没人发现 —— 闸门放开之前必须先确认它真的能跑完。
   */

  /** 只报环（{@code CYCLE}）—— 结构、可达性与类型校验在 {@link #validate} 里做。 */
  private static List<Issue> cycles(Graph graph) {
    Map<String, List<String>> next = new HashMap<>();
    for (Node node : graph.nodes()) {
      if (node != null && node.id() != null) {
        next.computeIfAbsent(node.id(), key -> new ArrayList<>());
      }
    }
    if (graph.edges() != null) {
      for (Edge edge : graph.edges()) {
        if (edge == null || edge.source() == null || edge.target() == null) {
          continue;
        }
        next.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
      }
    }
    List<Issue> found = new ArrayList<>();
    Set<String> done = new HashSet<>();
    Set<String> onPath = new HashSet<>();
    for (String id : next.keySet()) {
      if (walk(id, next, done, onPath, found)) {
        break;
      }
    }
    return found;
  }

  /** 深搜找第一个环；找到即停 —— 一处环就够说明「跑不完」，列全没有额外信息。 */
  private static boolean walk(
      String id,
      Map<String, List<String>> next,
      Set<String> done,
      Set<String> onPath,
      List<Issue> found) {
    if (done.contains(id)) {
      return false;
    }
    if (!onPath.add(id)) {
      return false;
    }
    for (String target : next.getOrDefault(id, List.of())) {
      if (onPath.contains(target)) {
        found.add(
            issue(
                "ERROR",
                "CYCLE",
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

  private static Issue issue(
      String level, String code, String message, String hint, String nodeId) {
    return new Issue(level, code, message, hint, nodeId);
  }

  /** 图（只取校验需要的字段）。 */
  public record Graph(List<Node> nodes, List<Edge> edges) {}

  /** 节点（只取校验需要的字段；参数的业务含义不在这里判）。 */
  public record Node(String id, String type, String title) {}

  /** 边。 */
  public record Edge(String id, String source, String target) {}

  /** 一条校验结论（契约 `WorkflowValidationIssue`）。 */
  public record Issue(String level, String code, String message, String hint, String nodeId) {}

  /** 校验结果（契约 `WorkflowValidationResult`）。 */
  public record Result(boolean passed, List<Issue> issues) {}
}
