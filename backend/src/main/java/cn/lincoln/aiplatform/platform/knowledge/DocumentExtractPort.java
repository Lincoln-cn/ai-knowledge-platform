package cn.lincoln.aiplatform.platform.knowledge;

/**
 * 文档解析端口：把一份文件字节解析成 Markdown。
 *
 * <p>★ <strong>为什么要有这个端口</strong>：真正的解析实现（Tika + OCR + 十几种格式分支）在 {@code knowledge} 模块里，
 * 而工作流的 {@code DOCUMENT_EXTRACT_NODE} 在 {@code assistant} 模块里。{@code ModuleBoundaryTest} 不允许
 * {@code assistant} 直接依赖 {@code knowledge} 的实现类 —— 与 {@code #293}（{@code EntityRecallPort} 搬模块）
 * 同一条判据：<strong>跨模块要用的是「能力」，不是「那个类」</strong>。
 *
 * <p>★ <strong>返回值里没有「页数」「OCR 页数」</strong>：那些是入库时的统计口径（{@code kb_document} 的列），
 * 工作流节点只关心「解析出来的正文」与「失败了没有」。多加两个字段会让实现方被迫在解析阶段做入库阶段的事。
 *
 * <p>★ <strong>失败不抛异常</strong>：解析失败是一种正常结果（文件坏了 / 格式不支持），调用方要拿到
 * {@link Result#failReason()} 才能把它写进节点记录。抛异常会让执行器把「文件坏了」与「系统坏了」当成同一件事。
 */
public interface DocumentExtractPort {

  /**
   * 解析一份文档。
   *
   * @param content 文件字节
   * @param filename 原始文件名（★ 解析器靠扩展名与文件头判定格式，不能传 null 或去掉扩展名）
   * @return 解析结果；{@code ok = false} 时 {@code markdown} 可能是部分内容（解析到一半失败）
   */
  Result extract(byte[] content, String filename);

  /**
   * 解析结果。
   *
   * @param ok 是否成功
   * @param markdown 正文（失败时可能是部分结果，也可能是空串）
   * @param failReason 失败原因（成功时为 null）
   */
  record Result(boolean ok, String markdown, String failReason) {

    /** 成功。 */
    public static Result ok(String markdown) {
      return new Result(true, markdown == null ? "" : markdown, null);
    }

    /** 失败。 */
    public static Result failed(String failReason, String partial) {
      return new Result(false, partial == null ? "" : partial, failReason);
    }
  }
}
