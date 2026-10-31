package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.FileRef;
import cn.lincoln.aiplatform.conversation.domain.FileRefRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话附件的上传（{@code POST /files}，{@code 08 §4.9}）。
 *
 * <h3>★★ 顺序是硬约束：先存字节、后落记录</h3>
 *
 * <p>{@code 03} 机制八的前提：上传失败时库里<strong>不能</strong>留下指向空文件的记录。反过来（先落记录、后写盘）一旦写盘失败，
 * 消息上就会挂一个点开是空白的附件，而且没有任何一处会报错。所以这里的顺序是 {@code store(...)} → {@code
 * files.save(...)}，代价是写盘成功而落库失败时会留下一个孤儿文件 （孤儿可以扫出来删，悬空记录扫不出来 —— 只能靠用户投诉）。
 *
 * <h3>★ 今天的边界</h3>
 *
 * <p>上传完就结束了：返回的 id <strong>还没有任何代码消费</strong>（{@code ChatController} 收下 {@code attachmentIds}
 * 但不用）。 那一步（绑定 + 落 {@code kb_document} 让附件进检索）是下一批，见 {@code 13 §11.2–11.4.1}。
 */
@Service
public class FileService {

  /** 展示用文件名的长度上限：够放下中文长标题，又不至于让列表撑爆。 */
  private static final int MAX_NAME_LENGTH = 200;

  private final FileRefRepository files;
  private final FileStorePort store;
  private final long maxBytes;
  private final Clock clock;

  public FileService(
      FileRefRepository files,
      FileStorePort store,
      @Value("${app.files.max-size-bytes:20971520}") long maxBytes,
      Clock clock) {
    this.files = files;
    this.store = store;
    this.maxBytes = maxBytes;
    this.clock = clock;
  }

  /**
   * 存一份附件。
   *
   * @param originalName multipart 的原始文件名（可能带路径、可能是空）
   * @param contentType multipart 声明的 mime（可能为空 —— 浏览器对 {@code .md} 常给空）
   * @param content 字节；空数组按"空文件"拒
   * @param ownerId 上传者
   * @throws BusinessException 文件名缺失 / 内容为空 / 超过上限 —— 三种都是 400，且各自的 hint 里写着下一步能做什么
   */
  public FileView upload(String originalName, String contentType, byte[] content, UUID ownerId) {
    String name = displayName(originalName);
    if (content == null || content.length == 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文件内容为空（0 字节）", "请确认选中的不是占位文件或空文档；有些系统导出的空文件看着有大小，其实没有内容");
    }
    if (content.length > maxBytes) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "文件超过上限：" + content.length + " 字节，上限 " + maxBytes + " 字节",
          "压缩或拆分后再传；上限由 app.files.max-size-bytes 控制");
    }
    String storageKey = store.store(name, content);
    FileRef saved =
        files.save(
            new FileRef(
                UUID.randomUUID(),
                name,
                content.length,
                blankToNull(contentType),
                storageKey,
                ownerId,
                clock.instant()));
    return new FileView(saved.id(), saved.name(), saved.sizeBytes(), saved.mime());
  }

  /**
   * 从 multipart 的名字里取出<strong>能展示的那一段</strong>。
   *
   * <p>三轮清洗，每一轮都对应一种真实输入：
   *
   * <ol>
   *   <li><strong>按路径分隔符截断</strong>：有些客户端把 {@code C:\x\y\报告.pdf} 整个塞进 filename；
   *   <li><strong>去控制字符</strong>：换行与 {@code \0} 会被日志与响应头当成结构 —— 一个带换行的文件名能让一行日志裂成两行；
   *   <li><strong>截断到 200 字</strong>：防一条 1MB 的名字塞满列表。
   * </ol>
   *
   * <p>★ 清洗完还是空的 ⇒ 400。空名字存活下去的唯一后果，是用户上传 20 份文件后看到一排"未命名附件"。
   */
  private static String displayName(String originalName) {
    String raw = originalName == null ? "" : originalName;
    String base = raw.replace('\\', '/');
    int slash = base.lastIndexOf('/');
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    StringBuilder cleaned = new StringBuilder(base.length());
    for (int i = 0; i < base.length() && cleaned.length() < MAX_NAME_LENGTH; i++) {
      char ch = base.charAt(i);
      if (ch >= 0x20 && ch != 0x7F) {
        cleaned.append(ch);
      }
    }
    String name = cleaned.toString().trim();
    if (name.isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文件名缺失", "浏览器没有把 filename 传给服务端（少数客户端会这样）—— 换个浏览器再试，或先把文件重命名后重传");
    }
    return name;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
