package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计的动作面口径（`13 §7.4` · `#258`）。
 *
 * <p>这一册反复出现的失效形状是「**声明与实现各写一份**」：§7.3 的表说接了七类，枚举里悄悄多一类， 或者检索链路悄悄接上审计而表里没有一个字。两侧都读起来像"已经做了"。
 *
 * <p>于是这里用两条**能红**的断言把两侧咬住：
 *
 * <ol>
 *   <li>枚举里的动作码必须与 §7.4 登记的那份清单**逐个相等** —— 多一个没登记的要红，登记过的消失了也要红；
 *   <li>检索 / 查看 / 引用这三类**读侧**实现类不许依赖 {@code AuditLogPort} —— 接上了就得先改 §7.4。
 * </ol>
 *
 * <p>★ 改动作面时，**两处一起改**：本测试的 {@link #CONNECTED} 与 `13 §7.4` 的表。只改一处就会红， 这是刻意的 ——
 * 上一批欠账（`#40`）正是"同一状态四处各写一份"。
 */
@DisplayName("审计动作面：枚举与 §7.4 的口径必须一致")
class AuditActionSurfaceTest {

  /** `13 §7.4` 登记的动作码。改这里必须同时改那一节的表。 */
  private static final List<String> CONNECTED =
      List.of(
          "document.delete",
          "document.reclassify",
          "document.relabel",
          "grant.change",
          "data.export",
          "conversation.share",
          "conversation.unshare",
          "audit.purge");

  /** 读侧实现类：它们一旦依赖 `AuditLogPort`，就说明读侧审计已经接了，而 §7.4 说的是没接。 */
  private static final List<String> READ_SIDE_CLASSES =
      List.of(
          "retrieval/application/RetrievalService.java",
          "retrieval/application/HitTestQuery.java",
          "conversation/application/ChatHistoryService.java");

  @Test
  @DisplayName("枚举里的动作码与 §7.4 登记的清单逐个相等")
  void theEnumMatchesTheDocumentedSurface() {
    Set<String> actual = new TreeSet<>();
    Arrays.stream(AuditAction.values()).map(AuditAction::code).forEach(actual::add);

    assertThat(actual)
        .as("★ 动作面改动必须同时改 13 §7.4 的表：多出来的码会让读报表的人以为某类动作已经在记了")
        .isEqualTo(new TreeSet<>(CONNECTED));
  }

  @Test
  @DisplayName("读侧（检索 / 查看 / 引用）还没有依赖审计端口")
  void theReadSideIsStillNotWiredToTheAuditPort() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (String relative : READ_SIDE_CLASSES) {
      Path file = Path.of("src", "main", "java", "cn", "lincoln", "aiplatform").resolve(relative);
      assertThat(Files.exists(file))
          .as("★ 口径扫描的对象不存在了：`%s` —— 改名/搬包之后这条断言会变成永远为真", relative)
          .isTrue();
      String source = Files.readString(file, StandardCharsets.UTF_8);
      if (source.contains("AuditLogPort")) {
        offenders.add(relative);
      }
    }

    assertThat(offenders).as("★ 读侧接了审计就必须改 13 §7.4（那节写的是「不记」，理由与触发条件都在里面）").isEmpty();
  }

  @Test
  @DisplayName("动作码是闭集：没有 SEARCH / VIEW / CITE 这类读侧码")
  void thereIsNoReadSideActionCodeYet() throws IOException {
    try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
      List<String> hits =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(
                  p -> {
                    try {
                      String s = Files.readString(p, StandardCharsets.UTF_8);
                      return s.contains("\"search\"")
                          || s.contains("\"view\"")
                          || s.contains("\"cite\"");
                    } catch (IOException e) {
                      return false;
                    }
                  })
              .map(Path::toString)
              .toList();
      assertThat(hits).as("★ 出现读侧动作码 ⇒ 先改 13 §7.4 的口径（它今天说的是「不记」，理由：量级与明文查询）").isEmpty();
    }
  }
}
