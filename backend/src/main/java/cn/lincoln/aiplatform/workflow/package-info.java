/**
 * 编排（workflow）—— 流程定义存取、发布前校验、版本与回滚、画布内调试、流程记录。执行引擎在 V1.0。
 *
 * <p>★ 与其它模块<strong>交付节奏不同</strong>：配置界面已进 MVP，执行引擎仍是 V1.0 （"难的是执行引擎，不是配置界面"）。在引擎到位前，{@code
 * Assistant.type = 'workflow'} 的应用只能配置与发布，不能真正问答。
 *
 * <p>★ 流程定义格式<strong>已冻结</strong>在 {@code frontend/src/api/workflow.ts}：
 * 前端按它存取画布，后端按它实现存储与引擎，<em>改它 = 破坏性变更</em>。
 *
 * <p>⚠️ <strong>本模块的依赖位置是本轮补的，待架构评审确认</strong>：{@code 01 §三.2} 的矩阵 成表时只有 11 个模块（{@code workflow} 在
 * {@code backend/README.md} 的模块对账里才确立为第 12 个）， 因此矩阵里没有它的行。这里按"配置面 + 发布前校验"这一形态取与 {@code assistant}
 * 相同的依赖集： {@code modelcatalog}（节点要选模型）与 {@code knowledge}（{@code search-dataset} 节点要选知识库，
 * 发布前校验需要判断知识库是否停用）。理由与矩阵回写一并记在 {@code 01} 待补清单里。
 */
@ApplicationModule(allowedDependencies = {"platform", "identity", "modelcatalog", "knowledge"})
package cn.lincoln.aiplatform.workflow;

import org.springframework.modulith.ApplicationModule;
