package cn.lincoln.aiplatform.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分析服务的“没有数据时”口径。
 *
 * <p>★ 这条测试挡的是最容易被写坏的一类读数：SQL 聚合在没有行时返回 null，代码若直接拆箱就会 500； 或者为了“不为空”把 null 写成 0，界面就会把“没有数据”显示成“满意度
 * 0%”。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

  @Mock private JdbcTemplate jdbc;

  private AnalyticsService analytics;

  @BeforeEach
  void setUp() {
    analytics = new AnalyticsService(jdbc);
  }

  @Test
  void overviewWithoutDataReturnsZerosAndNullSatisfaction() {
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(), any()))
        .thenReturn(null);
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(java.math.BigDecimal.class), any(), any()))
        .thenReturn(null);

    var overview = analytics.overview(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));

    assertThat(overview.questionCount()).isZero();
    assertThat(overview.activeUserCount()).isZero();
    assertThat(overview.newUserCount()).isZero();
    assertThat(overview.tokenCount()).isZero();
    assertThat(overview.satisfaction()).isNull();
  }

  @Test
  void trendFillsDaysWithoutMessages() {
    // Mockito 对 List 返回值默认给空列表，不必再 stub 这个重载（stub 反而会撞上 queryForList 的重载歧义）
    var trend = analytics.trend(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));

    assertThat(trend)
        .extracting(AnalyticsService.TrendPoint::day)
        .containsExactly("2026-10-01", "2026-10-02", "2026-10-03");
    assertThat(trend)
        .allSatisfy(
            point -> {
              assertThat(point.questions()).isZero();
              assertThat(point.users()).isZero();
            });
  }

  @Test
  void qualityDoesNotFakeMetricsWithoutADataSource() {
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(java.math.BigDecimal.class), any(), any()))
        .thenReturn(null);
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(), any()))
        .thenReturn(null);

    var quality = analytics.quality(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));

    assertThat(quality.avgRefCount()).isZero();
    assertThat(quality.firstTokenLatencyP50()).isNull();
    assertThat(quality.artifactFailureRate()).isNull();
    assertThat(quality.pendingFeedbackCount()).isZero();
  }

  @Test
  void meSummaryWithoutActivityIsEmptyButNotNull() {
    // Mockito 对 Long/Timestamp 返回值默认就是 null；再按 4 参/5 参各 stub 一次会撞上
    // JdbcTemplate#queryForObject 的可变参数重载。
    var me = analytics.me(UUID.randomUUID(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));

    assertThat(me.myQuestionCount()).isZero();
    assertThat(me.myConversationCount()).isZero();
    assertThat(me.lastActiveAt()).isNull();
    assertThat(me.myUp()).isZero();
    assertThat(me.myDown()).isZero();
  }

  @Test
  void rangeRejectsReversedDates() {
    assertThatThrownBy(
            () -> analytics.overview(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("开始日期");
  }
}
