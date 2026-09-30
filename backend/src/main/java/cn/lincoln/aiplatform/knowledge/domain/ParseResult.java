package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 解析结果（第 6 周 · 0.2.1）—— Tika 管线的产物与失败说明。
 *
 * <p>★ {@code markdown} 在<strong>失败时也可能非空</strong>：{@code 12 §2.1} 的 catch 分支要求 返回 handler 已缓冲的部分
 * —— 「解析失败 = 整篇丢弃」会让用户对着空文档猜内容。
 *
 * @param markdown 解析出的 Markdown（失败时为已缓冲的部分，永不为 null，最多为空串）
 * @param success 是否完整解析成功
 * @param failReason 失败的可读原因（{@code DOC-I2}）；成功时为 null。必须给人看，不是堆栈
 */
public record ParseResult(String markdown, boolean success, String failReason) {

  /** 成功。 */
  public static ParseResult ok(String markdown) {
    return new ParseResult(markdown == null ? "" : markdown, true, null);
  }

  /** 失败但保留已缓冲部分（{@code 12 §2.1}）。 */
  public static ParseResult failed(String failReason, String partialMarkdown) {
    return new ParseResult(partialMarkdown == null ? "" : partialMarkdown, false, failReason);
  }
}
