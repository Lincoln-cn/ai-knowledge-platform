package cn.lincoln.aiplatform.conversation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link StaleGeneratingReconciler} 的边界判据。
 *
 * <p>★ 这条判据盯的是**锁行重查**：页里读到的是陈旧的 {@code GENERATING}，但拿到行锁后它已经被收尾线程写成 {@code COMPLETED} ——
 * 这一轮<strong>不许</strong>再覆盖成 {@code FAILED}，否则用户刚生成好的答案会消失。
 */
class StaleGeneratingReconcilerTest {

  private static final UUID MESSAGE_ID = UUID.fromString("10000000-0000-0000-0000-000000000182");

  @Test
  void staleGeneratingIsFailedAndSaved() {
    MessageRepository messages = mock(MessageRepository.class);
    Message stale = staleMessage();
    when(messages.findByIdForUpdate(MESSAGE_ID)).thenReturn(Optional.of(stale));

    StaleGeneratingReconciler.reconcile(messages, List.of(stale));

    verify(stale).fail(anyString(), anyString(), anyLong());
    verify(messages).save(stale);
  }

  @Test
  void freshGeneratingIsLeftAlone() {
    MessageRepository messages = mock(MessageRepository.class);
    Message fresh = freshMessage();

    StaleGeneratingReconciler.reconcile(messages, List.of(fresh));

    verifyNoInteractions(messages);
    verify(fresh, never()).fail(anyString(), anyString(), anyLong());
  }

  @Test
  void concurrentCompletionWinsOverStaleReconciliation() {
    MessageRepository messages = mock(MessageRepository.class);
    Message stale = staleMessage();
    Message completed = mock(Message.class);
    when(completed.id()).thenReturn(MESSAGE_ID);
    when(completed.status()).thenReturn(MessageStatus.GENERATED);
    when(messages.findByIdForUpdate(MESSAGE_ID)).thenReturn(Optional.of(completed));

    StaleGeneratingReconciler.reconcile(messages, List.of(stale));

    // ★ 拿到锁后发现已经收尾 ⇒ 不写库、不改状态；答案必须原样保留
    verify(completed, never()).fail(anyString(), anyString(), anyLong());
    verify(messages, never()).save(any());
  }

  private static Message staleMessage() {
    Message stale = mock(Message.class);
    when(stale.id()).thenReturn(MESSAGE_ID);
    when(stale.status()).thenReturn(MessageStatus.GENERATING);
    when(stale.updatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(20)));
    when(stale.createdAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(21)));
    return stale;
  }

  private static Message freshMessage() {
    Message fresh = mock(Message.class);
    when(fresh.id()).thenReturn(MESSAGE_ID);
    when(fresh.status()).thenReturn(MessageStatus.GENERATING);
    when(fresh.updatedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(1)));
    return fresh;
  }
}
