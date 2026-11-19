package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Flyway 的版本号是<strong>整个 classpath 共享的一条序列</strong>，与它放在哪个子目录无关。
 *
 * <h3>为什么要有这条闸门</h3>
 *
 * <p>迁移按模块分目录（{@code db/migration/assistant}、{@code platform}、{@code conversation} …），这很容易让人以为
 * "每个目录各有各的号段"。实际不是：Flyway 把所有 location 合并成一个版本序列，两个目录各有一个 {@code V100} 会让<strong>整个应用起不来</strong>
 * —— 报错是
 *
 * <pre>
 * FlywayException: Found more than one migration with version 100
 * </pre>
 *
 * <p>2026-11-19 真实踩过：新增的两个 assistant 种子迁移取了 {@code V100} / {@code V101}，而 {@code platform} 与 {@code
 * conversation} 里早就有这两个号 ⇒ 后端启动直接失败，界面侧表现为"重启了但服务没起来"。 更麻烦的是这个 错误<strong>只在启动时出现一次</strong>（Flyway
 * 在 bean 初始化阶段就抛），看不到任何"某某页面坏了"的征兆。
 *
 * <h3>判据</h3>
 *
 * <ul>
 *   <li>版本号在整棵迁移树里<strong>唯一</strong>；
 *   <li>至少扫到一个迁移（防止路径写错导致"零个重复"的空绿）。
 * </ul>
 */
class FlywayMigrationVersionTest {

  private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

  /** {@code V106__xxx.sql} 里的 {@code 106}；只认这种三段式，别的文件名一律不参与。 */
  private static final Pattern VERSIONED_FILE = Pattern.compile("^V(\\d+)__.+[.]sql$");

  @Test
  void everyMigrationVersionIsUniqueAcrossAllModuleDirectories() {
    Map<String, List<String>> byVersion = new LinkedHashMap<>();
    int total = 0;

    try (Stream<Path> files = Files.walk(MIGRATION_ROOT)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        String name = file.getFileName().toString();
        Matcher matcher = VERSIONED_FILE.matcher(name);
        if (!matcher.matches()) {
          continue;
        }
        total++;
        // 版本号归一化：V100 与 V0100 在 Flyway 眼里是同一个版本，比较时去掉前导零
        String version = String.valueOf(Long.parseLong(matcher.group(1)));
        byVersion
            .computeIfAbsent(version, key -> new ArrayList<>())
            .add(MIGRATION_ROOT.relativize(file).toString().replace('\\', '/'));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("读不到迁移目录：" + MIGRATION_ROOT.toAbsolutePath(), exception);
    }

    assertThat(total).as("一个迁移都没扫到 ⇒ 这条闸门等于没跑（路径写错了？）").isGreaterThan(0);

    List<String> duplicated =
        byVersion.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> "V" + entry.getKey() + " ⇒ " + entry.getValue())
            .toList();

    assertThat(duplicated)
        .as(
            "Flyway 版本号跨目录共享，重复会让应用直接起不来"
                + "（Found more than one migration with version …）；"
                + "取号前请扫<strong>全部</strong>模块目录，不要只看自己那个")
        .isEmpty();
  }
}
