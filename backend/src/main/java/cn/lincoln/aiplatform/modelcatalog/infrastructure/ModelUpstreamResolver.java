package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把“模型资产”解析成“上游连接”的<strong>唯一</strong>入口。
 *
 * <h3>★★ 为什么必须只有这一处</h3>
 *
 * <p>服务商、旧式直连、默认上游三者的优先级（{@code provider_id} 有值 ⇒ 服务商赢；否则模型自带 {@code
 * api_base/key}；再否则默认上游）如果散在对话、向量化、加载校验三处，任何一处漏判都不会报错： 要么用了旧 key、要么绕过了出网边界。这里收成一个返回值，调用方只读。
 *
 * <h3>★ fail-closed</h3>
 *
 * <p>绑了服务商但服务商不存在 / 已停用 ⇒ 抛可读异常，<strong>不</strong>回落到旧 key。回落会让“停用服务商” 看起来生效了（界面上停用了），实际模型还在用旧凭据跑。
 */
@Component
public class ModelUpstreamResolver {

  private static final Logger log = LoggerFactory.getLogger(ModelUpstreamResolver.class);

  private final ModelProviderRepository providers;
  private final ApiKeyCipher cipher;

  public ModelUpstreamResolver(ModelProviderRepository providers, ApiKeyCipher cipher) {
    this.providers = providers;
    this.cipher = cipher;
  }

  /**
   * 解析这一行的实际上游。
   *
   * @throws BusinessException 服务商不存在 / 已停用 / 密钥解不开（都带可读原因）
   */
  public ResolvedUpstream resolve(Model model) {
    if (model == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "模型不存在", "它可能已被删除，请刷新后重试");
    }
    if (model.providerId() == null) {
      return ResolvedUpstream.legacy(
          model.apiBase(), decryptLegacy(model), egressOfSupplier(model.supplier()));
    }
    ModelProvider provider = requireProvider(model.providerId());
    if (!provider.enabled()) {
      throw new BusinessException(
          ErrorCode.MODEL_NOT_READY,
          "模型「" + model.name() + "」绑定的服务商「" + provider.name() + "」已停用",
          "请启用该服务商，或把模型改绑到其它服务商后再试");
    }
    return new ResolvedUpstream(
        provider.id(),
        provider.name(),
        provider.kind(),
        provider.apiBase(),
        decryptProvider(provider),
        provider.authStyle(),
        provider.egress());
  }

  /** 按服务商 id 解析（基础模型目录 / 测试连接用）。 */
  public ResolvedUpstream resolve(ModelProvider provider) {
    if (provider == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "服务商不存在", "它可能已被删除，请刷新后重试");
    }
    if (!provider.enabled()) {
      throw new BusinessException(
          ErrorCode.MODEL_NOT_READY, "服务商「" + provider.name() + "」已停用", "请先启用该服务商，或换一个服务商");
    }
    return new ResolvedUpstream(
        provider.id(),
        provider.name(),
        provider.kind(),
        provider.apiBase(),
        decryptProvider(provider),
        provider.authStyle(),
        provider.egress());
  }

  /**
   * 这个模型在出网闸门里的边界。
   *
   * <p>★ 服务商被停用/删除时返回空 ⇒ 调用方 fail-closed（一条引用都不给），而不是回落到旧 supplier。 回落会让“停用”只停了一半。
   */
  public Optional<ProviderEgress> egressOf(Model model) {
    if (model == null) {
      return Optional.empty();
    }
    if (model.providerId() == null) {
      return Optional.of(egressOfSupplier(model.supplier()));
    }
    return providers
        .findById(model.providerId())
        .filter(ModelProvider::enabled)
        .map(ModelProvider::egress);
  }

  /** 外部 / 本地 → 与既有 {@link Supplier} 的映射（旧字段仍要非空，视图与闸门兼容）。 */
  public static Supplier supplierOf(ProviderEgress egress) {
    return egress == ProviderEgress.LOCAL ? Supplier.LOCAL_INFERENCE : Supplier.EXTERNAL_API;
  }

  private ModelProvider requireProvider(UUID providerId) {
    return providers
        .findById(providerId)
        .orElseThrow(
            () ->
                new BusinessException(
                    ErrorCode.MODEL_NOT_READY, "模型绑定的服务商不存在", "它可能已被删除；请把模型改绑到其它服务商，或解绑后使用旧式直连"));
  }

  private String decryptProvider(ModelProvider provider) {
    if (!provider.hasApiKey()) {
      return null;
    }
    try {
      return cipher.decrypt(provider.apiKeyCipher(), provider.apiKeyIv());
    } catch (IllegalArgumentException broken) {
      log.warn("服务商 {} 的密钥解不开：{}", provider.id(), broken.getMessage());
      throw new BusinessException(
          ErrorCode.MODEL_NOT_READY,
          "服务商「" + provider.name() + "」的密钥无法解密",
          "请重新录入该服务商的 API Key（app.model.api-key-secret 变更后旧密文会解不开）");
    }
  }

  private String decryptLegacy(Model model) {
    if (model.apiKeyCipher() == null || model.apiKeyIv() == null) {
      return null;
    }
    try {
      return cipher.decrypt(model.apiKeyCipher(), model.apiKeyIv());
    } catch (IllegalArgumentException broken) {
      log.warn("模型 {} 的旧式密钥解不开：{}", model.id(), broken.getMessage());
      throw new BusinessException(
          ErrorCode.MODEL_NOT_READY,
          "模型「" + model.name() + "」的旧式密钥无法解密",
          "请重新录入该模型的 API Key，或把它绑定到服务商后使用服务商密钥");
    }
  }

  private static ProviderEgress egressOfSupplier(Supplier supplier) {
    return supplier == Supplier.LOCAL_INFERENCE ? ProviderEgress.LOCAL : ProviderEgress.EXTERNAL;
  }
}
