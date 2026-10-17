package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.ConversationFeedback;
import cn.lincoln.aiplatform.conversation.domain.ConversationFeedbackRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 会话评分仓储的 JPA 适配器。
 *
 * <p>★ 它只做一件事：把领域端口翻译成 Spring Data 调用。业务口径（1–5 分、覆盖语义、归属 404） 全在 {@code
 * ConversationFeedbackService} 与 {@code ConversationFeedback} 里 —— 适配器里出现业务判断，就说明那条判断有了第二个家。
 */
@Repository
public class ConversationFeedbackRepositoryAdapter implements ConversationFeedbackRepository {

  private final JpaConversationFeedbackRepository jpa;

  public ConversationFeedbackRepositoryAdapter(JpaConversationFeedbackRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ConversationFeedback save(ConversationFeedback feedback) {
    return jpa.save(feedback);
  }

  @Override
  public Optional<ConversationFeedback> findByConversationAndUser(
      UUID conversationId, UUID userId) {
    return jpa.findByConversationIdAndUserId(conversationId, userId);
  }

  @Override
  public List<ConversationFeedback> listByConversation(UUID conversationId) {
    // ★ 管理端"反馈列表"按时间倒序；★ 用派生查询而不是 findAll() + 内存过滤（后者是全表扫）
    return jpa.findByConversationIdOrderByCreatedAtDesc(conversationId);
  }
}
