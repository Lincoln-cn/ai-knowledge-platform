package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import cn.lincoln.aiplatform.knowledge.infrastructure.OcrFastScanner;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ParseRunner#onUploaded} 的事务语义单测（issue #304 方案 2 的判据载体）。
 *
 * <p>★ 三条核心语义：① 任务只在 afterCommit 之后起跑（#18：起跑的必须是已落库的行）；② 无事务上下文退化成直接排队 （{@code
 * registerSynchronization} 在那里会抛 {@code IllegalStateException}）；③ 任何排队路径失败都不许外抛 ——
 * 本方法同步跑在上传事务里，外抛会把"解析没起来"换成"上传直接失败"，那是更坏的 silent-failure 换法。
 */
class ParseRunnerEventListenerTest {

  private ParseRunner newRunner() {
    return new ParseRunner(
        mock(TikaMarkdownParser.class),
        mock(OcrFastScanner.class),
        mock(ParseProgressService.class),
        mock(DocumentService.class),
        mock(ParseConcurrencyGate.class));
  }

  private DocumentUploaded event() {
    return new DocumentUploaded(
        UUID.randomUUID(), "内容".getBytes(StandardCharsets.UTF_8), "扫描件-台账.pdf", UUID.randomUUID());
  }

  @Test
  @DisplayName("★ 有事务：注册 afterCommit 回调，事务提交前任务不起跑，提交后才排队（#18 语义不退让）")
  void taskStartsOnlyAfterCommit() {
    ParseRunner runner = spy(newRunner());
    DocumentUploaded event = event();
    ArgumentCaptor<TransactionSynchronization> sync =
        ArgumentCaptor.forClass(TransactionSynchronization.class);

    try (MockedStatic<TransactionSynchronizationManager> mocked =
        mockStatic(TransactionSynchronizationManager.class)) {
      mocked.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
      mocked
          .when(() -> TransactionSynchronizationManager.registerSynchronization(sync.capture()))
          .thenAnswer(invocation -> null);

      runner.onUploaded(event);

      // 事务未提交：回调只被注册、任务还没起跑
      verify(runner, never()).run(any(), any(), any(), any());

      // 模拟事务提交 ⇒ afterCommit 触发 ⇒ 任务排队起跑
      sync.getValue().afterCommit();
      verify(runner, timeout(2000))
          .run(
              eq(event.documentId()),
              eq(event.content()),
              eq(event.filename()),
              eq(event.userId()));
    }
  }

  @Test
  @DisplayName("★ 无事务上下文：退化成直接排队（registerSynchronization 在这里会抛 IllegalStateException）")
  void degradesToImmediateSchedulingWithoutTransaction() {
    ParseRunner runner = spy(newRunner());
    DocumentUploaded event = event();

    try (MockedStatic<TransactionSynchronizationManager> mocked =
        mockStatic(TransactionSynchronizationManager.class)) {
      mocked.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);

      runner.onUploaded(event);

      verify(runner, timeout(2000))
          .run(
              eq(event.documentId()),
              eq(event.content()),
              eq(event.filename()),
              eq(event.userId()));
      mocked.verify(
          () -> TransactionSynchronizationManager.registerSynchronization(any()), never());
    }
  }

  @Test
  @DisplayName("★ 注册同步回调失败 ⇒ 不外抛（上传仍然成功；文档由 StuckPendingParseReaper 超期兜底）")
  void registrationFailureNeverThrows() {
    ParseRunner runner = spy(newRunner());
    DocumentUploaded event = event();

    try (MockedStatic<TransactionSynchronizationManager> mocked =
        mockStatic(TransactionSynchronizationManager.class)) {
      mocked.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
      mocked
          .when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
          .thenThrow(new IllegalStateException("事务同步未激活"));

      assertThatCode(() -> runner.onUploaded(event)).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("★ 排队提交失败（线程池已关）⇒ 不外抛，等 StuckPendingParseReaper 兜底")
  void submitFailureNeverThrows() throws Exception {
    ParseRunner runner = spy(newRunner());
    DocumentUploaded event = event();
    shutdownExecutor(runner);

    try (MockedStatic<TransactionSynchronizationManager> mocked =
        mockStatic(TransactionSynchronizationManager.class)) {
      mocked.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);

      assertThatCode(() -> runner.onUploaded(event)).doesNotThrowAnyException();
      verify(runner, never()).run(any(), any(), any(), any());
    }
  }

  /** 把 runner 内部的虚拟线程池关掉 ⇒ executor.submit 必抛 RejectedExecutionException。 */
  private void shutdownExecutor(ParseRunner runner) throws Exception {
    Field field = ParseRunner.class.getDeclaredField("executor");
    field.setAccessible(true);
    ((ExecutorService) field.get(runner)).shutdown();
  }
}
