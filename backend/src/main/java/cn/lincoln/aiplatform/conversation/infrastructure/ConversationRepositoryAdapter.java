package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的会话仓储适配成 domain 的 {@link ConversationRepository}。
 *
 * <p>★ 存在的理由与其它适配器相同：<strong>框架类型不许进领域层</strong>。
 *
 * <p>★ <strong>这个类刻意很薄</strong>：{@link #save} 只做一次 {@code save}，没有任何"顺手把密级也算一下"的逻辑 ——
 * 密级的抬升只发生在领域对象上（{@link Conversation#raiseClassification}）， 适配器碰它一次就多一个"谁说了算"的歧义。
 */
@Component
public class ConversationRepositoryAdapter implements ConversationRepository {

  private final JpaConversationRepository jpa;

  public ConversationRepositoryAdapter(JpaConversationRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Conversation save(Conversation conversation) {
    return jpa.save(conversation);
  }

  @Override
  public Optional<Conversation> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<Conversation> listByUser(UUID userId) {
    return jpa.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(userId);
  }
}
