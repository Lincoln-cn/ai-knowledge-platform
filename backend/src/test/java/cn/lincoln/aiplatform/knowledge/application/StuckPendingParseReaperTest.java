package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link StuckPendingParseReaper} 的单测（形状照抄 {@link StuckSegmentingReaperTest}，与 #303 的 reaper
 * 各测各的机理）。
 *
 * <p>★ 这个闸门看守的是 issue #304 的兜底路径：「期望解析、任务从未创建」的文档超期后必须被标成可重试 —— 且失败文案必须与 #303 的「处理任务已中断」区分（判据③）。
 */
class StuckPendingParseReaperTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ParseRunner runner = mock(ParseRunner.class);

  private Document stale() {
    Document stale = mock(Document.class);
    when(stale.id()).thenReturn(UUID.randomUUID());
    when(stale.name()).thenReturn("扫描件-台账.pdf");
    when(stale.parseExpectedAt()).thenReturn(Instant.now().minusSeconds(3600));
    return stale;
  }

  @Test
  @DisplayName("★ 超期无主的待解析文档 ⇒ 标为分段失败（可重试），文案「解析任务未能启动」")
  void unownedStalePendingParseIsReaped() {
    Document stale = stale();
    Document fresh = mock(Document.class);
    when(fresh.status()).thenReturn(DocumentStatus.PENDING_PARSE);
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of(stale));
    when(documents.findById(stale.id())).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(stale.id())).thenReturn(false);

    new StuckPendingParseReaper(documents, runner, 2)
        .reap(Instant.now().plus(Duration.ofMinutes(10)));

    verify(fresh).recordParseFailure(contains("解析任务未能启动"), contains("与文件内容无关"), eq(""));
    verify(documents).save(fresh);
  }

  @Test
  @DisplayName("★ 任务在途不判死：inFlight 的份就算超期也继续等（正常等待 ≠ 任务没起来）")
  void inFlightStaleIsNotKilled() {
    Document stale = stale();
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of(stale));
    when(runner.isInFlight(stale.id())).thenReturn(true);

    new StuckPendingParseReaper(documents, runner, 2)
        .reap(Instant.now().plus(Duration.ofMinutes(10)));

    verify(documents, never()).save(any());
    verify(documents, never()).findById(any());
  }

  @Test
  @DisplayName("fresh 守卫：状态已离开待解析的份（比如已被回收过）跳过")
  void freshStateGuardSkipsWhenNoLongerPendingParse() {
    Document stale = stale();
    Document fresh = mock(Document.class);
    when(fresh.status()).thenReturn(DocumentStatus.SEGMENTING);
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of(stale));
    when(documents.findById(stale.id())).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(stale.id())).thenReturn(false);

    new StuckPendingParseReaper(documents, runner, 2)
        .reap(Instant.now().plus(Duration.ofMinutes(10)));

    verify(fresh, never()).recordParseFailure(anyString(), anyString(), anyString());
    verify(documents, never()).save(any());
  }

  @Test
  @DisplayName("查不到 fresh 的份（刚被删/刚完成）跳过")
  void missingFreshDocumentIsSkipped() {
    Document stale = stale();
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of(stale));
    when(documents.findById(stale.id())).thenReturn(Optional.empty());
    when(runner.isInFlight(stale.id())).thenReturn(false);

    new StuckPendingParseReaper(documents, runner, 2)
        .reap(Instant.now().plus(Duration.ofMinutes(10)));

    verify(documents, never()).save(any());
  }

  @Test
  @DisplayName("★ 判据③：失败文案与 #303 区分 —— 说「未能启动」，绝不说「已中断/解析超时」，也不甩锅给文件")
  void reapReasonDistinguishesFromStuckSegmentingAndNeverBlamesTheFile() {
    Document stale = stale();
    Document fresh = mock(Document.class);
    when(fresh.status()).thenReturn(DocumentStatus.PENDING_PARSE);
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of(stale));
    when(documents.findById(stale.id())).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(stale.id())).thenReturn(false);

    new StuckPendingParseReaper(documents, runner, 2)
        .reap(Instant.now().plus(Duration.ofMinutes(10)));

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> hint = ArgumentCaptor.forClass(String.class);
    verify(fresh).recordParseFailure(reason.capture(), hint.capture(), eq(""));
    // 与 StuckSegmentingReaper 的「解析超时：…（处理任务已中断）」是两把不同的尺子
    assertThat(reason.getValue()).contains("解析任务未能启动");
    assertThat(reason.getValue()).doesNotContain("已中断").doesNotContain("解析超时");
    assertThat(reason.getValue()).contains("可重试");
    assertThat(hint.getValue()).contains("服务繁忙，解析任务没有启动起来").contains("与文件内容无关");
  }

  @Test
  @DisplayName("★ 超期阈值用可注入的 now 计算（测试传未来时刻即判死，不用真等）")
  void cutoffIsComputedFromInjectedNow() {
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of());

    Instant now = Instant.parse("2026-11-06T12:00:00Z");
    new StuckPendingParseReaper(documents, runner, 2).reap(now);

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(documents).findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), cutoff.capture());
    // 阈值 2 分钟 ⇒ cutoff = now - 2 分钟
    assertThat(cutoff.getValue()).isEqualTo(now.minus(Duration.ofMinutes(2)));
  }

  @Test
  @DisplayName("阈值下限 1 分钟（配 0 不许把刚投出事件的份立刻判死）")
  void timeoutFloorIsOneMinute() {
    when(documents.findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), any(Instant.class)))
        .thenReturn(List.of());

    Instant now = Instant.parse("2026-11-06T12:00:00Z");
    new StuckPendingParseReaper(documents, runner, 0).reap(now);

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(documents).findStalePendingParse(eq(DocumentStatus.PENDING_PARSE), cutoff.capture());
    assertThat(cutoff.getValue()).isEqualTo(now.minus(Duration.ofMinutes(1)));
  }
}
