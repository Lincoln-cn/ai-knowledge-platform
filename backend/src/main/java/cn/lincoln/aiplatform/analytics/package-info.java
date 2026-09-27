/**
 * 运营指标（analytics）—— 用量指标、趋势、质量观测。派生读模型，只订阅事件。
 *
 * <p>★ <strong>不依赖任何业务模块</strong>（{@code 01 §三.3}）：它是"分析不影响业务"的架构保障 ——
 * 统计逻辑再重也拖不慢问答。代价是它必须靠事件重建自己的读模型， 所以"事件是否真的落库"（Modulith 出站事件表）是它的隐含前提。
 *
 * <p>依赖：{@code platform}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform"})
package cn.lincoln.aiplatform.analytics;

import org.springframework.modulith.ApplicationModule;
