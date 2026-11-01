package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ 文件读写的边界（`#77` 判据 3）。
 *
 * <p>设计册 `13 §11.6.1` 定的缝：**附件与会话产物的字节只能过 `FileStorePort`**， 换对象存储时改一个实现，调用方零改动。今天这件事没有任何东西守着 ——
 * 有人在业务代码里 `Files.write(...)` 一行，编译、测试、闸门全都不会响， 而"以后换 OSS 要搜全仓"的代价要到真的换介质那天才一次性付出来。
 *
 * <p>这条断言只禁**写**：读（`Files.isDirectory` / `Files.isRegularFile`）是允许的 —— `OcrEngine` 用它探测 tesseract
 * 可执行文件与 tessdata 目录，那不是业务产物。 判据窄一点的好处是它不会被人当成"碍事"删掉。
 */
class FileIoBoundaryTest {

  /** 允许自己写文件的唯一实现：`FileStorePort` 的本地盘适配器。 */
  private static final Set<String> WRITE_ADAPTERS =
      Set.of("platform/infrastructure/LocalFileStore.java");

  private static final Pattern WRITE_CALL =
      Pattern.compile(
          "Files\\.(createDirectories|createTempDirectory|createTempFile|write|newOutputStream"
              + "|newBufferedWriter|copy|move|delete|deleteIfExists)\\s*\\(");

  @Test
  @DisplayName("★ 除适配器外不许自己写文件：附件与产物的字节只能过 FileStorePort（#77 判据 3）")
  void onlyTheAdapterTouchesTheFileSystemForWrites() throws IOException {
    Map<String, List<String>> offenders = new LinkedHashMap<>();
    Path root = mainSourceRoot();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (WRITE_ADAPTERS.contains(relative)) {
          continue;
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        Matcher matcher = WRITE_CALL.matcher(body);
        while (matcher.find()) {
          hits.add(matcher.group(1));
        }
        if (!hits.isEmpty()) {
          offenders.put(relative, hits);
        }
      }
    }
    assertThat(offenders)
        .as(
            "★ 业务代码自己去写文件了：%s —— 附件与产物必须走 FileStorePort（13 §11.6.1），" + "否则换对象存储时要逐个调用点排查",
            offenders)
        .isEmpty();
  }

  @Test
  @DisplayName("白名单里的适配器必须真的存在，而且必须真的在写 —— 否则这条边界是空转")
  void theWriteAdapterWhitelistStaysHonest() throws IOException {
    Path root = mainSourceRoot();
    for (String adapter : WRITE_ADAPTERS) {
      Path file = mainSourceRoot().resolve(adapter);
      assertThat(Files.exists(file)).as("★ 白名单里的 %s 不存在了 —— 删掉实现就删掉边界，请同步改这条断言", adapter).isTrue();
      String body = Files.readString(file, StandardCharsets.UTF_8);
      assertThat(WRITE_CALL.matcher(body).find())
          .as("★ 白名单里的 %s 已经不写文件了 —— 这时候它不该再挂在白名单上，" + "否则下一个真正自己写文件的类可以混进来", adapter)
          .isTrue();
    }
    assertThat(root.resolve("platform/domain/FileStorePort.java"))
        .as("★ 端口必须留在 platform（08 §4.9）：换介质只改适配器，调用方不认识磁盘")
        .exists();
  }

  private static Path mainSourceRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("src/main/java"))) {
      current = current.getParent();
    }
    assertThat(current).as("★ 找不到 src/main/java —— 这条测试必须能定位源码树，不能靠相对路径猜").isNotNull();
    return current.resolve("src/main/java/cn/lincoln/aiplatform");
  }
}
