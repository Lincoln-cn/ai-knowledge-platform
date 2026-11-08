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
      } else if (isHumanInTheLoop(node.type())) {
        // 判据 2（09 §5.3.2 ②）：人工介入节点 = 中断点，本版本不支持，含它的图拒绝发布。
        issues.add(
            issue(
                "ERROR",
                "INTERRUPT_NODE",
                "节点「" + node.title() + "」是人工介入节点（追问 / 表单收集），本版本不支持",
                "把人工介入换成已实现的节点（例如「AI 对话」或「回复」），否则发布后这一步无法自动执行",
                node.id()));
      } else if (!catalog.implemented(node.type())) {
        // 判据 2：implemented=false = 本版本不支持 ⇒ 从 WARNING 升为 ERROR，直接拒绝发布。
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

    // 判据 3（轻引擎边界，§11.12）：执行器只跑 LinearWorkflowExecutor.SUPPORTED_TYPES 四种节点。
    // ★ 这个集合之外的类型（条件分支 / 问题优化 / 人工介入……）在**运行时**才抛
    // IllegalArgumentException —— 而发布校验不拦就等于"发布成功、点试用时才 500"。
    // 把拦截前移到发布：用户当场就能看到是哪一格、为什么、怎么办。
    // ★ 集合从执行器读，不在这里另抄一份（抄一份就一定会漏改）。
    for (Node node : byId.values()) {
      if (!LinearWorkflowExecutor.SUPPORTED_TYPES.contains(node.type())) {
        issues.add(
            issue(
                "ERROR",
                "NODE_NOT_EXECUTABLE",
                "节点「" + node.title() + "」本版本执行引擎还跑不了",
                "先删掉它；本版本只能跑开始 / 检索 / AI 对话 / 回复四种节点，条件分支与其它节点要等 V1.0 的执行引擎",
                node.id()));
      }
    }

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

  /**
   * 人工介入节点 = {@code #113} 的「中断点」：走到这里要停下来等用户填/答，本版本的执行器只跑 全自动线性流程，不支持 —— {@code QUESTION_NODE}（追问）/
   * {@code FORM_NODE}（表单收集）。
   *
   * <p>★ 刻意按类型硬识别，而不是看 {@code implemented}：{@code QUESTION_NODE} 在 {@code node-types.json} 里被错标成
   * {@code implemented:true}（账目不准、待订正），但它是人工介入节点，必须拦。
   */
  private static boolean isHumanInTheLoop(String type) {
    return "QUESTION_NODE".equals(type) || "FORM_NODE".equals(type);
  }

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
