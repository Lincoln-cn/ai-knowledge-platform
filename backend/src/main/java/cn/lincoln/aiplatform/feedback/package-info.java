/**
 * 反馈（feedback）—— 赞踩与评分，以及处理闭环（状态流转、指派、沉淀到知识库）。
 *
 * <p>★ "沉淀到知识库"走的是 {@code knowledge} 的<strong>公开接口</strong>而非直接写它的表； 这条路径跨越 L5→L2，是矩阵里刻意留的一条边。
 *
 * <p>依赖：{@code platform} · {@code identity} · {@code conversation}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform", "identity", "conversation"})
package cn.lincoln.aiplatform.feedback;

import org.springframework.modulith.ApplicationModule;
