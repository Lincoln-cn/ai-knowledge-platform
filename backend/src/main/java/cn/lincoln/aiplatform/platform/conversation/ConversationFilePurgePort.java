package cn.lincoln.aiplatform.platform.conversation;

import java.util.UUID;

/**
 * 会话附件<strong>文件</strong>的彻底删除端口（{@code #299} · 跨模块 SPI）—— 接口放 {@code platform}、实现放 {@code
 * conversation}。
 *
 * <h3>★ 它为什么存在（knowledge 要删 conversation 的东西）</h3>
 *
 * <p>附件的"彻底删除"是三样东西：{@code kb_document} 会话域行 + 分段/向量（{@code knowledge} 的内部形状）与 {@code cv_file} 行 +
 * 磁盘字节（{@code conversation} 与 {@code FileStorePort} 的地盘）。 单篇附件文档走 {@code DELETE
 * /api/v1/documents/{id}/purge} 时，{@code DocumentService} 住在 {@code knowledge}（{@code CLOSED} 模块），
 * 不认识 {@code cv_file} —— 所以"删完库内行之后把字节也删掉"必须走一条窄端口， 与 {@link
 * cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort} 反向同族。
 *
 * <h3>★ 调用方的义务：引用计数</h3>
 *
 * <p>同一个上传文件可以被多个会话引用（同一 {@code cv_file} 跨会话复用）。调用方（{@code knowledge} 侧）必须在 {@code
 * countByAttachmentId == 0} 时才调本端口 —— 先删字节会让另一个会话的附件变成"有行无字节"的半截数据。 计数放在 {@code kb_document}
 * 所有者那一侧判，删除放在 {@code cv_file} 所有者这一侧执行，两侧各守自己的表。
 *
 * <h3>★ 幂等</h3>
 *
 * <p>{@code cv_file} 行不在、或磁盘字节不在，都算成功（与 {@code FileStorePort#delete} 同口径）：
 * 彻底删除要跑在"可能已经残缺"的现场上（上次清理半途失败、 人工挪走过文件），重跑不许变成 500。
 */
public interface ConversationFilePurgePort {

  /**
   * 删掉一个上传文件的<strong>全部存在</strong>：{@code cv_file} 行 + 磁盘字节。
   *
   * @param attachmentId {@code cv_file.id}（{@code kb_document.attachment_id} 追溯链的一环）
   * @return true = 调用后它已不在（删掉了或本来就没有）
   * @throws IllegalStateException 删除动作本身失败（磁盘只读、权限）—— 不吞：吞了就是"彻底删除"在介质层撒谎
   */
  boolean purgeAttachmentFile(UUID attachmentId);
}
