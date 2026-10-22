package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import java.util.UUID;

/**
 * 服务商视图（契约 {@code ModelProviderInfo}）。
 *
 * <p>★ 绝不回传 {@code apiKey}：只有 {@code hasApiKey}。与 {@code ModelView} 不含 {@code apiKeyCipher} 同一条纪律。
 *
 * @param modelCount 引用它的模型数（删除守卫与界面提示都用它）
 */
public record ModelProviderView(
    UUID id,
    String name,
    ProviderKind kind,
    String apiBase,
    AuthStyle authStyle,
    ProviderEgress egress,
    boolean enabled,
    boolean hasApiKey,
    String remark,
    long modelCount) {

  public static ModelProviderView from(ModelProvider provider, long modelCount) {
    return new ModelProviderView(
        provider.id(),
        provider.name(),
        provider.kind(),
        provider.apiBase(),
        provider.authStyle(),
        provider.egress(),
        provider.enabled(),
        provider.hasApiKey(),
        provider.remark(),
        modelCount);
  }
}
