package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.ConversationShare;
import cn.lincoln.aiplatform.conversation.domain.ConversationShareRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link ConversationShareRepository} 的 JPA 实现。 */
@Component
public class ConversationShareRepositoryAdapter implements ConversationShareRepository {

  private final JpaConversationShareRepository jpa;

  public ConversationShareRepositoryAdapter(JpaConversationShareRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ConversationShare save(ConversationShare share) {
    return jpa.save(share);
  }

  @Override
  public Optional<ConversationShare> findByToken(String token) {
    return jpa.findByToken(token);
  }

  @Override
  public Optional<ConversationShare> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<ConversationShare> listByConversation(UUID conversationId) {
    return jpa.findByConversationIdOrderByCreatedAtDesc(conversationId);
  }
}
