package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回收站超期清理（`#259`）：默认关、按批删、单篇失败不拖垮整批、每次删都留痕。
 *
 * <p>★ 用 Mockito 而不是真栈：要验的是**顺序与开关**，不是 SQL。真栈那条由 `DocumentPurgeTest` 覆盖（同一套删除路径）。
 */
class DeletedDocumentSweeperTest {

  private static final Instant NOW = Instant.parse("2026-10-31T04:10:00Z");

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final SegmentRepository segments = mock(SegmentRepository.class);
  private final SegmentEmbeddingRepository embeddings = mock(SegmentEmbeddingRepository.class);
  private final AuditLogPort audit = mock(AuditLogPort.class);

  private DeletedDocumentSweeper sweeper(boolean enabled, int retainDays, int batch) {
    return new DeletedDocumentSweeper(
        enabled,
        retainDays,
        batch,
        documents,
        segments,
        embeddings,
        audit,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("★ 关闭时一条都不查、一条都不删 —— 默认值必须是真的关闭")
  void disabledSweeperDoesNothing() {
    assertThat(sweeper(false, 7, 50).sweep()).isZero();
    assertThat(sweeper(false, 7, 50).cutoff()).isNull();
    verifyNoInteractions(documents, segments, embeddings, audit);
  }

  @Test
  @DisplayName("截止时间 = 现在 − 保留天数")
  void cutoffIsNowMinusRetainDays() {
    assertThat(sweeper(true, 7, 50).cutoff()).isEqualTo(Instant.parse("2026-10-24T04:10:00Z"));
  }

  @Test
  @DisplayName("★ 先分段与向量、再文档行 —— 反过来会留孤儿分段，而孤儿分段照样命中")
  void deleteOrderIsSegmentsThenDocument() {
    Document document = mockDocument();
    // ★ 先把 id 取出来：在 verify/inOrder 的参数位置调用 mock 方法会让 Mockito 以为在用匹配器
    UUID id = document.id();
    when(documents.findDeletedBefore(any(), anyInt())).thenReturn(List.of(document));

    assertThat(sweeper(true, 7, 50).sweep()).isEqualTo(1);

    var order = org.mockito.Mockito.inOrder(documents, segments, embeddings);
    order.verify(segments).deleteByDocumentId(id);
    order.verify(embeddings).deleteByDocumentId(id);
    order.verify(documents).delete(document);
    org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.captor();
    verify(audit)
        .record(
            eq(AuditAction.DOCUMENT_PURGE),
            eq("DOCUMENT"),
            eq(id.toString()),
            isNull(),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("trigger", "retention");
  }

  @Test
  @DisplayName("★ 单篇失败不拖垮整批：其余照删，返回的是真正删掉的条数")
  void oneFailureDoesNotStopTheBatch() {
    Document first = mockDocument();
    Document second = mockDocument();
    UUID firstId = first.id();
    when(documents.findDeletedBefore(any(), anyInt())).thenReturn(List.of(first, second));
    org.mockito.Mockito.doThrow(new IllegalStateException("外键还有引用"))
        .when(segments)
        .deleteByDocumentId(eq(firstId));

    assertThat(sweeper(true, 7, 50).sweep()).isEqualTo(1);
    verify(documents).delete(second);
    verify(documents, never()).delete(first);
  }

  @Test
  @DisplayName("批量上限传进了查询 —— 不许一次把整表搬进内存")
  void batchSizeIsPassedToTheQuery() {
    when(documents.findDeletedBefore(any(), anyInt())).thenReturn(List.of());

    sweeper(true, 7, 7).sweep();

    verify(documents).findDeletedBefore(any(), eq(7));
  }

  @Test
  @DisplayName("保留天数配成 0 或负数时按 1 天处理（不是「永不过期」，那会让清理永不生效）")
  void retainDaysIsClampedToOne() {
    assertThat(sweeper(true, 0, 50).cutoff()).isEqualTo(Instant.parse("2026-10-30T04:10:00Z"));
  }

  private Document mockDocument() {
    Document document = mock(Document.class);
    UUID id = UUID.randomUUID();
    when(document.id()).thenReturn(id);
    when(document.name()).thenReturn("已删材料.docx");
    when(document.kbId()).thenReturn(UUID.randomUUID());
    return document;
  }
}
