package cn.lincoln.aiplatform.platform.knowledge;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;

/**
 * 会话附件的登记端口（跨模块 SPI）—— 接口放 {@code platform}、实现放 {@code knowledge}。
 *
 * <h3>★★ 它为什么必须存在（而不是让 {@code conversation} 直接建 {@code kb_document}）</h3>
 *
 * <p>{@code knowledge} 是 {@code CLOSED} 模块，{@code kb_document} 是它的内部形状；而 {@code conversation} 的模块声明
 * （{@code package-info.java}）里<strong>没有</strong> {@code knowledge} 这一项 —— 那是刻意的（{@code 01 §三.3}：
 * 问答只该认识检索返回值）。所以"把附件落成一条会话域文档"这件事必须走一条<strong>窄端口</strong>， 与 {@code
 * platform.assistant.AppBindingPort} / {@code platform.retrieval.SegmentSearchPort} 同族。
 *
 * <h3>★ 端口面为什么是这一组参数（每一个都有来源，没有一个可以省）</h3>
 *
 * <table>
 *   <tr><th>参数</th><th>为什么必须是调用方给</th></tr>
 *   <tr><td>{@code conversationId}</td><td>{@code 13 §11.2} 第三域的 {@code scope_id}；只由问答那一轮决定</td></tr>
 *   <tr><td>{@code ownerId}</td><td>检索谓词第三支要求 {@code owner_id = me} ⇒ 它必须是<strong>上传人</strong>，不是"建档人"</td></tr>
 *   <tr><td>{@code attachmentId}</td><td>{@code cv_file.id} —— 幂等键与追溯用（同一个附件不重复建档）</td></tr>
 *   <tr><td>{@code displayName}</td><td>用户看到的文件名；它会进分段前缀与引用标题</td></tr>
 *   <tr><td>{@code content}</td><td>★ 字节由调用方读出来（{@code FileStorePort#read}）：knowledge 不认识 {@code cv_file} 与文件存储</td></tr>
 *   <tr><td>{@code classification}</td><td>★ {@code 13 §11.5}「附件密级不许低于会话当前密级」—— 会话当前值只有 conversation 那侧知道</td></tr>
 * </table>
 *
 * <h3>★★ 它<strong>同步</strong>解析，这是有意的</h3>
 *
 * <p>返回时该文档的 {@code kb_segment} 已经落库。理由：本端口在"这一轮问答的检索之前"被调用， 如果解析是异步的，第一次问"附件里写了什么"必然检索不到 ——
 * 而那个空结果看起来与"附件里确实没有"一模一样 （{@code silent-failure} 的形状）。代价是大文件会推迟首字延迟，这笔账记在 {@code #187} 的报告里，
 * 不在端口面上（将来改成"上传即异步解析"时，端口签名不变，只是实现换了时机）。
 */
public interface ConversationAttachmentPort {

  /**
   * 把一个已上传的会话附件登记为该会话下的一条 {@code scope_kind='CONVERSATION'} 文档，并解析出可检索的分段。
   *
   * <p>★ 幂等：同一 {@code (conversationId, attachmentId)} 再调一次返回<strong>同一条</strong>文档 id，不重复建档、
   * 不重复解析。问答可以反复问同一批附件，那不该让引用列表里出现两份一模一样的来源。
   *
   * @param conversationId 会话 id（≥1 个字符的域键，不允许为空）
   * @param ownerId 上传人（检索谓词第三支的 {@code owner_id}）
   * @param attachmentId {@code cv_file.id}
   * @param displayName 展示名（原始文件名）
   * @param content 文件字节（调用方已确认非空）
   * @param classification 密级（★ 调用方按 {@code 13 §11.5} 取的会话当前密级）
   * @return 文档 id
   */
  UUID register(
      UUID conversationId,
      UUID ownerId,
      UUID attachmentId,
      String displayName,
      byte[] content,
      ClassificationLevel classification);

  /**
   * 彻底删除一个会话下的<strong>全部</strong>会话域文档行（{@code kb_document} + {@code kb_segment} +
   * 向量），并报告哪些上传文件因此不再被引用（{@code #299}）。
   *
   * <p>★ 为什么删库内行要走本端口、删文件却不走：{@code kb_document} 是 {@code knowledge}（{@code CLOSED}）的内部形状，而 {@code
   * cv_file} 与磁盘字节归 {@code conversation} —— 两侧各守自己的表，引用计数在 {@code kb_document} 所有者这边判（删完行之后数），
   * 文件删除由调用方拿 {@link #orphanedAttachmentIds()} 去调 {@code ConversationFilePurgePort}。
   *
   * <p>★ 分段与向量<strong>物理删</strong>：{@code 02 §5.1.1} 行 587 的口径（彻底删除必须物理删分段与向量）—— 分段表的 {@code
   * content} 就是正文摘录，留着的话"彻底删除"只删了个标题。
   *
   * @param conversationId 会话 id
   * @return 清理读数（文档/分段/向量各删了几行）+ 不再被任何文档行引用的 {@code cv_file.id} 列表
   */
  PurgeResult purgeConversationAttachments(UUID conversationId);

  /**
   * {@link #purgeConversationAttachments} 的读数（{@code #299} 判据 1 的按表逐项断言与审计摘要都读它）。
   *
   * @param documents 删掉的 {@code kb_document} 行数
   * @param segments 删掉的 {@code kb_segment} 行数
   * @param vectors 删掉的向量行数（会话域只走全文一路时恒 0 —— 删前删后都是 0，如实报）
   * @param orphanedAttachmentIds 彻底删除后已无任何文档行引用的上传文件（{@code cv_file.id}）—— 只有这些才允许删磁盘字节
   */
  record PurgeResult(
      int documents, int segments, int vectors, java.util.List<UUID> orphanedAttachmentIds) {}
}
