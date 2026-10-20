package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ 读时收敛「超时未收尾」的 {@code GENERATING}（Issue #182 根因 1）。
 *
 * <p>为什么抽成一个包内工具而不是留在某一个 service 里：登录面（{@link ChatHistoryService}）与公开面（{@link
 * PublicChatService}）读的是同一张 {@code cv_message}，如果只修一边，另一边刷新后仍会永久停在"生成中"—— 症状相同、代码却有两份， 迟早漂移。
 *
 * <p>判据 = {@code status == GENERATING} 且 {@code updatedAt} 早于 {@code now -} {@link
 * #STALE_GENERATING_AFTER}。 命中的消息调 {@link Message#fail} 后 {@code save}：只改终态字段（状态 / 失败原因 / 提示 /
 * 耗时），<strong>不碰</strong>正文、引用与 用量 —— 已生成的那一段必须保留（MSG-I2）。
 *
 * <p>★ 并发兜底：先按页读出候选，再对每个候选 {@link MessageRepository#findByIdForUpdate} <strong>锁行重查</strong>。收尾线程可能
 * 正好在同一瞬把它收掉（{@code fail} 会因状态已不是 {@code GENERATING} 抛 {@link IllegalStateException}）—— 那是正常竞态，
 * {@code log.debug} 后跳过，<strong>不许</strong>让整个历史接口失败；<strong>更不许</strong>把并发收尾刚写好的答案覆盖成失败（锁保证
 * 后到者看到先到者的终态）。
 */
final class StaleGeneratingReconciler {

  private static final Logger log = LoggerFactory.getLogger(StaleGeneratingReconciler.class);

  /**
   * ★ 一次生成的最长合理时长：超过 10 分钟仍无收尾，视为服务端中断（收尾线程被打断 / 进程被杀）—— 那条消息永远不会再被收尾，读到它时就地 收敛成 {@code
   * FAILED}，免得界面永久停在"生成中"。
   */
  static final Duration STALE_GENERATING_AFTER = Duration.ofMinutes(10);

  private StaleGeneratingReconciler() {}

  /**
   * 把本页/本窗口里陈旧的 {@code GENERATING} 就地收敛成 {@code FAILED}。
   *
   * @param messages 消息仓储（调用方必须处在事务里：行锁需要事务）
   * @param page 本页/本窗口的消息（就地修改其终态字段）
   */
  static void reconcile(MessageRepository messages, List<Message> page) {
    Instant now = Instant.now();
    for (Message message : page) {
      if (message.status() != MessageStatus.GENERATING) {
        continue;
      }
      Instant updated = message.updatedAt();
      if (updated == null || !updated.isBefore(now.minus(STALE_GENERATING_AFTER))) {
        continue;
      }
      // ★ 锁行重查：拿到锁之后再判一次状态与时间，收尾线程已经写好的终态绝不能被这一轮覆盖
      Message locked = messages.findByIdForUpdate(message.id()).orElse(null);
      if (locked == null || locked.status() != MessageStatus.GENERATING) {
        continue;
      }
      Instant lockedUpdated = locked.updatedAt();
      if (lockedUpdated == null || !lockedUpdated.isBefore(now.minus(STALE_GENERATING_AFTER))) {
        continue;
      }
      long durationMs = Math.max(0L, Duration.between(fallbackInstant(locked), now).toMillis());
      try {
        locked.fail("生成中断：超过 10 分钟未收尾，视为服务端中断（已生成内容保留）", "可重新生成", durationMs);
        messages.save(locked);
      } catch (IllegalStateException concurrent) {
        // 别的线程刚刚收尾（状态已不是 GENERATING）⇒ 本次收敛让路，历史接口照常返回
        log.debug("陈旧 GENERATING 收敛让路：消息 {} 已被并行收尾", locked.id(), concurrent);
      }
    }
  }

  /** 收敛计时的起点：{@code createdAt} 为空时兜底用 {@code updatedAt}。 */
  private static Instant fallbackInstant(Message message) {
    return message.createdAt() != null ? message.createdAt() : message.updatedAt();
  }
}
