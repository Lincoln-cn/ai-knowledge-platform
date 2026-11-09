package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 触发器「留缝」的反向守卫（{@code #295} 的一半）。
 *
 * <h3>★ 为什么留缝也需要一条能红的断言</h3>
 *
 * <p>{@code V88__assistant_trigger_slots.sql} 给 {@code as_assistant} 加了两列 {@code trigger_type} /
 * {@code trigger_cron}，今天<strong>没有任何写入方，也没有任何读取方</strong>。
 *
 * <p>留缝最危险的失败形状不是"缝留错了"，而是半年后有人翻到这两列就以为"已经支持定时触发了"， 于是在别处按"应用会自己跑"来写代码 —— 而实际上一轮问答永远不会被自动发起。
 *
 * <p>所以这一条断的是<strong>否定命题</strong>：整个主代码里<b>没有</b>调度器。 等真要实现时（V1.0），这条会红 —— 那时的正确处理是删掉它并在 {@code
 * ROADMAP} 里销账， 不是把它改成"允许有调度器"而留在这里。
 *
 * <h3>为什么不给这两列配一个端口</h3>
 *
 * <p>我原先的计划里有「{@code TriggerPort} 空实现」，写的时候否掉了：今天<strong>一个调用点都没有</strong>，
 * 造一个没人调用的接口不是留缝，是负债（Sonar 与架构闸门都会把它算成死代码）。 真正"晚做要返工"的东西是<strong>数据形状</strong>，那已经用迁移解决了。
 */
class TriggerSlotTest {

  /** 主代码根目录（surefire 的 cwd 是 backend/） */
  private static final Path MAIN = Path.of("src", "main", "java");

  private static List<Path> javaFiles() {
    try (Stream<Path> walk = Files.walk(MAIN)) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> filesContaining(String needle) {
    return javaFiles().stream()
        .filter(
            p -> {
              try {
                return Files.readString(p, StandardCharsets.UTF_8).contains(needle);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            })
        .map(p -> MAIN.relativize(p).toString())
        .toList();
  }

  /**
   * 找出**真的写了**这些注解的文件（不是注释里提到它们）。
   *
   * <p>★ 第一版按 {@code contains("@Scheduled")} 找，红灯打在 {@code RetentionDryRunService} 上 —— 那个文件的
   * javadoc 里写着「{@code @Scheduled} 会按它删…」，说的正是<strong>没有</strong>这个注解。 判据必须是"注解在代码里"，所以这里逐行按 {@code
   * ^\s*@名字} 匹配。
   */
  private static List<String> filesDeclaring(String annotation) {
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile(
            "^\\s*@" + java.util.regex.Pattern.quote(annotation) + "\\b");
    return javaFiles().stream()
        .filter(
            p -> {
              try {
                return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> pattern.matcher(line).find());
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            })
        .map(p -> MAIN.relativize(p).toString())
        .toList();
  }

  @Test
  @DisplayName("没有任何调度器在碰这两个触发列 —— 应用仍然只能由人点")
  void noSchedulerConsumesTheTriggerSlots() {
    // ★★ 这一条的第一版断的是"整个主代码里没有 @Scheduled"，跑出来是红的 —— 打在
    //   `knowledge/application/DeletedDocumentCleanupSchedule.java` 上。
    //   那个调度是**合法的、早就有的**（清理已删除文档），说明"项目没有调度基础设施"这个前提是错的。
    //
    //   真正该断的命题要窄：**没有一个调度器去读 / 写 trigger_type / trigger_cron**。
    //   调度基础设施在不在无关紧要，关键是"有没有东西会自己发起一轮问答"。
    List<String> schedulers = filesDeclaring("Scheduled");
    assertThat(schedulers)
        .as("项目里本来就有调度器（DeletedDocumentCleanupSchedule，清理已删文档）—— 若这条突然变空，先确认它是否被误删")
        .isNotEmpty();

    List<String> schedulerTouchingTriggers =
        schedulers.stream().filter(f -> fileMentions(f, "trigger")).toList();
    assertThat(schedulerTouchingTriggers)
        .as("有调度器开始读 / 写 trigger 列 ⇒ 定时触发真的动工了。若是 V1.0，删掉本条并更新 ROADMAP §11.7")
        .isEmpty();
  }

  /** 该文件里是否出现过 {@code needle}（用来判"这个调度器有没有碰触发器"） */
  private static boolean fileMentions(String relative, String needle) {
    try {
      return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8).contains(needle);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("触发相关的两列还没有任何读写方（留缝 ≠ 已接入）")
  void theTriggerColumnsHaveNoReaderOrWriter() {
    // ★ 只允许出现在迁移 SQL 里（建列）与这条测试自己里。
    //   一旦主代码里出现 "trigger_type" / "trigger_cron" / "triggerType" / "triggerCron"，
    //   说明有人把它接进去了 —— 那一步必须同时把本条与 TriggerSlotTest 的说明一起改。
    assertThat(filesContaining("trigger_type")).as("主代码读了 trigger_type").isEmpty();
    assertThat(filesContaining("trigger_cron")).as("主代码读了 trigger_cron").isEmpty();
    assertThat(filesContaining("triggerType")).as("主代码出现了 triggerType 字段").isEmpty();
    assertThat(filesContaining("triggerCron")).as("主代码出现了 triggerCron 字段").isEmpty();
  }
}
