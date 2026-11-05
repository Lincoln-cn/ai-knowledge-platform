package cn.lincoln.aiplatform.knowledge.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StuckSegmentingReaper} 的边界（{@code #303} 判据②）。
 *
 * <p>★ 核心判据是「卡住」和「慢」的区分：<b>超期但在登记表里 ⇒ 慢，不判死；超期且无主 ⇒ 卡，回收成可重试</b>。 把正在慢跑的份误杀（标成失败）比卡住更坏 ——
 * 一份本来能成功的文档变成失败。所以"不误杀"这条要有自己的用例，不是顺带验。
 */
class StuckSegmentingReaperTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ParseRunner runner = mock(ParseRunner.class);

  private Document stale(UUID id) {
    Document stale = mock(Document.class);
    when(stale.id()).thenReturn(id);
    when(stale.name()).thenReturn("扫描件-台账.pdf");
    when(stale.updatedAt()).thenReturn(Instant.now().minusSeconds(3600));
    return stale;
  }

  @Test
  @DisplayName("★ 超期且无主（卡死）⇒ 回收成 SEGMENT_FAILED，且文案说清『不是文件的问题、可重试』")
  void unownedStaleSegmentingIsReaped() {
    UUID id = UUID.randomUUID();
    Document stale = stale(id);
    Document fresh = mock(Document.class);
    when(fresh.id()).thenReturn(id);
    when(fresh.status()).thenReturn(DocumentStatus.SEGMENTING);
    when(documents.findStaleByStatus(eq(DocumentStatus.SEGMENTING), any()))
        .thenReturn(List.of(stale));
    when(documents.findById(id)).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(id)).thenReturn(false);

    new StuckSegmentingReaper(documents, runner, 10).reap();

    verify(fresh).recordParseFailure(contains("解析超时"), contains("与文件内容无关"), eq(""));
    verify(documents).save(fresh);
  }

  @Test
  @DisplayName("★ 超期但在登记表里（慢跑/排队）⇒ 不判死：不回收、不落库，只留痕迹")
  void inFlightStaleIsNotKilled() {
    UUID id = UUID.randomUUID();
    Document stale = stale(id);
    when(documents.findStaleByStatus(eq(DocumentStatus.SEGMENTING), any()))
        .thenReturn(List.of(stale));
    when(runner.isInFlight(id)).thenReturn(true);

    new StuckSegmentingReaper(documents, runner, 10).reap();

    verify(documents, never()).save(any());
    verify(documents, never()).findById(any());
  }

  @Test
  @DisplayName("★ 再读守卫：读后判判后写之间任务恰好收尾 ⇒ 不用过期结论覆盖现状")
  void freshStateGuardSkipsWhenNoLongerSegmenting() {
    UUID id = UUID.randomUUID();
    Document stale = stale(id);
    Document fresh = mock(Document.class);
    when(fresh.id()).thenReturn(id);
    when(fresh.status()).thenReturn(DocumentStatus.PENDING_EMBEDDING);
    when(documents.findStaleByStatus(eq(DocumentStatus.SEGMENTING), any()))
        .thenReturn(List.of(stale));
    when(documents.findById(id)).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(id)).thenReturn(false);

    new StuckSegmentingReaper(documents, runner, 10).reap();

    verify(fresh, never()).recordParseFailure(any(), any(), any());
    verify(documents, never()).save(any());
  }

  @Test
  @DisplayName("文档已不在（被删/被并）⇒ 什么都不动")
  void missingFreshDocumentIsSkipped() {
    UUID id = UUID.randomUUID();
    Document stale = stale(id);
    when(documents.findStaleByStatus(eq(DocumentStatus.SEGMENTING), any()))
        .thenReturn(List.of(stale));
    when(documents.findById(id)).thenReturn(Optional.empty());
    when(runner.isInFlight(id)).thenReturn(false);

    new StuckSegmentingReaper(documents, runner, 10).reap();

    verify(documents, never()).save(any());
  }

  @Test
  @DisplayName("回收文案 = 可重试 + 与文件内容无关 —— 判据③的第二处出口（回收路径也不能说文件有问题）")
  void reapReasonAndHintNeverBlameTheFile() {
    UUID id = UUID.randomUUID();
    Document stale = stale(id);
    Document fresh = mock(Document.class);
    when(fresh.id()).thenReturn(id);
    when(fresh.status()).thenReturn(DocumentStatus.SEGMENTING);
    when(documents.findStaleByStatus(eq(DocumentStatus.SEGMENTING), any()))
        .thenReturn(List.of(stale));
    when(documents.findById(id)).thenReturn(Optional.of(fresh));
    when(runner.isInFlight(id)).thenReturn(false);

    new StuckSegmentingReaper(documents, runner, 10).reap();

    verify(fresh).recordParseFailure(contains("可重试"), contains("服务繁忙或处理中断"), eq(""));
  }
}
