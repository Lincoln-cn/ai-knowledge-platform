package cn.lincoln.aiplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 配额「缝」的现状断言（{@code #73} 判据 ④）。
 *
 * <p>★ 这一条测试要回答的问题只有一个：<b>今天的行为是不拦截</b>。 它用"主代码里这三列一个都没被读" 来回答，而不是用一句注释来回答 —— 因为注释不会红，断言会。
 *
 * <p>★ 它红起来的时候是<b>好事</b>：说明有人真的接线了。那一刻必须同时做三件事： ① 改本类（把刚接线的列移进"已接线"清单）；② 改 {@code 08 §4.10.3}；③ 在
 * {@code README}/{@code deploy/README.md} 的指标与告警一节点明"超限时到底发生什么"（拒绝？降级？还是只告警）。 不做的后果是"留了缝"被读成"已经做了"
 * —— 本项目反复栽在这上面。
 *
 * <p>★ 为什么扫源码而不是查数据库元数据：查 DB 需要一个跑起来的 PG（慢、且本机常常没有）， 而这里要验的是"代码有没有动它"，不是"列建出来没有"。列的存不存在由 Flyway
 * 迁移文件本身证明 （同一条测试的第二段）。
 */
class QuotaSeamTest {

  /** 主代码里出现的这些名字，任意一个命中都说明"缝开始被消费了"。 */
  private static final List<String> SEAM_COLUMNS =
      List.of("storage_quota_bytes", "storageQuotaBytes", "daily_call_quota", "dailyCallQuota");

  /** 用量列单独列：它们允许被"写"（记账）与"读"（展示），但今天也不许有任何代码引用。 */
  private static final List<String> USAGE_COLUMNS =
      List.of("storage_used_bytes", "storageUsedBytes", "calls_today", "callsToday");

  private static final Path MAIN_JAVA = Path.of("src", "main", "java");

  private static final Path MIGRATION =
      Path.of(
          "src",
          "main",
          "resources",
          "db",
          "migration",
          "platform",
          "V54__platform_quota_seams.sql");

  @Test
  void noProductionCodeConsumesTheQuotaColumnsYet() throws IOException {
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String column : SEAM_COLUMNS) {
          if (source.contains(column)) {
            offenders.add(file + " → " + column);
          }
        }
        for (String column : USAGE_COLUMNS) {
          if (source.contains(column)) {
            offenders.add(file + " → " + column);
          }
        }
      }
    }

    assertThat(offenders)
        .as(
            "★ 今天的行为是「不拦截」：配额/用量列只存在于迁移里，主代码一处都不许引用。"
                + "若这里红了，说明有人开始接线 —— 请按类注释的三件事同步更新，并删掉本断言里对应的列")
        .isEmpty();
  }

  @Test
  void theSeamItselfIsRealSoTheAssertionAboveIsNotVacuous() throws IOException {
    // ★ 反证的另一半：上面那条"没有代码引用"只有在"列真的存在"时才有意义。
    //   如果迁移被删/列名改错，上面那条会因为"扫不到"而永远绿 —— 那是一条假绿。
    assertThat(MIGRATION).as("配额缝的迁移文件必须在").exists();

    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
    assertThat(sql)
        .as("知识库存储配额 + 用量、模型每日调用配额 + 用量，四列都要在迁移里")
        .contains("storage_quota_bytes")
        .contains("storage_used_bytes")
        .contains("daily_call_quota")
        .contains("calls_today");
    assertThat(sql)
        .as("★ NULL = 不限；不许用 0 表示不限（0 的自然读法是「一个都不许」，而零额度是真实配置）")
        .doesNotContain("default 0 check");
  }
}
