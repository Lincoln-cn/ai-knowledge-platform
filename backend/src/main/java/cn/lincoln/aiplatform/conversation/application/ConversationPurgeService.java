package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.conversation.ConversationFilePurgePort;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话<strong>彻底删除</strong>（`#299` 第一块）—— 会话、消息、附件行与字节一起从库里消失， 不可恢复。与软删（{@code
 * ConversationListService.delete}，只标 {@code deleted_at}）是<strong>两个独立动作</strong>。
 *
 * <h3>删什么、按什么顺序（按表逐项）</h3>
 *
 * <ol>
 *   <li>{@code kb_document (scope=CONVERSATION)} 行 + {@code kb_segment} + {@code vec_embedding_768}
 *       —— 经 {@link ConversationAttachmentPort#purgeConversationAttachments}（知识模块删自己的三张表）；
 *   <li>没有别的文档行引用的 {@code cv_file} 行 + 磁盘字节 —— 经 {@link ConversationFilePurgePort}
 *       （会话模块删自己的文件；引用计数在知识侧判过）；
 *   <li>{@code cv_message} —— <strong>必须显式删</strong>：它刻意没有外键到会话（"外键只会添乱"）， 删会话行不会带走消息。{@code
 *       cv_feedback_state} 有外键级联到 {@code cv_message}，自动带走；
 *   <li>{@code cv_conversation} 行 —— {@code cv_conversation_feedback}、{@code cv_share} 靠外键级联带走。
 * </ol>
 *
 * <p>★ 顺序不能反：先删会话行再删消息时，中途失败会留下"会话没了、消息还在"的孤儿行， 而孤儿消息在任何视图里都不可见、也永远不会被清理（恰恰是"彻底删除在撒谎"的形状）。
 *
 * <h3>为什么必须先软删</h3>
 *
 * <p>与文档 purge 同一口径（{@code DocumentService.purge}）：软删是"从用户视野里消失"（可反悔），
 * 彻底删除是"这份材料不再存在"。两步分开，用户就有一次反悔的机会。
 *
 * <h3>审计不删</h3>
 *
 * <p>业务数据消失得越干净，"发生过什么"越只能靠审计回答。本动作写 {@code conversation.purge} 与 {@code attachment.purge}
 * <strong>各一条</strong>（issue `#299` 判据：两条独立入口不许合成一条）。
 */
@Service
public class ConversationPurgeService {

  private static final Logger log = LoggerFactory.getLogger(ConversationPurgeService.class);

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ConversationAttachmentPort attachments;
  private final ConversationFilePurgePort attachmentFiles;
  private final AuditLogPort audit;

  public ConversationPurgeService(
      ConversationRepository conversations,
      MessageRepository messages,
      ConversationAttachmentPort attachments,
      ConversationFilePurgePort attachmentFiles,
      AuditLogPort audit) {
    this.conversations = conversations;
    this.messages = messages;
    this.attachments = attachments;
    this.attachmentFiles = attachmentFiles;
    this.audit = audit;
  }

  /**
   * 彻底删除一条会话（`#299`）。返回各表删掉的行数，给按表逐项的判据用。
   *
   * @param conversationId 会话 id
   * @param currentUserId 操作者（必须是会话归属人 —— 与软删同归属口径）
   * @return 各表删掉的行数
   */
  @Transactional
  public PurgeOutcome purge(UUID conversationId, UUID currentUserId) {
    Conversation conversation = requireOwnedSoftDeleted(conversationId, currentUserId);

    // ① 附件三域（kb_document/kb_segment/向量）—— 知识模块删自己的表，回来一份清单
    ConversationAttachmentPort.PurgeResult attachmentPurge =
        attachments.purgeConversationAttachments(conversationId);
    // ② 没有文档行引用的 cv_file + 磁盘字节（引用计数在知识侧判过 = orphaned）
    int filesPurged = 0;
    for (UUID attachmentId : attachmentPurge.orphanedAttachmentIds()) {
      if (attachmentFiles.purgeAttachmentFile(attachmentId)) {
        filesPurged++;
      }
    }
    // ③ 消息（无外键必须显式；cv_feedback_state 随外键级联走）
    long messagesPurged = messages.deleteByConversation(conversationId);
    // ④ 会话行（cv_conversation_feedback/cv_share 随外键级联走）
    conversations.delete(conversation);

    audit.record(
        AuditAction.CONVERSATION_PURGE,
        "CONVERSATION",
        conversationId.toString(),
        currentUserId,
        Map.of(
            "messages",
            messagesPurged,
            "documents",
            attachmentPurge.documents(),
            "segments",
            attachmentPurge.segments(),
            "vectors",
            attachmentPurge.vectors(),
            "files",
            filesPurged));
    if (!attachmentPurge.orphanedAttachmentIds().isEmpty()) {
      audit.record(
          AuditAction.ATTACHMENT_PURGE,
          "ATTACHMENT",
          conversationId.toString(),
          currentUserId,
          Map.of(
              "conversationId",
              conversationId.toString(),
              "files",
              filesPurged,
              "attachmentIds",
              attachmentPurge.orphanedAttachmentIds().toString()));
    }
    log.info(
        "会话 {} 已彻底删除：消息 {} · 附件文档 {} · 分段 {} · 向量 {} · 文件 {}",
        conversationId,
        messagesPurged,
        attachmentPurge.documents(),
        attachmentPurge.segments(),
        attachmentPurge.vectors(),
        filesPurged);
    return new PurgeOutcome(
        messagesPurged,
        attachmentPurge.documents(),
        attachmentPurge.segments(),
        attachmentPurge.vectors(),
        filesPurged);
  }

  /**
   * 彻底删除只对<strong>已软删</strong>的会话开放（与 {@code DocumentService.purge} 同形）。
   *
   * <p>★ 归属校验与软删同口径（{@code ConversationListService.requireOwned}）：不把"归属别人"单独报 403 —— 那会告诉调用方这个 id
   * 真实存在。 但这里<strong>不过滤软删</strong>（purge 的前提恰恰是已软删），两者的可见性谓词不同，刻意不共用一个方法。
   */
  private Conversation requireOwnedSoftDeleted(UUID conversationId, UUID userId) {
    Conversation conversation =
        conversations
            .findById(conversationId)
            .filter(c -> c.userId().equals(userId))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在", "它可能已被删除，或者不在你的可见范围内"));
    if (conversation.deletedAt() == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "这条会话还在使用中，不能彻底删除", "先删除它（会进入待清理状态），确认不再需要之后再来彻底删除");
    }
    return conversation;
  }

  /**
   * 彻底删除的各表读数（按表逐项断言用）。
   *
   * @param messages 消息删掉的行数
   * @param documents 附件文档行删掉的行数
   * @param segments 分段删掉的行数
   * @param vectors 向量删掉的行数
   * @param files 文件（{@code cv_file} 行 + 磁盘字节）删掉的个数
   */
  public record PurgeOutcome(long messages, int documents, int segments, int vectors, int files) {}
}
