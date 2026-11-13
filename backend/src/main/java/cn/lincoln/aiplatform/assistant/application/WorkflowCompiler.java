package cn.lincoln.aiplatform.assistant.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻引擎编译器（{@code #113} 判据 1）：图 → 执行计划，<strong>只编译不跑</strong>。
 *
 * <p>★ 判据 1：给定图，单独产出 {@link ExecutionPlan}（拓扑序、条件位置、中断点清单）， 不执行任何节点 —— 这样"编译对不对"能被独立断言，不必把整个流程跑一遍。
 *
 * <p>★ <strong>图不连通或成环 ⇒ 返回错误（{@link CompileResult}），而不是抛异常</strong>：
 * 调用方（发布/调试）要把原因显示给用户，编译器内部炸栈没用。
 *
 * <p>★ 拓扑序用 <strong>Kahn</strong>：入度 0 进队、逐步减入度；排不满全部节点就是有环。
 */
public final class WorkflowCompiler {

  private final NodeTypeCatalog catalog;

  public WorkflowCompiler(NodeTypeCatalog catalog) {
    this.catalog = catalog;
  }

  /**
   * 编译结果：成功给 {@code plan}，失败给 {@code errors}（永远不抛）。
   *
   * @param ok 是否编译成功
   * @param plan 成功时的执行计划；失败时为 {@code null}
   * @param errors 失败原因（成环 / 不连通 / 图形残缺）；成功时为空
   */
  public record CompileResult(boolean ok, ExecutionPlan plan, List<String> errors) {}

  /**
   * 执行计划。
   *
   * @param steps 拓扑序的步骤
   * @param interruptNodeIds 中断点清单（本版本不支持的节点 id，含人工介入节点）
   */
  public record ExecutionPlan(List<PlanStep> steps, List<String> interruptNodeIds) {}

  /**
   * 一步。
   *
   * @param nodeId 节点 id
   * @param type 节点类型
   * @param order 拓扑序（0 起）
   * @param branchHandle 该步从条件节点哪个分支进来；线性路径为 {@code null}
   */
  public record PlanStep(
      String nodeId, String type, int order, String branchHandle, Map<String, Object> config) {
    /**
     * 不带配置的旧构造器。
     *
     * <p>保留它有两个用处：一是让这次"只加字段、不接使用"的改动成为一个可单独验收的步子（先红/绿一次，再写使用方）； 二是编译之外的地方（例如调试计划）本来就不需要配置。
     */
    public PlanStep(String nodeId, String type, int order, String branchHandle) {
      this(nodeId, type, order, branchHandle, Map.of());
    }
  }

  /** 图（只取编译需要的字段）。 */
  public record Graph(List<Node> nodes, List<Edge> edges) {}

  /**
   * 节点。
   *
   * <p>★ 节点配置有**两种真实形状**，两格都得认：
   *
   * <ul>
   *   <li>{@code properties.config} —— 画布内部形状（{@code toLogicFlow} 产出，含 {@code stepName} / {@code
   *       width} 等展示字段）；
   *   <li>顶层 {@code config} —— 前端提交给后端的契约形状（{@code fromLogicFlow} 产出，**没有 properties**）。
   * </ul>
   *
   * <p>只认前者会让生产路径上每一个节点的配置都是空的（{@code WorkflowDebugController} 反序列化 的就是契约形状），执行器全部空转 —— 这是 {@code
   * DOCUMENT_EXTRACT_NODE} 之前必须补上的底座。 合并时**顶层优先**：契约形状是调用方显式给的，画布形状是内部的。
   *
   * @param properties 画布节点原样保留的那层（可为 null）
   * @param config 合并后的配置，**永不 null**
   */
  public record Node(
      String id, String type, Map<String, Object> properties, Map<String, Object> config) {
    public Node {
      config = mergeConfig(properties, config);
    }

    /** 只读 id / type 的场合（判据、日志）。 */
    public Node(String id, String type) {
      this(id, type, Map.of(), Map.of());
    }

    /** 只有画布形状的场合。 */
    public Node(String id, String type, Map<String, Object> properties) {
      this(id, type, properties, Map.of());
    }

    /**
     * 合并两种形状的配置，顶层（契约）优先。
     *
     * <p>不用 {@code Map.copyOf} / {@code Map.of}：它们拒绝 null 值，而 Jackson 反序列化出来的 map 可以有
     * null（前端某个字段没填就是 null）。空配置返回不可变的空 map，其余返回不可变视图。
     */
    private static Map<String, Object> mergeConfig(
        Map<String, Object> properties, Map<String, Object> config) {
      Map<String, Object> merged = new LinkedHashMap<>();
      if (properties != null && properties.get("config") instanceof Map<?, ?> inner) {
        inner.forEach((key, value) -> merged.put(String.valueOf(key), value));
      }
      if (config != null) {
        merged.putAll(config);
      }
      return merged.isEmpty() ? Map.of() : Collections.unmodifiableMap(merged);
    }
  }

  /** 边（带条件分支出口 {@code sourceHandle}：从条件节点的哪个出口连出来）。 */
  public record Edge(String source, String target, String sourceHandle) {}

  /** 把图编译成执行计划；图不连通或成环 ⇒ {@code ok=false} + {@code errors}，不抛。 */
  public CompileResult compile(Graph graph) {
    List<String> errors = new ArrayList<>();
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return new CompileResult(false, null, List.of("图是空的，编译不出执行计划"));
    }

    Map<String, Node> byId = new LinkedHashMap<>();
    for (Node node : graph.nodes()) {
      if (node.id() == null || node.id().isBlank()) {
        errors.add("有一个节点没有 id");
        continue;
      }
      if (byId.putIfAbsent(node.id(), node) != null) {
        errors.add("节点 id 重复：" + node.id());
      }
    }

    Map<String, List<String>> adjacency = new HashMap<>();
    Map<String, List<String>> parents = new HashMap<>();
    Map<String, Integer> indegree = new HashMap<>();
    Map<String, List<String>> handlesInto = new HashMap<>();
    for (String id : byId.keySet()) {
      indegree.put(id, 0);
    }
    Map<String, String> enterHandle = new HashMap<>();
    List<Edge> edges = graph.edges() == null ? List.of() : graph.edges();
    for (Edge edge : edges) {
      if (edge.source() == null
          || edge.target() == null
          || !byId.containsKey(edge.source())
          || !byId.containsKey(edge.target())) {
        errors.add("有一条连线指向不存在的节点");
        continue;
      }
      adjacency.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
      parents.computeIfAbsent(edge.target(), key -> new ArrayList<>()).add(edge.source());
      indegree.merge(edge.target(), 1, Integer::sum);
      if (edge.sourceHandle() != null) {
        handlesInto
            .computeIfAbsent(edge.target(), key -> new ArrayList<>())
            .add(edge.sourceHandle());
      }
    }
    if (!errors.isEmpty()) {
      return new CompileResult(false, null, List.copyOf(errors));
    }

    // 中断点清单 = 人工介入节点（追问/表单）+ 本版本不支持的节点（implemented=false）。
    List<String> interruptNodeIds = new ArrayList<>();
    for (Node node : byId.values()) {
      if (isInterrupt(node.type())) {
        interruptNodeIds.add(node.id());
      }
    }

    // Kahn 拓扑排序：按插入序取入度 0 进队，逐步减入度。
    Deque<String> queue = new ArrayDeque<>();
    for (String id : byId.keySet()) {
      if (indegree.get(id) == 0) {
        queue.add(id);
      }
    }
    // 每个节点落在哪条分支上（分支 id = 条件节点那条出边的 sourceHandle）。
    //
    // ★ 只标「直接目标」是不够的：条件节点后面往往还挂着好几个节点，执行器要跳过的是**整条链**。
    // 所以这里沿拓扑序往下传播 —— 一个节点若只有一条入边，就继承上游的分支；
    // 一旦有多条入边就是**汇合点**，两边合流之后再分「在哪个分支上」没有意义，置 null。
    // 判据：同一张图按不同输入运行时，落在另一条分支上的节点整段不执行。
    Map<String, String> branchOf = new HashMap<>();
    Map<String, String> stepBranch = new HashMap<>();
    List<PlanStep> steps = new ArrayList<>();
    Set<String> placed = new HashSet<>();
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!placed.add(id)) {
        continue;
      }
      Node node = byId.get(id);
      List<String> entries = parents.getOrDefault(id, List.of());
      String branch;
      if (entries.isEmpty()) {
        branch = null;
      } else if (entries.size() > 1) {
        branch = null;
      } else {
        String upstream = entries.get(0);
        String handle = handlesInto.containsKey(id) ? handlesInto.get(id).get(0) : null;
        branch = handle != null ? handle : stepBranch.get(upstream);
      }
      stepBranch.put(id, branch);
      steps.add(new PlanStep(id, node.type(), steps.size(), branch, node.config()));
      for (String next : adjacency.getOrDefault(id, List.of())) {
        if (indegree.merge(next, -1, Integer::sum) == 0) {
          queue.add(next);
        }
      }
    }
    if (steps.size() != byId.size()) {
      // Kahn 排不满全部节点 = 有环（04 BR-04-02 禁止成环）。
      errors.add("图上有环，编译不出线性执行计划");
      return new CompileResult(false, null, List.copyOf(errors));
    }

    // 连通性：必须从开始节点走到每一个节点，否则不是一条执行路径。
    String startId =
        byId.values().stream()
            .filter(n -> "START_NODE".equals(n.type()))
            .map(Node::id)
            .findFirst()
            .orElse(null);
    if (startId == null) {
      errors.add("缺少开始节点");
      return new CompileResult(false, null, List.copyOf(errors));
    }
    Set<String> reached = new HashSet<>();
    Deque<String> walk = new ArrayDeque<>();
    walk.add(startId);
    reached.add(startId);
    while (!walk.isEmpty()) {
      for (String next : adjacency.getOrDefault(walk.poll(), List.of())) {
        if (reached.add(next)) {
          walk.add(next);
        }
      }
    }
    if (reached.size() != byId.size()) {
      errors.add("图不连通：有节点从开始节点走不到，编译不出一条执行路径");
      return new CompileResult(false, null, List.copyOf(errors));
    }

    return new CompileResult(
        true,
        new ExecutionPlan(List.copyOf(steps), List.copyOf(interruptNodeIds)),
        List.copyOf(errors));
  }

  /**
   * 中断点 = 人工介入节点（{@code QUESTION_NODE} 追问 / {@code FORM_NODE} 表单收集）+ 本版本跑不了 的节点（{@code
   * executable=false}）。含中断点的图本版本不能自动跑完，见 {@code #113} 判据 2。
   */
  private boolean isInterrupt(String type) {
    return "QUESTION_NODE".equals(type)
        || "FORM_NODE".equals(type)
        || (catalog.known(type) && !catalog.executable(type));
  }
}
