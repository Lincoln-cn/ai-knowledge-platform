package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link SegmentLabelMismatchMetric} 的三件事：读数正确、失败不伪装成 0、Gauge 抓取时不抛异常。
 *
 * <p>★ 为什么单独测"失败记 -1"：这条读数的**唯一**作用就是让漂移可见。如果查询失败返回 0， 那么"库连不上"与"标签完全一致"在仪表盘上是同一个点 —— 这正是 `#228`
 * 那种把原因混掉的形状。
 */
class SegmentLabelMismatchMetricTest {

  /** 只覆写被用到的那一个方法：真实 SQL 的形状由集成测试（`DocumentLabelWriteTest`）验。 */
  private static JdbcTemplate jdbcReturning(List<Long> rows) {
    return new JdbcTemplate() {
      @Override
      public <T> List<T> queryForList(String sql, Class<T> elementType) {
        @SuppressWarnings("unchecked")
        List<T> casted = (List<T>) rows;
        return casted;
      }
    };
  }

  private static JdbcTemplate jdbcFailing(RuntimeException failure) {
    return new JdbcTemplate() {
      @Override
      public <T> List<T> queryForList(String sql, Class<T> elementType) {
        throw failure;
      }
    };
  }

  @Test
  @DisplayName("一致时读数为 0（0 的含义是「查过了、没问题」，不是「没查」）")
  void consistentLabelsReadAsZero() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SegmentLabelMismatchMetric metric =
        new SegmentLabelMismatchMetric(registry, jdbcReturning(List.of(0L)));

    assertThat(metric.countMismatchedSegments()).isZero();
    assertThat(registry.get(SegmentLabelMismatchMetric.NAME).gauge().value()).isZero();
  }

  @Test
  @DisplayName("有漂移时读数就是那个条数，并且能被抓取到")
  void mismatchedLabelsAreVisibleThroughTheGauge() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SegmentLabelMismatchMetric metric =
        new SegmentLabelMismatchMetric(registry, jdbcReturning(List.of(7L)));

    assertThat(metric.countMismatchedSegments()).isEqualTo(7L);
    assertThat(registry.get(SegmentLabelMismatchMetric.NAME).gauge().value()).isEqualTo(7.0);

    assertThat(registry.get(SegmentLabelMismatchMetric.NAME).gauge().getId().getDescription())
        .as("描述里要写清「应为 0」和「-1 表示查询没成功」，否则看板读者会把 -1 当成 0")
        .contains("应为 0")
        .contains("-1");
  }

  @Test
  @DisplayName("★ 查询失败记 -1 而不是 0 —— 否则「库连不上」与「完全一致」在仪表盘上是同一个点")
  void queryFailureIsRecordedAsMinusOneNotZero() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SegmentLabelMismatchMetric metric =
        new SegmentLabelMismatchMetric(
            registry, jdbcFailing(new DataAccessResourceFailureException("连接池打满（本测试故意制造）")));

    assertThat(metric.countMismatchedSegments()).isEqualTo(-1L);
    assertThat(registry.get(SegmentLabelMismatchMetric.NAME).gauge().value()).isEqualTo(-1.0);
  }

  @Test
  @DisplayName("空结果集也按 -1 处理：一条 count(*) 查询至少该返回一行，空行说明查询形状变了")
  void emptyResultIsTreatedAsFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SegmentLabelMismatchMetric metric =
        new SegmentLabelMismatchMetric(registry, jdbcReturning(List.of()));

    assertThat(metric.countMismatchedSegments()).isEqualTo(-1L);
  }
}
