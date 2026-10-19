package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 服务商请求的认证方式。
 *
 * <p>★ 这不是“可选装饰”：JAiRouter 的 API Key 头是 {@code X-API-Key}，而 OpenAI / DeepSeek 是 {@code
 * Authorization: Bearer}。写死 Bearer 的症状是“绑上了、一调就 401”，而它看起来像 key 填错了。
 */
public enum AuthStyle {
  /** {@code Authorization: Bearer <key>}（OpenAI / DeepSeek / 多数兼容端点）。 */
  BEARER("Authorization", "Bearer "),

  /** {@code X-API-Key: <key>}（JAiRouter 的 API Key 约定）。 */
  X_API_KEY("X-API-Key", ""),

  /** 不带认证头（本地 Ollama 等）。 */
  NONE(null, "");

  private final String headerName;
  private final String prefix;

  AuthStyle(String headerName, String prefix) {
    this.headerName = headerName;
    this.prefix = prefix;
  }

  /**
   * @return 头名；{@code null} = 不带认证头
   */
  public String headerName() {
    return headerName;
  }

  /**
   * @return 头值前缀（Bearer 带一个空格，X-API-Key 没有）
   */
  public String prefix() {
    return prefix;
  }

  /** 空值按 {@link #BEARER}（与既有模型兼容）；非法值明确报错。 */
  public static AuthStyle parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return BEARER;
    }
    try {
      return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("认证方式只支持 BEARER / X_API_KEY / NONE，收到：" + raw, e);
    }
  }
}
