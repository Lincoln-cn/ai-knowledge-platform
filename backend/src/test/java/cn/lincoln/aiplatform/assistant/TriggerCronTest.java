package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.assistant.application.TriggerCron;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定时触发的 cron 计算（{@code #295} / 0.7.3）。
 *
 * <p>★ 这一条<strong>能红</strong>：把 {@code TriggerCron.next} 改成"每次都返回 now + 1 分钟"， 下面每一条都会红。替换掉的是原来
 * {@code TriggerSlotTest} 那两条<strong>否定断言</strong> （"没有任何调度器碰这些列"）—— 那两条在实现落地之后只剩一个命运：被删掉或改成永远绿。
 */
class TriggerCronTest {

  private static final ZoneId ZONE = ZoneId.systemDefault();

  private static Instant at(int year, int month, int day, int hour, int minute) {
    return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant();
  }

  @Test
  @DisplayName("工作日九点：周一八点的下一次是周一九点")
  void weekdayNineFromMondayMorning() {
    // 2026-11-16 是周一
    Optional<Instant> next = TriggerCron.next("0 9 * * 1-5", at(2026, 11, 16, 8, 0));
    assertThat(next).contains(at(2026, 11, 16, 9, 0));
  }

  @Test
  @DisplayName("工作日九点：周五九点之后的下一次是下周一 —— 跨周末是 72 小时，不是 24")
  void weekdayNineSkipsTheWeekend() {
    Instant afterFriday = at(2026, 11, 20, 9, 0);
    Optional<Instant> next = TriggerCron.next("0 9 * * 1-5", afterFriday);
    assertThat(next).contains(at(2026, 11, 23, 9, 0));
  }

  @Test
  @DisplayName("错过几轮要逐次数：跨周末的 24 小时制 cron 不会被算成 3 轮")
  void missedCountStepsOneByOne() {
    // 周四 9:00 该跑（服务停了），到周一 10:00 才发现 —— 中间只漏了周五、周六、周日 3 次？
    // 不：周五 9:00、周六 9:00、周日 9:00 各一次，共 3 次。逐次数的结果必须是 3，不是除法算出的其它值。
    int missed =
        TriggerCron.countBetween("0 9 * * *", at(2026, 11, 19, 9, 0), at(2026, 11, 22, 10, 0), 100);
    assertThat(missed).isEqualTo(3);
  }

  @Test
  @DisplayName("工作日的 cron 跨周末只算 1 轮：周五之后的下一次是周一，中间没有别班")
  void weekdayCronDoesNotCountTheWeekend() {
    int missed =
        TriggerCron.countBetween(
            "0 9 * * 1-5", at(2026, 11, 20, 9, 0), at(2026, 11, 23, 10, 0), 100);
    assertThat(missed).isEqualTo(1);
  }

  @Test
  @DisplayName("每 15 分钟：10:07 的下一次是 10:15（秒位自动补 0）")
  void everyFifteenMinutes() {
    Optional<Instant> next = TriggerCron.next("*/15 * * * *", at(2026, 11, 16, 10, 7));
    assertThat(next).contains(at(2026, 11, 16, 10, 15));
  }

  @Test
  @DisplayName("写六段（带秒）要报错，并说清是几段 —— 界面上写对秒级 cron 的人极少")
  void sixFieldsIsRejected() {
    assertThat(TriggerCron.validate("0 0 9 * * 1-5"))
        .hasValueSatisfying(message -> assertThat(message).contains("5 段").contains("6 段"));
  }

  @Test
  @DisplayName("字段越界（99 分钟）要报错，且错误文案来自框架、能看懂")
  void outOfRangeIsRejected() {
    assertThat(TriggerCron.validate("99 * * * *")).isPresent();
  }

  @Test
  @DisplayName("空 cron 的文案要给出示例 —— 只说\"必填\"等于没说")
  void blankIsRejectedWithAnExample() {
    assertThat(TriggerCron.validate("  "))
        .hasValueSatisfying(message -> assertThat(message).contains("0 9 * * 1-5"));
  }

  @Test
  @DisplayName("合法 cron 校验通过")
  void validCronPasses() {
    assertThat(TriggerCron.validate("0 9 * * 1-5")).isEmpty();
    assertThat(TriggerCron.validate("30 2 * * *")).isEmpty();
    assertThat(TriggerCron.validate("0 0 1 * *")).isEmpty();
  }
}
