package cn.lincoln.aiplatform.modelcatalog.application;

import java.util.List;

/**
 * 服务商“测试连接”的结果。
 *
 * <p>★ 连接失败是<strong>预期结果</strong>，不是 500：界面需要显示原因并让用户改地址/密钥。
 *
 * @param ok 是否连通
 * @param modelCount 上游清单里的模型数
 * @param sampleModels 前几个模型名（便于确认“连的是不是那台”）
 * @param elapsedMs 耗时
 * @param error 失败原因（ok=true 时为 null）
 */
public record ProviderTestResult(
    boolean ok, int modelCount, List<String> sampleModels, long elapsedMs, String error) {}
