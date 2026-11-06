package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 归档清理的 dry-run（{@code #299}「留缝」）：按表报出「如果按留存期清理，会清掉什么」，<strong>只数不删</strong>。
 *
 * <p>★ 为什么是 dry-run 而不是真删（处置判定「留缝」）：「超过留存期自动清理」的天数是<strong>客户口径</strong>—— issue #299 没给数字，02
 * §5.1.1 行 616 写明「数字是口径不是承诺：真做清理任务前，必须与客户确认」。今天做真删只会做错（删早了是数据丢失、删晚了是合规谎言）。 所以这一批只落三样：配置位（{@link
 * RetentionPolicyProperties}）· 这个 dry-run 端点 · 一条「今天没有任何东西会自动删」的断言（{@code RetentionDryRunTest}）。
 *
 * <p>★ 真删执行体今天<strong>不存在</strong>：{@code app.lifecycle.retention.*} 没有任何消费者，没有任何
 * {@code @Scheduled} 会按它删 {@code cv_conversation} / {@code cv_message} /
 * 会话附件——「今天没有任何东西会自动删」不是承诺，是被断言钉住的现状。
 */
@Service
public class RetentionDryRunService {

  private static final Logger log = LoggerFactory.getLogger(RetentionDryRunService.class);

  /** dry-run 一次最多拖回多少条过期会话参与计数（计数底册不是删除批次；超出的以 {@code truncated} 如实报）。 */
  private static final int SCAN_LIMIT = 500;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ConversationAttachmentPort attachments;
  private final RetentionPolicyProperties retention;
  private final Clock clock;

  public RetentionDryRunService(
      ConversationRepository conversations,
      MessageRepository messages,
      ConversationAttachmentPort attachments,
      RetentionPolicyProperties retention,
      Clock clock) {
    this.conversations = conversations;
    this.messages = messages;
    this.attachments = attachments;
    this.retention = retention;
    this.clock = clock;
  }

  /**
   * 试算：按留存期（天）找出「软删已过期」的会话，按表报出会清掉什么。不删任何行、不删任何文件、不写审计。
   *
   * @param days 试算用的留存天数；{@code null} 时取配置位 {@code app.lifecycle.retention.retain-days}。两者都没有 ⇒
   *     拒绝（不编数字）
   * @return 按表逐项的试算读数
   */
  @Transactional
  public DryRunReport dryRun(Integer days) {
    Integer retainDays = days != null ? days : retention.retainDays();
    if (retainDays == null || retainDays < 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "留存期还没定，没法试算（数字待客户确认）",
          "可以带 ?days=N 按 N 天试算；正式数字要和客户确认后写进 app.lifecycle.retention.retain-days");
    }
    Instant cutoff = clock.instant().minus(Duration.ofDays(retainDays));
    long expiredConversations = conversations.countDeletedBefore(cutoff);
    List<Conversation> expired = conversations.findDeletedBefore(cutoff, SCAN_LIMIT);
    long messageRows = 0;
    List<java.util.UUID> ids = new java.util.ArrayList<>(expired.size());
    for (Conversation conversation : expired) {
      ids.add(conversation.id());
      messageRows += messages.countByConversation(conversation.id());
    }
    ConversationAttachmentPort.PurgeResult preview =
        attachments.previewConversationAttachments(ids);
    boolean truncated = expiredConversations > expired.size();
    log.info(
        "归档清理 dry-run（只数不删）：留存期 {} 天 · cutoff {} · 过期会话 {}（本批 {}） · 消息 {} · 附件文档 {} · 分段 {} · 向量 {}"
            + " · 将无人引用的文件 {}",
        retainDays,
        cutoff,
        expiredConversations,
        expired.size(),
        messageRows,
        preview.documents(),
        preview.segments(),
        preview.vectors(),
        preview.orphanedAttachmentIds().size());
    return new DryRunReport(
        retainDays,
        cutoff,
        expiredConversations,
        expired.size(),
        truncated,
        messageRows,
        preview.documents(),
        preview.segments(),
        preview.vectors(),
        preview.orphanedAttachmentIds().size(),
        retention.enabled());
  }

  /** dry-run 的按表读数。每一行都是「如果真删，会消失多少」——今天没有任何一行会被真的删掉。 */
  public record DryRunReport(
      Integer retainDays,
      Instant cutoff,
      long conversations,
      long conversationsScanned,
      boolean truncated,
      long messages,
      int attachmentDocuments,
      int attachmentSegments,
      int attachmentVectors,
      int files,
      boolean deletionEnabled) {}
}
