package cn.lincoln.aiplatform.assistant.application;

import java.util.UUID;

/**
 * 传给 {@link NodeExecutor} 的执行上下文：一次执行的 id + 当前节点 + 共享 {@link FlowState}。
 *
 * <p>★ {@code state} 在同一次执行的所有节点间共享 —— 判据 3 的 {@link KeyUpdate}（{@code REPLACE} / {@code
 * APPEND}）就是多个节点往同一个 {@code state} 键写时的语义。
 */
public record WorkflowNodeContext(UUID executionId, String nodeId, String type, FlowState state) {}
