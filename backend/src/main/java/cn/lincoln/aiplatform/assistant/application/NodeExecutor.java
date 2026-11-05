package cn.lincoln.aiplatform.assistant.application;

/**
 * 节点执行器（{@code #113} 判据 5 的注册表边界）。
 *
 * <p>★ <strong>一个执行器只管一种节点类型</strong>（{@link #type()}），由 {@link LinearWorkflowExecutor} 按 type
 * 收成一张「注册表」。执行器只依赖 {@link WorkflowNodeContext}（一次执行 + 当前节点 + 共享 {@link
 * FlowState}），<strong>不碰任何跨模块服务</strong> —— 真跑 {@code AI_CHAT_NODE} 要接 {@code conversation} 的
 * {@code ChatService} 会越模块边界，本版本刻意不接（见 {@code package-info} 的 {@code allowedDependencies}）。
 *
 * <p>★ {@link #execute} 抛任何异常 = 该节点执行失败；{@link LinearWorkflowExecutor} 的 {@code @Transactional}
 * 会把这一跑的节点记录一并回滚（L1 检查点）。
 */
public interface NodeExecutor {

  /** 这个执行器处理的节点类型（如 {@code START_NODE}）。 */
  String type();

  /** 执行一个节点；抛异常 = 失败。 */
  void execute(WorkflowNodeContext ctx) throws Exception;
}
