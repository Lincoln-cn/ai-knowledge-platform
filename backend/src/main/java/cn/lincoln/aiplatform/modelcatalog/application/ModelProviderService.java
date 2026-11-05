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
  private final ModelReferencePort references;

  public ModelProviderService(
      ModelProviderRepository providers,
      ModelRepository models,
      ApiKeyCipher cipher,
      UpstreamGateway gateway,
      ModelUpstreamResolver resolver,
      JairouterProperties jairouter,
      ModelReferencePort references) {
    this.providers = providers;
    this.models = models;
    this.cipher = cipher;
    this.gateway = gateway;
    this.resolver = resolver;
    this.jairouter = jairouter;
    this.references = references;
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

  /**
   * 读某个服务商的引用面（{@code #300}）：挂在它下面的模型，以及这些模型的下游引用。
   *
   * @param id 服务商 id；不存在 ⇒ 404
   * @return 引用面；没有模型时 {@link ModelReference#NONE}
   */
  @Transactional(readOnly = true)
  public ModelReference referencesOf(UUID id) {
    require(id);
    return references.ofProvider(id);
  }

  /**
   * 删除服务商（{@code #300} 起带<strong>明确的一并处理</strong>）。
   *
   * <p>★ 这个守卫<strong>本来就有</strong>（提交 {@code 9ba0983} 起的 {@code countByProviderId > 0 ⇒ 400}），
   * 本批补的是三件事：① 状态码从 {@code 400} 改成 {@code 409}（请求没错，是资源当前状态不允许 ⇒ 前端才能把它和"我传错了"分开）； ②
   * 响应体带引用面明细（哪几个模型、它们各自被哪些应用/库/默认键引用）；③ 给出<strong>强制删除</strong>这条明确的出路。
   *
   * <h3>★ 判据 5「不许留悬空指针」怎么落</h3>
   *
   * <p>服务商的连带处理只有两种合法做法：<strong>阻止</strong> 或 <strong>一并删模型</strong>。这里默认阻止， {@code force=true}
   * 时走"一并删" —— 删完之后没有任何一行的 {@code provider_id} 还指向它， 因为外键是 {@code ON DELETE RESTRICT}（{@code
   * V46}），悬空指针在库层也插不进来。
   *
   * <p>★★ <strong>强制删除比判据更严的一处</strong>：若这些模型<strong>自己</strong>还被应用/知识库/平台默认键引用， 即便 {@code
   * force=true} 也<strong>仍然 409</strong>并列出引用者。理由：那样"删服务商"会连带把一个应用变成不可提问 ——
   * 用户点的是"删服务商"，不该收到"你的应用坏了"这种后果。要让模型消失，请先把应用改绑到别的模型，再回来删。
   *
   * @param id 服务商 id
   * @param force 明知它下面还有模型仍要删（这些模型会被一并删除）
   */
  @Transactional
  public void delete(UUID id, boolean force) {
    ModelProvider provider = require(id);
    ModelReference refs = references.ofProvider(id);
    if (refs.models() > 0 && !force) {
      throw new BusinessException(
          ErrorCode.CONFLICT,
          "服务商「" + provider.name() + "」下还有 " + refs.models() + " 个模型，无法删除",
          "请先把这些模型改绑到其它服务商，或删除它们后再删服务商；" + "确认要连模型一起删，请用「强制删除」",
          refs);
    }
    boolean modelsStillUsed =
        !refs.apps().isEmpty()
            || !refs.knowledgeBases().isEmpty()
            || refs.documents() > 0
            || !refs.defaultKeys().isEmpty();
    if (modelsStillUsed) {
      // ★★ force 也拦：删服务商会连带删模型，而这些模型正被应用/知识库/平台默认键用着 ——
      //    那是"别人的功能坏了"，不是"你删了一个服务商"。
      throw new BusinessException(
          ErrorCode.CONFLICT,
          "服务商「"
              + provider.name()
              + "」下的 "
              + refs.models()
              + " 个模型仍被引用："
              + refs.downstreamSummary()
              + "，不能连它们一起强制删除",
          "请先把这些应用/知识库/平台默认键改绑到别的模型，再回来删服务商（改绑不是删除，数据不会丢）",
          refs);
    }
    if (refs.models() > 0) {
      // ★ 一并删模型：删完这个服务商下不存在任何一行还指向它，判据 5 的"不许留悬空指针"在库里成立。
      List<ModelReference.Named> victims = references.modelsOfProvider(id, Integer.MAX_VALUE);
      for (ModelReference.Named victim : victims) {
        models
            .findById(victim.id())
            .ifPresent(
                model -> {
                  // ★ 与 ModelService#delete 同口径：先关启用位再删，中间态里没有仍在对外服务的模型
                  model.clearEnable();
                  models.delete(model);
                });
      }
      log.warn(
          "强制删除服务商 {}（{}），一并删除其下 {} 个模型：{}",
          provider.name(),
          provider.id(),
          victims.size(),
          victims.stream().map(ModelReference.Named::name).toList());
    }
    try {
      providers.delete(provider);
    } catch (DataIntegrityViolationException race) {
      // ★ 读与删之间可能有并发：另一个请求刚把模型绑到这个服务商。数据库的
      //   ON DELETE RESTRICT 是最后一道守卫，但它抛的是 500 形状的异常；这里翻译成同一条
      //   可操作提示，避免用户看到“服务器内部错误”而不知道是还有模型引用。
      throw new BusinessException(
          ErrorCode.CONFLICT, "服务商「" + provider.name() + "」仍被模型引用，无法删除", "请刷新模型列表，把引用它的模型改绑后再删");
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
