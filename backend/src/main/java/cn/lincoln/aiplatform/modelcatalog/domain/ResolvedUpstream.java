package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.UUID;

/**
 * 一个模型真正要用的上游连接（运行时解析结果）。
 *
 * <p>★★ 它是“服务商 / 旧式直连 / 默认上游”三种情况的<strong>唯一出口</strong>：调用方只读它， 不再自己判断 {@code
 * model.providerId}、也不再自己解密 key。否则同一件事会在对话、向量化、加载校验三处各判一次。
 *
 * @param providerId 服务商 id；{@code null} = 旧式直连或默认上游
 * @param providerName 服务商显示名；{@code null} = 旧式直连
 * @param kind 服务商类型；{@code null} = 旧式直连
 * @param apiBase 上游地址；{@code null} 表示走 {@code UpstreamGateway} 的默认上游
 * @param apiKey 明文 key（只在调用上游时短暂存在，不进日志、不进响应）；{@code null} = 无 key
 * @param authStyle 认证头方式
 * @param egress 出网边界（{@code #72} 的权威来源）
 */
public record ResolvedUpstream(
    UUID providerId,
    String providerName,
    ProviderKind kind,
    String apiBase,
    String apiKey,
    AuthStyle authStyle,
    ProviderEgress egress) {

  /** 旧式直连（模型自带 apiBase/key）或默认上游。 */
  public static ResolvedUpstream legacy(String apiBase, String apiKey, ProviderEgress egress) {
    return new ResolvedUpstream(null, null, null, apiBase, apiKey, AuthStyle.BEARER, egress);
  }
}
