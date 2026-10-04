package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 解析结果（第 6 周 · 0.2.1）—— Tika 管线的产物与失败说明。
 *
 * <p>★ {@code markdown} 在<strong>失败时也可能非空</strong>：{@code 12 §2.1} 的 catch 分支要求 返回 handler 已缓冲的部分
 * —— 「解析失败 = 整篇丢弃」会让用户对着空文档猜内容。
 *
 * <p>★ {@code failHint}（下一步建议）由<strong>解析器自己</strong>给，不是调用方硬编码一句通用的。 理由：同一个"解析不出内容"背后是完全不同的下一步 ——
 * 「扫描件但本机没装 OCR 引擎」要的是"装引擎"，「真的是空文档」要的是"换文件"， 「文件损坏」要的是"重新导出"。统一写一句「修复文件后重新上传」等于把三种情况揉成一句
 * 谁也用不上的话（{@code DOC-I2} 要的是<strong>可执行的</strong>下一步）。
 *
 * @param markdown 解析出的 Markdown（失败时为已缓冲的部分，永不为 null，最多为空串）
 * @param success 是否完整解析成功
 * @param failReason 失败的可读原因（{@code DOC-I2}）；成功时为 null。必须给人看，不是堆栈
 * @param failHint 失败时的下一步建议；成功或没有更好的建议时为 null
 * @param pageCount 总页数；未知（非分页格式）时为 0
 * @param ocrPageCount 其中**走了 OCR 的页数**；未知时为 0。 ★ 这是「这份文件有几页是扫描件」的唯一来源（{@code 12 §11.3} 要求可观测）——
 *     它来自解析器读到的 PDF 元数据，**不是**我们自己数的：逐页判定与统计都由 Tika 内建完成。
 */
public record ParseResult(
    String markdown,
    boolean success,
    String failReason,
    String failHint,
    int pageCount,
    int ocrPageCount) {

  /** 成功（页数未知）。 */
  public static ParseResult ok(String markdown) {
    return ok(markdown, 0, 0);
  }

  /** 成功 + 页数事实。 */
  public static ParseResult ok(String markdown, int pageCount, int ocrPageCount) {
    return new ParseResult(
        markdown == null ? "" : markdown, true, null, null, pageCount, ocrPageCount);
  }

  /** 失败但保留已缓冲部分（{@code 12 §2.1}），不带下一步建议。 */
  public static ParseResult failed(String failReason, String partialMarkdown) {
    return failed(failReason, null, partialMarkdown);
  }

  /** 失败 + 可读原因 + <strong>针对这个原因</strong>的下一步建议。 */
  public static ParseResult failed(String failReason, String failHint, String partialMarkdown) {
    return failed(failReason, failHint, partialMarkdown, 0, 0);
  }

  /** 失败 + 页数事实（扫描件解析失败时也要留下"它有几页、其中几页走了 OCR"）。 */
  public static ParseResult failed(
      String failReason, String failHint, String partialMarkdown, int pageCount, int ocrPageCount) {
    return new ParseResult(
        partialMarkdown == null ? "" : partialMarkdown,
        false,
        failReason,
        failHint,
        pageCount,
        ocrPageCount);
  }
}
