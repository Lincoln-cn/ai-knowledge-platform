package cn.lincoln.aiplatform.platform.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 三条业务指标的<b>记账口径</b>单测（{@code #73}）。
 *
 * <p>★ 这里验的不是"有没有被 Spring 装配"（那是 {@code BusinessMetricsEndpointTest} 的事），
 * 而是"记了几笔、记在哪条序列上"。两件事分开验，才不会用一个假的空 registry 把两件都验成"通过"。
 */
class BusinessMetricsTest {

  /** 标签里<b>不许出现</b>的维度：都是高基数或涉账号的字段（#73 口径）。 */
  private static final List<String> FORBIDDEN_TAG_KEYS =
      List.of(
          "app",
          "application",
          "user",
          "user_id",
          "session",
          "session_id",
          "model",
          "model_id",
          "kb");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final BusinessMetrics metrics = new BusinessMetrics(registry);

  @Test
  void upstreamOutcomesBecomeTwoSeries() {
    metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
    metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
    metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);

    // ★ 成功与失败必须落在两条序列上：合成一条就算不出"不可达率"，只能算次数。
    assertThat(counter("ok")).isEqualTo(2.0);
    assertThat(counter("unreachable")).isEqualTo(1.0);
  }

  @Test
  void firstTokenIsRecordedAsOneSample() {
    metrics.firstToken(Duration.ofMillis(1200).toNanos());

    assertThat(registry.get(BusinessMetrics.CHAT_FIRST_TOKEN).timer().count()).isEqualTo(1L);
    assertThat(
            registry
                .get(BusinessMetrics.CHAT_FIRST_TOKEN)
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        .isEqualTo(1200.0);
  }

  @Test
  void negativeFirstTokenIsIgnoredRatherThanRecordedAsZero() {
    // ★ 0 会进分位数主力的位置（一堆 0 会把 P95 拉低），"没测到"必须是"不记"，不是"记 0"。
    metrics.firstToken(-1L);

    assertThat(registry.get(BusinessMetrics.CHAT_FIRST_TOKEN).timer().count()).isZero();
  }

  @Test
  void ingestionFailuresAreSeparatedByStage() {
    metrics.ingestionFailed(BusinessMetrics.IngestionStage.PARSE);
    metrics.ingestionFailed(BusinessMetrics.IngestionStage.SEGMENT);
    metrics.ingestionFailed(BusinessMetrics.IngestionStage.SEGMENT);

    assertThat(stageCounter("parse")).isEqualTo(1.0);
    assertThat(stageCounter("segment")).isEqualTo(2.0);
    // ★ 没记过的阶段**没有这条序列**（不是"记成 0"）。
    //   Prometheus 侧的表现就是"查不到这条路"——而"查不到"与"一直是 0"在仪表盘上长得一样，
    //   这正是"每一段都必须真的埋点"的理由：漏埋点不会让曲线变高，只会让它**消失**。
    assertThat(
            registry.find(BusinessMetrics.INGESTION_FAILURES).tag("stage", "embedding").counter())
        .isNull();
  }

  @Test
  void noHighCardinalityTagsLeakIn() {
    metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.OK);
    metrics.ingestionFailed(BusinessMetrics.IngestionStage.EMBEDDING);
    metrics.firstToken(1_000_000L);

    for (Meter meter : registry.getMeters()) {
      assertThat(meter.getId().getTags().stream().map(Tag::getKey).toList())
          .as("指标 %s 的标签", meter.getId().getName())
          .doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS);
    }
  }

  private double counter(String outcome) {
    return registry.get(BusinessMetrics.UPSTREAM_CALLS).tag("outcome", outcome).counter().count();
  }

  private double stageCounter(String stage) {
    return registry.get(BusinessMetrics.INGESTION_FAILURES).tag("stage", stage).counter().count();
  }
}
