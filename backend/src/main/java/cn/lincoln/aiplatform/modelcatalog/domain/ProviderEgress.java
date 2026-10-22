package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 服务商的出网边界（{@code ai_model_provider.egress}）。
 *
 * <p>★ 它是 {@code #72} 出站闸门的<strong>权威来源</strong>：同样的模型名，放在本地 vLLM 上可以处理机密内容， 放在外部 API
 * 上就不行。不要从服务商类型（OpenAI / DeepSeek / Ollama）推断 —— 自建 vLLM 也用 OpenAI 协议。
 */
public enum ProviderEgress {
  /** 不出网（本机 / 本机房）：密级上限放到最高档。 */
  LOCAL,

  /** 会出网：密级上限取 {@code app.egress.external-api-ceiling}（默认 INTERNAL）。 */
  EXTERNAL;

  public static ProviderEgress parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return EXTERNAL;
    }
    try {
      return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("出网边界只支持 LOCAL / EXTERNAL，收到：" + raw, e);
    }
  }
}
