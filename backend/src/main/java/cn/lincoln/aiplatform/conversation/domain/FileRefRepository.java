package cn.lincoln.aiplatform.conversation.domain;

/**
 * 会话附件的仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ 只有 {@link #save}：今天没有任何读路径（见 {@link FileRef} 的"诚实边界"）。按 id 查、按消息查都等到绑定那一批再加 —— 一个没人调的 {@code
 * findById} 会被后来的读者当成"已经有人按 id 取过文件了"。
 */
public interface FileRefRepository {

  FileRef save(FileRef file);
}
