package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.JairouterProperties;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务商配置（{@code /model-providers}）—— CRUD、密钥、测试连接、JAiRouter 快速绑定。
 *
 * <h3>★★ 快速绑定不是一条特殊运行时路径</h3>
 *
 * <p>它只做两件事：用 {@link AuthStyle#X_API_KEY} 探一次 {@code /models}，然后把探通的配置写成一个普通的 {@link
 * ProviderKind#GATEWAY} 服务商。之后的对话/向量化仍走 {@link ModelUpstreamResolver}，没有第二套分支。
 */
@Service
public class ModelProviderService {

  private static final Logger log = LoggerFactory.getLogger(ModelProviderService.class);

  private static final int SAMPLE_MODELS = 8;

  private final ModelProviderRepository providers;
  private final ModelRepository models;
  private final ApiKeyCipher cipher;
  private final UpstreamGateway gateway;
  private final ModelUpstreamResolver resolver;
  private final JairouterProperties jairouter;

  public ModelProviderService(
      ModelProviderRepository providers,
      ModelRepository models,
      ApiKeyCipher cipher,
      UpstreamGateway gateway,
      ModelUpstreamResolver resolver,
      JairouterProperties jairouter) {
    this.providers = providers;
    this.models = models;
    this.cipher = cipher;
    this.gateway = gateway;
    this.resolver = resolver;
    this.jairouter = jairouter;
  }

  // ────────────────────────── 查询 / CRUD ──────────────────────────

  @Transactional(readOnly = true)
  public List<ModelProviderView> list() {
    return providers.findAll().stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public ModelProviderView get(UUID id) {
    return view(require(id));
  }

  @Transactional
  public ModelProviderView create(ProviderDraft draft, UUID operatorId) {
    requireUniqueName(draft.name(), null);
    ProviderKind kind = draft.kind() == null ? ProviderKind.OPENAI_COMPATIBLE : draft.kind();
    String apiBase = resolveApiBase(kind, draft.apiBase(), false);
    AuthStyle authStyle = draft.authStyle() == null ? kind.defaultAuthStyle() : draft.authStyle();
    ProviderEgress egress = draft.egress() == null ? kind.defaultEgress() : draft.egress();
    ModelProvider provider =
        new ModelProvider(
            UUID.randomUUID(), draft.name(), kind, apiBase, authStyle, egress, operatorId);
    provider.update(draft.name(), apiBase, authStyle, egress, draft.remark(), operatorId);
    if (hasText(draft.apiKey())) {
      seal(provider, draft.apiKey());
    }
    providers.save(provider);
    log.info("新建服务商 {}（{}，{}）", provider.name(), provider.kind(), provider.apiBase());
    return view(provider);
  }

  @Transactional
  public ModelProviderView update(UUID id, ProviderDraft draft, UUID operatorId) {
    ModelProvider provider = require(id);
    requireUniqueName(draft.name(), id);
    String apiBase = resolveApiBase(provider.kind(), draft.apiBase(), false);
    AuthStyle authStyle =
        draft.authStyle() == null ? provider.kind().defaultAuthStyle() : draft.authStyle();
    ProviderEgress egress =
        draft.egress() == null ? provider.kind().defaultEgress() : draft.egress();
    provider.update(draft.name(), apiBase, authStyle, egress, draft.remark(), operatorId);
    providers.save(provider);
    return view(provider);
  }

  @Transactional
  public ModelProviderView setEnabled(UUID id, boolean enabled, UUID operatorId) {
    ModelProvider provider = require(id);
    provider.enabled(enabled, operatorId);
    providers.save(provider);
    return view(provider);
  }

  @Transactional
  public ModelProviderView setKey(UUID id, String apiKey, UUID operatorId) {
    if (!hasText(apiKey)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 不能为空", "如果要清空密钥，请用「清除密钥」");
    }
    ModelProvider provider = require(id);
    seal(provider, apiKey);
    provider.update(
        provider.name(),
        provider.apiBase(),
        provider.authStyle(),
        provider.egress(),
        provider.remark(),
        operatorId);
    providers.save(provider);
    return view(provider);
  }

  @Transactional
  public ModelProviderView clearKey(UUID id, UUID operatorId) {
    ModelProvider provider = require(id);
    provider.clearApiKey();
    provider.update(
        provider.name(),
        provider.apiBase(),
        provider.authStyle(),
        provider.egress(),
        provider.remark(),
        operatorId);
    providers.save(provider);
    return view(provider);
  }

  @Transactional
  public void delete(UUID id) {
    ModelProvider provider = require(id);
    long count = models.countByProviderId(id);
    if (count > 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "服务商「" + provider.name() + "」下还有 " + count + " 个模型，无法删除",
          "请先把这些模型改绑到其它服务商，或删除它们后再删服务商");
    }
    try {
      providers.delete(provider);
    } catch (DataIntegrityViolationException race) {
      // ★ count 与 delete 之间可能有并发：另一个请求刚把模型绑到这个服务商。数据库的
      //   ON DELETE RESTRICT 是最后一道守卫，但它抛的是 500 形状的异常；这里翻译成同一条
      //   可操作提示，避免用户看到“服务器内部错误”而不知道是还有模型引用。
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "服务商「" + provider.name() + "」仍被模型引用，无法删除", "请刷新模型列表，把引用它的模型改绑后再删");
    }
    log.info("删除服务商 {}（{}）", provider.name(), provider.id());
  }

  // ────────────────────────── 测试连接 / 快速绑定 ──────────────────────────

  /** 测试已保存的服务商：用它的地址/密钥探一次 {@code /models}。 */
  @Transactional(readOnly = true)
  public ProviderTestResult test(UUID id) {
    ModelProvider provider = require(id);
    return probe(provider);
  }

  /**
   * 一键绑定 JAiRouter。
   *
   * <p>★ 先探再写：探不通就不落库（避免用户以为“绑定成功”但实际 401）。同一 {@code kind=GATEWAY} + 同一地址已存在时 复用并更新密钥，不新建第二条。
   *
   * @param apiBase 可空 = 用 {@code app.providers.jairouter.base-url}
   * @param apiKey 可空（本地开发网关可能不校验；401 时提示去控制台创建）
   */
  @Transactional
  public QuickBindResult quickBind(String apiBase, String apiKey, UUID operatorId) {
    String base = resolveApiBase(ProviderKind.GATEWAY, apiBase, true);
    ProviderTestResult probe = probe(ProviderKind.GATEWAY, base, apiKey);
    if (!probe.ok()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "没有绑定成功：" + probe.error(),
          "确认 JAiRouter 已启动、地址是 http://主机:8080/v1；API Key 可在 " + jairouter.getConsoleUrl() + " 创建");
    }
    ModelProvider existing =
        providers.findByKindAndApiBase(ProviderKind.GATEWAY, base).orElse(null);
    boolean created = existing == null;
    ModelProvider provider;
    if (existing == null) {
      provider =
          new ModelProvider(
              UUID.randomUUID(),
              "JAiRouter（" + hostOf(base) + "）",
              ProviderKind.GATEWAY,
              base,
              AuthStyle.X_API_KEY,
              ProviderEgress.EXTERNAL,
              operatorId);
    } else {
      provider = existing;
      // ★ 复用已存在的服务商时不替用户改启用状态：用户停用它是有意为之，快速绑定只负责
      //   更新地址/密钥，不该把“停用”悄悄变成“启用”。需要启用请走 PATCH /enabled。
    }
    if (hasText(apiKey)) {
      seal(provider, apiKey);
    }
    providers.save(provider);
    log.info(
        "{} JAiRouter 服务商 {}（{}，探测到 {} 个模型）",
        created ? "快速绑定" : "复用",
        provider.name(),
        base,
        probe.modelCount());
    return new QuickBindResult(view(provider), created, probe.modelCount(), probe.sampleModels());
  }

  /**
   * @param apiBase 允许空：GATEWAY 默认用 {@code app.providers.jairouter.base-url}
   * @param apiKey 只用于本次探测 / 新建，不落日志
   */
  private ProviderTestResult probe(ProviderKind kind, String apiBase, String apiKey) {
    long started = System.nanoTime();
    try {
      List<UpstreamGateway.UpstreamModel> catalog =
          gateway.listModels(apiBase, apiKey, AuthStyle.X_API_KEY);
      return new ProviderTestResult(
          true,
          catalog.size(),
          catalog.stream().limit(SAMPLE_MODELS).map(UpstreamGateway.UpstreamModel::id).toList(),
          elapsedMs(started),
          null);
    } catch (UpstreamGateway.UpstreamUnreachableException failure) {
      String error = failure.getMessage();
      if (error != null && error.contains("HTTP 401")) {
        error = "JAiRouter 返回 401：需要 API Key。请在 " + jairouter.getConsoleUrl() + " 创建一个 API Key 后填入";
      }
      return new ProviderTestResult(false, 0, List.of(), elapsedMs(started), error);
    }
  }

  private ProviderTestResult probe(ModelProvider provider) {
    long started = System.nanoTime();
    if (!provider.enabled()) {
      return new ProviderTestResult(false, 0, List.of(), 0, "服务商已停用，请先启用");
    }
    try {
      ResolvedUpstream target = resolver.resolve(provider);
      List<UpstreamGateway.UpstreamModel> catalog =
          gateway.listModels(target.apiBase(), target.apiKey(), target.authStyle());
      return new ProviderTestResult(
          true,
          catalog.size(),
          catalog.stream().limit(SAMPLE_MODELS).map(UpstreamGateway.UpstreamModel::id).toList(),
          elapsedMs(started),
          null);
    } catch (UpstreamGateway.UpstreamUnreachableException failure) {
      return new ProviderTestResult(false, 0, List.of(), elapsedMs(started), failure.getMessage());
    } catch (BusinessException failure) {
      return new ProviderTestResult(false, 0, List.of(), elapsedMs(started), failure.getMessage());
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  private ModelProviderView view(ModelProvider provider) {
    return ModelProviderView.from(provider, models.countByProviderId(provider.id()));
  }

  private ModelProvider require(UUID id) {
    return providers
        .findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "服务商不存在", "它可能已被删除，请刷新后重试"));
  }

  private void requireUniqueName(String name, UUID excludeId) {
    if (!hasText(name)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "服务商名称必填", "例如「OpenAI 生产」");
    }
    String trimmed = name.trim();
    boolean exists =
        providers.findAll().stream()
            .anyMatch(p -> p.name().equalsIgnoreCase(trimmed) && !p.id().equals(excludeId));
    if (exists) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "服务商名称「" + trimmed + "」已存在",
          "换一个名字（例如加「生产 / 测试」后缀），避免两个服务商看起来是同一个");
    }
  }

  private String resolveApiBase(ProviderKind kind, String apiBase, boolean useJairouterDefaults) {
    String raw = apiBase;
    if (!hasText(raw) && useJairouterDefaults) {
      raw = jairouter.getBaseUrl();
    }
    if (!hasText(raw)) {
      raw = kind.defaultApiBase();
    }
    if (!hasText(raw)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "服务地址必填", "这个类型没有预设地址（MiMo / GLM 等以厂商文档为准），请手动填写完整地址");
    }
    String normalized = normalizeBase(raw.trim());
    if (useJairouterDefaults && !normalized.endsWith("/v1")) {
      normalized = normalizeBase(normalized + "/v1");
    }
    return normalized;
  }

  /**
   * 地址归一：去掉尾斜杠，并把 scheme / host 统一成小写、去掉默认端口。
   *
   * <p>★ 这不是“好看”，而是让快速绑定的幂等键（{@code kind + apiBase}）真的幂等： {@code HTTP://LOCALHOST:8080/v1/} 与
   * {@code http://localhost:8080/v1} 是同一台上游， 不归一就会建出两条服务商，之后轮换密钥只改了其中一条。
   */
  private static String normalizeBase(String value) {
    String result = value;
    while (result.length() > 1 && result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    try {
      URI uri = URI.create(result);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return result;
      }
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
        port = -1;
      }
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
    } catch (IllegalArgumentException malformed) {
      // 交给 ModelProvider.requireApiBase 的 http(s) 校验给出可读错误；这里只做尽力归一。
      return result;
    }
  }

  private void seal(ModelProvider provider, String plaintext) {
    ApiKeyCipher.Sealed sealed = cipher.encrypt(plaintext.trim());
    provider.apiKey(sealed.cipherBase64(), sealed.ivBase64());
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static String hostOf(String apiBase) {
    try {
      return java.net.URI.create(apiBase).getHost() == null
          ? apiBase
          : java.net.URI.create(apiBase).getHost();
    } catch (IllegalArgumentException e) {
      return apiBase;
    }
  }

  /**
   * 创建 / 更新服务商的入参。
   *
   * @param apiKey 只在创建 / 快速绑定时使用；更新走 {@link #setKey}，留空不修改
   */
  public record ProviderDraft(
      String name,
      ProviderKind kind,
      String apiBase,
      AuthStyle authStyle,
      ProviderEgress egress,
      String apiKey,
      String remark) {}

  /** 快速绑定结果。 */
  public record QuickBindResult(
      ModelProviderView provider, boolean created, int modelCount, List<String> sampleModels) {}
}
