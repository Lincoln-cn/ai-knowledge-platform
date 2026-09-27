/**
 * 模型目录与接入（modelcatalog）—— 供应商、基础模型、启用模型、能力探测，以及对话/向量两个端口。
 *
 * <p>★ 底层由 Spring AI 实现，但<strong>端口在我们这边</strong>（{@code 01 §二} 第 3 行）： 这是"若框架下不了思考开关、就换原生适配器"（风险
 * R9）这条退路能成立的前提 —— 换适配器不影响任何业务模块。
 *
 * <p>依赖：{@code platform}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform"})
package cn.lincoln.aiplatform.modelcatalog;

import org.springframework.modulith.ApplicationModule;
