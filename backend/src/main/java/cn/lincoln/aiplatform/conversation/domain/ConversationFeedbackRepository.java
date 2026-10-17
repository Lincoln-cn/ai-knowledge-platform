package cn.lincoln.aiplatform.conversation.domain;

import java.util.Optional;
import java.util.UUID;

/** 会话评分的仓储端口。 */
public interface ConversationFeedbackRepository {

  /**
   * 保存（新建或覆盖）。
   *
   * @param feedback 评分
   * @return 落库后的那条
   */
  ConversationFeedback save(ConversationFeedback feedback);

  /**
   * 查某人给某场会话的评分。
   *
   * @param conversationId 会话
   * @param userId 评分人
   * @return 评分；没有则空
   */
  Optional<ConversationFeedback> findByConversationAndUser(UUID conversationId, UUID userId);

  /**
   * 一场会话的全部评分（管理端统计用）。
   *
   * @param conversationId 会话
   * @return 评分列表
   */
  java.util.List<ConversationFeedback> listByConversation(UUID conversationId);
}
