package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件字节落在本地磁盘（{@code 08 §4.9} 的起步实现）。
 *
 * <h3>键的形状</h3>
 *
 * <p>{@code <32 位十六进制 uuid>} 加上**从原始名里取出来的扩展名**。扩展名留着是为了运维能 `file` 一下认出这是什么， 而 uuid
 * 保证"两个用户传同名文件"不会互相覆盖 —— 用原始名做键就会：后传的静默顶掉先传的，两条消息指向同一份字节。
 *
 * <h3>为什么不建日期子目录</h3>
 *
 * <p>日期子目录解决的是"一个目录几十万文件"的问题，而那个问题的解法取决于将来换的对象存储（键前缀规则完全不同）。 现在建一层 {@code 2026/11/} 就等于先猜一遍将来的键规则
 * —— 换实现时要迁移存量文件的路径。
 *
 * <h3>★ 失败一律抛</h3>
 *
 * <p>磁盘满、目录只读、无权限：这些都不该被吞成"上传成功"。吞了以后用户发一条指向空气的消息， 而日志里只有一行 debug —— {@code silent-failure} 的标准形状。
 */
public class LocalFileStore implements FileStorePort {

  private static final Logger log = LoggerFactory.getLogger(LocalFileStore.class);

  /** 扩展名最长认 8 个字符：再长基本不是扩展名，而是名字里带的点。 */
  private static final int MAX_EXTENSION_LENGTH = 8;

  private final Path root;

  public LocalFileStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /**
   * 把键解析成一个<strong>落在根目录之内</strong>的绝对路径。
   *
   * <p>★ 键是 {@link #store} 自己生成的，理论上是安全的；但 {@code read} 的键来自数据库（{@code cv_file.storage_key}），
   * 而数据库里的值可能被人改过 —— 所以<strong>读的那一侧照样要校验</strong>。一条 {@code ../../etc/passwd} 的存量行在这里会被拒绝，
   * 而不是把宿主机的文件读进检索。
   */
  private Path resolveInsideRoot(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("存储键为空，拒绝读取");
    }
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IllegalStateException("存储键解析后落在根目录之外，拒绝读取：" + key);
    }
    return target;
  }

  @Override
  public String store(String originalName, byte[] content) {
    String key = UUID.randomUUID().toString().replace("-", "") + extensionOf(originalName);
    Path target = resolveInsideRoot(key);
    try {
      Files.createDirectories(root);
      Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException cause) {
      throw new IllegalStateException("文件写入失败（根目录 " + root + "，键 " + key + "）", cause);
    }
    log.info("附件已落盘：键 {}，{} 字节", key, content.length);
    return key;
  }

  @Override
  public byte[] read(String key) {
    Path target = resolveInsideRoot(key);
    try {
      return Files.readAllBytes(target);
    } catch (IOException cause) {
      // ★ 不返回空数组：那会让"文件不在了"与"文件是空的"看起来一样（见 FileStorePort#read 的注释）
      throw new IllegalStateException(
          "文件读取失败（根目录 " + root + "，键 " + key + "，是否存在 " + Files.exists(target) + "）", cause);
    }
  }

  @Override
  public boolean delete(String key) {
    Path target = resolveInsideRoot(key);
    try {
      boolean removed = Files.deleteIfExists(target);
      log.info("附件字节删除：键 {}，{}", key, removed ? "已删掉" : "本就不在（幂等成功）");
      return true;
    } catch (IOException cause) {
      throw new IllegalStateException("文件删除失败（根目录 " + root + "，键 " + key + "）", cause);
    }
  }

  /**
   * 从原始文件名里取扩展名（含点，小写）。
   *
   * <p>★ 只看最后一段：{@code a.b/c.pdf} 这种带路径的名字要先按路径分隔符截断，否则 {@code c.pdf} 会被当成扩展名的一部分。 ★
   * 读不出合法扩展名就返回空串：键里没有扩展名不影响任何功能，而"猜一个"会在运维看到 {@code .undefined} 时更迷惑。
   */
  private static String extensionOf(String originalName) {
    if (originalName == null) {
      return "";
    }
    String base = originalName.replace('\\', '/');
    int slash = base.lastIndexOf('/');
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    int dot = base.lastIndexOf('.');
    if (dot < 0 || dot == base.length() - 1) {
      return "";
    }
    String ext = base.substring(dot + 1);
    if (ext.length() > MAX_EXTENSION_LENGTH) {
      return "";
    }
    for (int i = 0; i < ext.length(); i++) {
      char ch = ext.charAt(i);
      boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
      if (!ok) {
        return "";
      }
    }
    return "." + ext.toLowerCase(Locale.ROOT);
  }
}
