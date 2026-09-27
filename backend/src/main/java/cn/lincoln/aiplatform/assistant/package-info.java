/**
 * 应用配置（assistant）—— 应用（智能体）配置、发布、访问凭证、导入导出，以及提示词配置数据。
 *
 * <p>★ 提示词<strong>不单列模块</strong>：前端为它留了独立契约文件（{@code api/prompts.ts}）， 但那不代表需要一个独立业务域 —— 它是 {@code
 * assistant} 的配置数据（{@code backend/README.md} 的模块对账）。
 *
 * <p>★ {@code Assistant.type}（{@code simple} / {@code workflow}）<strong>创建时选定、之后不可改</strong>：
 * 改类型等于换了应用，应新建（不变量 {@code AS-I2~I5} 之一）。
 *
 * <p>依赖：{@code platform} · {@code identity} · {@code modelcatalog} · {@code knowledge}。
 */
@ApplicationModule(allowedDependencies = {"platform", "identity", "modelcatalog", "knowledge"})
package cn.lincoln.aiplatform.assistant;

import org.springframework.modulith.ApplicationModule;
