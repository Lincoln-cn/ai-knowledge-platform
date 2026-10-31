package cn.lincoln.aiplatform.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 保留期口径：两处消费者（回收站列表/恢复 与 清理作业）共用一个类，边界必须钉死。
 *
 * <p>★ 断言的重点在**边界**：恰好等于截止时刻算过期。这条一改，列表与恢复就会开始各说各的。
 */
class RetentionWindowTest {

  private static final Instant NOW = Instant.parse("2026-10-31T04:00:00Z");

  @Test
  void cutoffIsNowMinusTheWindow() {
    assertThat(RetentionWindow.cutoff(NOW, 7)).isEqualTo(Instant.parse("2026-10-24T04:00:00Z"));
  }

  @Test
  void nonPositiveWindowMeansExpiredRightNow() {
    assertThat(RetentionWindow.cutoff(NOW, 0)).isEqualTo(NOW);
    assertThat(RetentionWindow.cutoff(NOW, -3)).isEqualTo(NOW);
    assertThat(RetentionWindow.within(NOW, NOW, 0)).as("★ 0 天 ⇒ 刚删的也已经过期（调用方要拦）").isFalse();
  }

  @Test
  void insideTheWindowIsRestorable() {
    assertThat(RetentionWindow.within(Instant.parse("2026-10-28T04:00:00Z"), NOW, 7)).isTrue();
  }

  @Test
  void exactlyAtTheCutoffIsAlreadyExpired() {
    assertThat(RetentionWindow.within(Instant.parse("2026-10-24T04:00:00Z"), NOW, 7))
        .as("★ 边界：恰好等于截止时刻算过期 —— 两处消费者共用这一条")
        .isFalse();
  }

  @Test
  void nullDeletedAtIsNotWithinTheWindow() {
    assertThat(RetentionWindow.within(null, NOW, 7)).isFalse();
  }
}
