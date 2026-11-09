package cn.lincoln.aiplatform.assistant.application;

import java.util.Map;
import java.util.UUID;

/**
 * 传给 {@link NodeExecutor} 的执行上下文：一次执行的 id + 当前节点 + **本次输入** + 共享 {@link FlowState}。
 *
 * <p>★ {@code state} 在同一次执行的所有节点间共享 —— 判据 3 的 {@link KeyUpdate}（{@code REPLACE} / {@code
 * APPEND}）就是多个节点往同一个 {@code state} 键写时的语义。
 *
 * <p>★ <strong>{@code input} 是 {@code #326} 加的第 5 格</strong>：加之前 {@code
 * LinearWorkflowExecutor.execute(executionId, plan, input)} 的 {@code input}
 * <strong>只出现在签名上</strong>， 既没进 {@code FlowState} 也没进本 record ⇒ 执行器拿不到用户提问，{@code START_NODE}
 * 无从把它写进 {@code state}， {@code SEARCH_DATASET_NODE} 也就没有检索词。一个方法的参数从未被使用，是"这个能力还没接进来"的信号。
 *
 * <p>★ 为什么给每个节点都带一份 {@code input}（而不是只给 {@code START_NODE}）：用户提问是**一次执行的属性**，不是某个节点的副作用。
 * 让后面的节点也能直接读到它，比要求它们都先 {@code state.read("query")} 更不容易出错 —— 后者一旦 {@code START_NODE} 没跑或键名改了，
 * 失败点会离原因很远。
 *
 * @param executionId 一次执行（{@code wf_execution} 主键）
 * @param nodeId 当前节点 id
 * @param type 当前节点类型
 * @param input 本次执行的用户输入（可为 {@code null}）
 * @param state 同一次执行内共享的流程状态
 * @param nodeConfig 当前节点在画布上那段 {@code properties.config}；没有就是空 map
 * @param userId 发起这次执行的用户；{@code null} = 未知（见下）
 */
public record WorkflowNodeContext(
    UUID executionId,
    String nodeId,
    String type,
    String input,
    FlowState state,
    Map<String, Object> nodeConfig,
    UUID userId) {

  /**
   * ★ <strong>{@code userId} 是 {@code #326} 加的最后一格，而且现在还没有任何人读它。</strong>
   *
   * <p>它存在的原因：检索节点要按"谁在执行"去查 —— 密级 pre-filter 与组织 scope 都挂在人身上。在这个字段之前， 执行上下文里只有 {@code
   * executionId}，执行器回答不了"这是谁的知识库"，于是只有两条路：按系统身份查（等于绕过密级）、 或者不查（功能缺失）。两者都不是实现层面能决定的，所以先把通道建出来。
   *
   * <p>★ 现在 {@code LinearWorkflowExecutor} 传的是 {@code null}（执行记录里还没有"谁发起"这一列）。<strong>这不是占位符语义，
   * 是"真的未知"</strong> —— 因此读到 {@code null} 的实现都不许按"能看到全部"处理。
   */

  /**
   * ★ <strong>{@code nodeConfig} 是 {@code #326} 加的第 6 格</strong>：加之前节点配置在编译阶段就被丢掉了（{@code
   * WorkflowCompiler.Node} 只有 {@code id} / {@code type}）⇒ {@code SEARCH_DATASET_NODE} 拿不到 {@code
   * dataset_id_list}、{@code AI_CHAT_NODE} 拿不到模型，执行器写出来也是空转。
   *
   * <p>★ 它和 {@code input} 的区别：{@code input} 是**一次执行**的属性（所有节点读同一份），{@code nodeConfig}
   * 是**一个节点**的属性（每个节点不同）。混成一个字段会让执行器分不清"这是用户说的"还是"这是配的"。
   *
   * <p>★ 永不返回 {@code null}：编译期已把缺失归一成 {@link Map#of()}，执行器不必判空 —— 判空散落到每个实现里， 只要漏一处就是 NPE。
   */
  public WorkflowNodeContext {
    nodeConfig = nodeConfig == null ? Map.of() : Map.copyOf(nodeConfig);
  }

  /**
   * 兼容构造器：{@code #326} 加 {@code userId} 之前（无执行者身份）。
   *
   * <p>★ 现在 {@code LinearWorkflowExecutor} 走的正是这一条，因为执行记录里还没有「谁发起」这一列。
   */
  public WorkflowNodeContext(
      UUID executionId,
      String nodeId,
      String type,
      String input,
      FlowState state,
      Map<String, Object> nodeConfig) {
    this(executionId, nodeId, type, input, state, nodeConfig, null);
  }

  /**
   * 兼容构造器：{@code #326} 加 {@code nodeConfig} 之前的签名（无节点配置）。
   *
   * <p>★ 保留它是为了让"只加字段"这一次提交不牵动任何既有调用点 —— 先证明加了字段别处没坏，再写使用方。
   */
  public WorkflowNodeContext(
      UUID executionId, String nodeId, String type, String input, FlowState state) {
    this(executionId, nodeId, type, input, state, Map.of(), null);
  }

  /**
   * 兼容构造器：{@code #326} 之前的签名（无 {@code input}）。
   *
   * <p>★ 保留它是因为既有测试与 {@code NodeExecutor} 的实现方都是按 4 参构造的；新代码请用 6 参重载。
   */
  public WorkflowNodeContext(UUID executionId, String nodeId, String type, FlowState state) {
    this(executionId, nodeId, type, null, state, Map.of(), null);
  }
}
