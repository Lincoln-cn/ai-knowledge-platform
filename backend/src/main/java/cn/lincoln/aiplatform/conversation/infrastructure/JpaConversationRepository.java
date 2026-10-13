package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 的会话仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code ConversationRepository} —— 双继承会让 {@code Pageable} /
 * {@code Specification} 这些框架类型漏进领域层（与 {@code JpaKnowledgeBaseRepository} / {@code
 * JpaQaPairRepository} 同一条判断）。
 */
public interface JpaConversationRepository extends JpaRepository<Conversation, UUID> {

  /**
   * 某个人的会话（未软删，最近发言在前；还没发过言的排在最后）。
   *
   * <p>★ {@code deletedAtIsNull} 与 {@code V32} 的 {@code idx_conv_user}（{@code where deleted_at is
   * null}） 是同一个口径：partial 索引只有查询也带这个条件才会被用到。
   */
  List<Conversation> findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(
      UUID userId);
}
