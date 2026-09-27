/**
 * 知识资产（knowledge）—— 知识库、分类、文档、分段的生命周期管理。
 *
 * <p>★ 它是<strong>密级传播链的起点</strong>：知识库上设 ceiling，文档建档时必填密级， 分段强制继承文档密级（不允许单独下调）—— 见 {@code 13} 与
 * {@code 02} 的 {@code SEG-I5~I7}。 密级是"会进检索谓词、会改数据形状"的一类字段，所以必须在第一次就做对。
 *
 * <p>依赖：{@code platform} · {@code identity} · {@code modelcatalog}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform", "identity", "modelcatalog"})
package cn.lincoln.aiplatform.knowledge;

import org.springframework.modulith.ApplicationModule;
