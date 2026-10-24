package cn.lincoln.aiplatform.assistant.application;

import java.util.List;

/**
 * 导入前的依赖检查结果（契约 {@code BundleCheckResult}）。
 *
 * <p>★ 三个字段各有分工：{@code legacy} 回答"这个包是不是当前格式"（旧包仍然可读，但要让导入方知道）， {@code dependencies} 是逐条依赖的现状，
 * {@code hasMissing} 是"能不能直接导入"的唯一判据 —— 页面据此决定要不要让用户勾"跳过引用导入"。
 *
 * <p>⚠️ 本接口<strong>只做检查</strong>：它收到的依赖清单来自客户端解析出的包，服务端没有 ids 可复核；真正的权威复算发生在 {@link
 * AssistantService#importBundle}，那里拿得到完整 {@code config}。
 */
public record BundleCheckResult(
    String format,
    boolean legacy,
    List<AssistantBundleView.Dependency> dependencies,
    boolean hasMissing) {}
