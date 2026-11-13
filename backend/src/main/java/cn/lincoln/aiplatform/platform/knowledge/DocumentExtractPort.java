package cn.lincoln.aiplatform.platform.knowledge;

import java.util.Optional;
import java.util.UUID;

/**
 * 文档解析端口：把一份文件字节解析成 Markdown。
 *
 * <p>★ <strong>为什么要有这个端口</strong>：真正的解析实现（Tika + OCR + 十几种格式分支）在 {@code knowledge} 模块里， 而工作流的
 * {@code DOCUMENT_EXTRACT_NODE} 在 {@code assistant} 模块里。{@code ModuleBoundaryTest} 不允许 {@code
 * assistant} 直接依赖 {@code knowledge} 的实现类 —— 与 {@code #293}（{@code EntityRecallPort} 搬模块）
 * 同一条判据：<strong>跨模块要用的是「能力」，不是「那个类」</strong>。
 *
 * <p>★ <strong>返回值里没有「页数」「OCR 页数」</strong>：那些是入库时的统计口径（{@code kb_document} 的列），
 * 工作流节点只关心「解析出来的正文」与「失败了没有」。多加两个字段会让实现方被迫在解析阶段做入库阶段的事。
 *
 * <p>★ <strong>失败不抛异常</strong>：解析失败是一种正常结果（文件坏了 / 格式不支持），调用方要拿到 {@link Result#failReason()}
 * 才能把它写进节点记录。抛异常会让执行器把「文件坏了」与「系统坏了」当成同一件事。
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
   * 按文档 id 取库里<strong>已经解析好的正文</strong>。
   *
   * <p>★ 为什么不收字节：{@code kb_document} 里<strong>没有存原始字节</strong>（迁移 {@code
   * V14__knowledge_document_parsed_content.sql} 只加了 {@code parsed_content} 一列；字节只存在于会话附件那条链 {@code
   * cv_file.storage_key → FileStorePort}）。所以对「库里已有的文档」，今天能拿到的是解析结果，不是原件 ——
   * 端口按<strong>能拿到的东西</strong>定义，而不是按理想中该有的东西定义。等哪天原始字节入库了，再加一个 取字节的方法，不必改这个方法。
   *
   * <p>★ 三种结果，调用方要分得开：
   *
   * <ul>
   *   <li>{@link java.util.Optional#empty()} —— 文档不存在，或已被软删；
   *   <li>{@code markdown} 有内容 —— 正常，直接用；
   *   <li>{@code markdown} 为空 —— 文档在，但还没有解析结果（{@code status} 是 {@link
   *       DocumentText#status()}，调用方可以据此说清楚是在等解析还是解析失败）。
   * </ul>
   */
  Optional<DocumentText> textOf(UUID docId);

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

  /**
   * 库里一份文档的读取结果。
   *
   * @param filename 原始文件名（展示用，也是错误文案里指认"哪一份"的唯一线索）
   * @param markdown 已解析的正文；没解析过就是空串
   * @param status 文档当前状态（{@code DocumentStatus} 的名字）。★ 用字符串而不用枚举：这个端口在 {@code platform} 层，{@code
   *     DocumentStatus} 是 {@code knowledge} 模块内的类型，端口的目的是让调用方不必依赖那个模块。
   *     这里传名字是为了让「文档还在解析」与「解析失败了」在节点记录里说得出口。
   */
  record DocumentText(String filename, String markdown, String status) {}
}
