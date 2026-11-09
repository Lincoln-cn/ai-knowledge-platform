package cn.lincoln.aiplatform.assistant.application;

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
 */
public record WorkflowNodeContext(
    UUID executionId, String nodeId, String type, String input, FlowState state) {

  /**
   * 兼容构造器：{@code #326} 之前的签名（无 {@code input}）。
   *
   * <p>★ 保留它是因为既有测试与 {@code NodeExecutor} 的实现方都是按 4 参构造的；新代码请用 5 参重载。
   */
  public WorkflowNodeContext(UUID executionId, String nodeId, String type, FlowState state) {
    this(executionId, nodeId, type, null, state);
  }
}
