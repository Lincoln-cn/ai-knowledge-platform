package cn.lincoln.aiplatform.conversation.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话附件的仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ 读写各一条：{@link #save} 给上传那一批（{@code POST /api/v1/files} 落 {@code cv_file}），{@link #findById}
 * 给问答那一批（{@code #187} 判据 4：请求体里的 {@code attachmentIds} 要解析成字节 + 归属，才能登记成会话域文档）。
 *
 * <p>★ 仍然<strong>没有</strong>"按消息查附件"：消息与附件的关系这一批<u>没有落库</u>（只在问答那一轮即时消费）， 所以这里不预先加一个没人调的 {@code
 * findByMessageId} —— 那会被后来的读者当成"已经记下过每轮用了哪些附件"。
 */
public interface FileRefRepository {

  FileRef save(FileRef file);

  /**
   * 按 id 取一条附件元数据（含 {@code storage_key}，字节要再经 {@code FileStorePort#read} 读出来）。
   *
   * <p>★ 返回 {@link Optional} 而不是 null 或抛异常：调用方要区分"这个 id 不存在"与"这个 id 属于别人"，
   * 而那两种情况对外的答案<strong>必须一样</strong>（404，不泄漏存在性）。把这两种情况在这里揉成一个异常， 调用点就没法自己决定这条口径。
   */
  Optional<FileRef> findById(UUID id);
}
