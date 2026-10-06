package cn.lincoln.aiplatform.platform.retrieval;

/**
 * 无引用时的策略（契约 {@code NoRefStrategy}，取值一字不差）。
 *
 * <p>与 {@link RetrievalMode} 同因住在 {@code platform}：它是召回率之外的另一件事 —— "检索到的东西不够时，模型该拒答还是自由回答"（{@code
 * 03 §2.4}、{@code 10 §5}）。 严谨场景（法规、财务）该拒答；开放咨询该自由回答 —— 所以它是<strong>每个库可配</strong>的， 也就需要 {@code
 * knowledge} 能引用它。
 */
public enum NoRefStrategy {
  /** 拒答：没有资料支持时说"未找到"。 */
  REFUSE,
  /** 自由回答：允许模型用自己的知识答，不强制引用。 */
  FREE_ANSWER;

  /** 空值回默认（{@link #REFUSE}，与 mock 的默认值一致）；非法值明确报错。 */
  public static NoRefStrategy parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return REFUSE;
    }
    try {
      return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("无引用策略只支持 REFUSE / FREE_ANSWER，收到：" + raw, e);
    }
  }
}
