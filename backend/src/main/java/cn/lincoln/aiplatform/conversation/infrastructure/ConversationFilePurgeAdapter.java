package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.FileRef;
import cn.lincoln.aiplatform.conversation.domain.FileRefRepository;
import cn.lincoln.aiplatform.platform.conversation.ConversationFilePurgePort;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link ConversationFilePurgePort} 的实现（`#299` 附件彻底删除）：删 {@code cv_file} 行 + 磁盘字节。
 *
 * <p>★ 为什么要有这个端口：{@code kb_document} 与 {@code cv_file} 分属两个模块 —— 知识模块看得见文档行、看不见上传文件； 上传文件（{@code
 * cv_file} + {@code backend/data/files/}）是 {@code conversation} 模块的事实。
 * 彻底删除要跨过这条缝，就只能"知识侧判、会话侧删"（调用方义务见端口 javadoc：{@code countByAttachmentId == 0} 之后才许调）。
 *
 * <p>★ 幂等：文件或行本就不在 ⇒ 直接算成功（重复彻底删除、半截清理后的重放都不该报错）。 删除顺序是
 * <strong>先字节后行</strong>：反过来的话，行删掉、字节删除失败，就再也没有 {@code storage_key} 能找到那个文件 —— 磁盘上的孤儿永远清不掉。
 */
@Service
public class ConversationFilePurgeAdapter implements ConversationFilePurgePort {

  private static final Logger log = LoggerFactory.getLogger(ConversationFilePurgeAdapter.class);

  private final FileRefRepository files;
  private final FileStorePort store;

  public ConversationFilePurgeAdapter(FileRefRepository files, FileStorePort store) {
    this.files = files;
    this.store = store;
  }

  @Override
  public boolean purgeAttachmentFile(UUID attachmentId) {
    if (attachmentId == null) {
      return false;
    }
    Optional<FileRef> found = files.findById(attachmentId);
    if (found.isEmpty()) {
      // ★ 行不在：字节无从定位（键只记在行里）—— 幂等成功，如实记一条
      log.info("附件 {} 的 cv_file 行本就不在（幂等成功）", attachmentId);
      return true;
    }
    FileRef file = found.get();
    store.delete(file.storageKey());
    files.delete(file);
    log.info("附件 {} 已彻底删除：cv_file 行 + 磁盘字节（键 {}）", attachmentId, file.storageKey());
    return true;
  }
}
