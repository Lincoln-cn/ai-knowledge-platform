package cn.lincoln.aiplatform.platform.infrastructure.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.AuditQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 留存期清理的判据（`#75` 判据 ②）：算得对、删得对、关着的时候一条都不许动。 */
@DisplayName("留存期清理：截止时间、开关、自身留痕与失败不假装成功")
class AuditRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-10-31T03:30:00Z");

  private final AuditQueryPort query = mock(AuditQueryPort.class);
  private final AuditLogPort audit = mock(AuditLogPort.class);

  private AuditRetentionSweeper sweeper(boolean enabled, int retainDays) {
    return new AuditRetentionSweeper(
        enabled, retainDays, query, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("关闭时算不出截止时间，也一条都不动")
  void disabledMeansNoCutoffAndNoCalls() {
    AuditRetentionSweeper sweeper = sweeper(false, 365);

    assertThat(sweeper.cutoff()).as("★ 开关没开就必须是「没有截止时间」，而不是「截止到很久以前」").isEmpty();
    assertThat(sweeper.sweep()).as("关闭时清理必须是空操作").isZero();
    verifyNoInteractions(query, audit);
  }

  @Test
  @DisplayName("保留 90 天 ⇒ 截止 = 现在 - 90 天，删完写一条 audit.purge 且注明是调度删的")
  void enabledPurgesUpToTheRetentionCutoffAndAuditsItself() {
    when(query.purgeOlderThan(any())).thenReturn(7L);

    AuditRetentionSweeper sweeper = sweeper(true, 90);
    Instant expected = NOW.minus(Duration.ofDays(90));

    assertThat(sweeper.cutoff()).contains(expected);
    assertThat(sweeper.sweep()).isEqualTo(7L);

    verify(query).purgeOlderThan(expected);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(audit)
        .record(
            eq(AuditAction.AUDIT_PURGE),
            eq("AUDIT_LOG"),
            eq("pf_audit_log"),
            eq(null),
            detail.capture());
    assertThat(detail.getValue())
        .as("★ 明细里要能区分「人点的」和「调度删的」，并留下天数依据")
        .containsEntry("trigger", "retention")
        .containsEntry("deleted", 7L)
        .containsEntry("beforeAt", expected.toString());
  }

  @Test
  @DisplayName("这次没有过期数据 ⇒ 不写审计（0 条的清理不是一次动作）")
  void nothingExpiredMeansNoAuditRow() {
    when(query.purgeOlderThan(any())).thenReturn(0L);

    assertThat(sweeper(true, 365).sweep()).isZero();
    verify(audit, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("保留天数配成 0 或负数 ⇒ 按 1 天下限执行，不允许「删光」在这里发生")
  void retainDaysBelowOneIsClampedToTheFloor() {
    when(query.purgeOlderThan(any())).thenReturn(0L);

    AuditRetentionSweeper sweeper = sweeper(true, 0);

    assertThat(sweeper.cutoff())
        .as("★ 0 天 = 删光全部审计；下限必须是 1 天，且这件事要能在这里被看见")
        .contains(NOW.minus(Duration.ofDays(AuditRetentionSweeper.MIN_RETAIN_DAYS)));
  }

  @Test
  @DisplayName("库出错 ⇒ 返回 0 且不写审计，但绝不假装成功")
  void databaseFailureDoesNotLookLikeSuccess() {
    when(query.purgeOlderThan(any())).thenThrow(new IllegalStateException("connection refused"));

    assertThat(sweeper(true, 365).sweep()).isZero();
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("截止时间是「现在 - 天数」，不是「某个固定日期」")
  void theCutoffMovesWithTheClock() {
    AuditRetentionSweeper later =
        new AuditRetentionSweeper(
            true, 30, query, audit, Clock.fixed(NOW.plus(Duration.ofDays(10)), ZoneOffset.UTC));

    assertThat(later.cutoff())
        .as("★ 截止时间跟着时钟走：晚十天执行，截止也晚十天（不是某个写死的日期）")
        .contains(NOW.plus(Duration.ofDays(10)).minus(Duration.ofDays(30)));
  }
}
