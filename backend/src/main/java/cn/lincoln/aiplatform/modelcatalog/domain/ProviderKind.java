package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.Locale;

/**
 * 服务商类型（{@code ai_model_provider.kind}）。
 *
 * <p>★ 它是<strong>协议与预设</strong>的分类，不是“是否出网”的分类：一个自建 vLLM 也用 OpenAI 协议，但它是 {@link
 * ProviderEgress#LOCAL}；一个 DeepSeek 服务商是 {@link ProviderEgress#EXTERNAL}。两者必须分开，否则 {@code #72}
 * 的密级闸门会按厂家名误判。
 *
 * <p>★ 默认地址只放<strong>能核对的</strong>：OpenAI / DeepSeek / 本地 Ollama。MiMo / GLM 的地址与认证方式
 * 以厂商文档和“测试连接”的实测为准，不在这里猜 —— 猜错的表现是“保存成功、调用 401”，看起来像用户填错了。
 */
public enum ProviderKind {
  /** OpenAI 官方（OpenAI 兼容协议，Bearer）。 */
  OPENAI("https://api.openai.com/v1", AuthStyle.BEARER, ProviderEgress.EXTERNAL, true),
  /** DeepSeek（OpenAI 兼容协议，Bearer）。 */
  DEEPSEEK("https://api.deepseek.com/v1", AuthStyle.BEARER, ProviderEgress.EXTERNAL, true),
  /** 小米 MiMo；地址与认证方式待按厂商文档核对，先给模板不写死。 */
  MIMO(null, AuthStyle.BEARER, ProviderEgress.EXTERNAL, true),
  /** 智谱 GLM；可能是 OpenAI 兼容，也可能需要单独的签名适配，先给模板不写死。 */
  GLM(null, AuthStyle.BEARER, ProviderEgress.EXTERNAL, true),
  /** 本地 Ollama 的 OpenAI 兼容端点；默认本机、无认证、不出网。 */
  OLLAMA("http://localhost:11434/v1", AuthStyle.NONE, ProviderEgress.LOCAL, true),
  /** JAiRouter / 其它 OpenAI 兼容网关；认证是 {@code X-API-Key}（JAiRouter 的约定）。 */
  GATEWAY(null, AuthStyle.X_API_KEY, ProviderEgress.EXTERNAL, true),
  /** 任意 OpenAI 兼容端点；地址与认证方式由管理员填。 */
  OPENAI_COMPATIBLE(null, AuthStyle.BEARER, ProviderEgress.EXTERNAL, true);

  private final String defaultApiBase;
  private final AuthStyle defaultAuthStyle;
  private final ProviderEgress defaultEgress;
  private final boolean supportsModelList;

  ProviderKind(
      String defaultApiBase,
      AuthStyle defaultAuthStyle,
      ProviderEgress defaultEgress,
      boolean supportsModelList) {
    this.defaultApiBase = defaultApiBase;
    this.defaultAuthStyle = defaultAuthStyle;
    this.defaultEgress = defaultEgress;
    this.supportsModelList = supportsModelList;
  }

  /**
   * @return 预设地址；{@code null} = 需要管理员按厂商文档填写（不猜）
   */
  public String defaultApiBase() {
    return defaultApiBase;
  }

  public AuthStyle defaultAuthStyle() {
    return defaultAuthStyle;
  }

  public ProviderEgress defaultEgress() {
    return defaultEgress;
  }

  /** 该类型是否支持 {@code GET /models} 目录（不支持时 UI 引导手填基础模型）。 */
  public boolean supportsModelList() {
    return supportsModelList;
  }

  /** 空格 / 大小写不敏感地解析；非法值明确报错，不静默回退。 */
  public static ProviderKind parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("服务商类型必填");
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "服务商类型只支持 OPENAI / DEEPSEEK / MIMO / GLM / OLLAMA / GATEWAY / OPENAI_COMPATIBLE，收到："
              + raw,
          e);
    }
  }
}
