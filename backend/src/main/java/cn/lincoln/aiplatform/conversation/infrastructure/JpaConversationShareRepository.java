package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.ConversationShare;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code cv_share} 的 Spring Data 接口。 */
public interface JpaConversationShareRepository extends JpaRepository<ConversationShare, UUID> {

  Optional<ConversationShare> findByToken(String token);

  List<ConversationShare> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
