package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 执行器单测的公共替身（测试源，包内可见）。
 *
 * <p>★ 为什么抽出来：四个多模态执行器都要"造一个端口 + 造一个文件存储"，而 `ObjectProvider`
 * 的匿名实现有六个方法要写。每个测试文件各写一份的结果是——改一个方法签名要改四处，而漏掉的那一处 只有在几个月后某个测试莫名全红时才会被发现。
 */
final class NodeExecutorTestSupport {

  private NodeExecutorTestSupport() {}

  /** 有 bean 的 provider。 */
  static <T> ObjectProvider<T> providerOf(T bean) {
    return new ObjectProvider<>() {
      @Override
      public T getObject(Object... args) {
        return bean;
      }

      @Override
      public T getIfAvailable() {
        return bean;
      }

      @Override
      public T getIfUnique() {
        return bean;
      }
    };
  }

  /** 没有 bean 的 provider（造"能力还没装配"）。 */
  static <T> ObjectProvider<T> absent() {
    return new ObjectProvider<>() {
      @Override
      public T getObject(Object... args) {
        throw new IllegalStateException("没有 bean");
      }

      @Override
      public T getIfAvailable() {
        return null;
      }

      @Override
      public T getIfUnique() {
        return null;
      }
    };
  }

  /**
   * 记录调用的文件存储替身。
   *
   * <p>写入的键形如 {@code <名字>#<序号>}，这样断言可以直接核对"写进去的名字带不带扩展名"， 而不是只能断言"调过一次"。
   */
  static final class RecordingFileStore implements FileStorePort {

    /** 键 → 字节。测试里可以预填，造"读得出来"的现场。 */
    final Map<String, byte[]> files = new LinkedHashMap<>();

    /** 每次 store 的入参，按调用顺序。 */
    final List<String> storedNames = new ArrayList<>();

    /** 每次 read 的入参，按调用顺序。 */
    final List<String> readKeys = new ArrayList<>();

    @Override
    public String store(String originalName, byte[] content) {
      storedNames.add(originalName);
      String key = originalName + "#" + storedNames.size();
      files.put(key, content);
      return key;
    }

    @Override
    public byte[] read(String key) {
      readKeys.add(key);
      byte[] content = files.get(key);
      if (content == null) {
        throw new IllegalStateException("没有这个键：" + key);
      }
      return content;
    }

    @Override
    public boolean delete(String key) {
      return files.remove(key) != null;
    }
  }
}
