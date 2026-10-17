package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.ConversationFeedback;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 的会话评分仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code ConversationFeedbackRepository} —— 双继承会让框架类型漏进领域层（与
 * {@code JpaMessageRepository} / {@code JpaConversationRepository} 同一条判断）。
 */
public interface JpaConversationFeedbackRepository
    extends JpaRepository<ConversationFeedback, UUID> {

  /**
   * 查某人给某场会话的评分。
   *
   * <p>★ 列序与迁移里的 {@code uq_conv_feedback_user (conversation_id, user_id)} 一致 —— 那个唯一约束同时就是这个查询的索引。
   *
   * @param conversationId 会话
   * @param userId 评分人
   * @return 评分；没有则空
   */
  Optional<ConversationFeedback> findByConversationIdAndUserId(UUID conversationId, UUID userId);

  /**
   * 一场会话的全部评分，按提交时间倒序（管理端统计用）。
   *
   * <p>★ 用它而不是 `findAll()` + 内存过滤：后者是**全表扫**，而这张表会随使用量增长。 ★ 列序与迁移里的
   * `idx_conv_feedback_conversation` 一致。
   *
   * @param conversationId 会话
   * @return 评分列表
   */
  java.util.List<ConversationFeedback> findByConversationIdOrderByCreatedAtDesc(
      UUID conversationId);
}
