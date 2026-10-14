package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Message;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 的消息仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code MessageRepository} —— 双继承会让 {@code Pageable} / {@code
 * Specification} 这些框架类型漏进领域层（与 {@code JpaConversationRepository} / {@code
 * JpaKnowledgeBaseRepository} 同一条判断）。
 */
public interface JpaMessageRepository extends JpaRepository<Message, UUID> {

  /**
   * 某个会话的全部消息，按创建时间升序（对话的自然顺序）。
   *
   * <p>★ {@code CreatedAtAsc} 与 {@code V33} 的 {@code idx_msg_conv (conversation_id, created_at)}
   * <strong>同一个列序</strong>：这个索引就是为了这一条查询建的，列序反了它就白建。
   *
   * <p>★ 同秒创建的两条消息（断言里为了快会把它们挤进同一毫秒）之间没有稳定顺序 —— 所以再按 {@code id} 兜一道， 让返回顺序在"时间相同"时也是确定的（否则测试会偶发地飘）。
   */
  List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);
}
