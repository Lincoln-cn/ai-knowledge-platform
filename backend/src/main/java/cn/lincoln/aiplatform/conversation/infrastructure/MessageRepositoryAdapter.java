package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的消息仓储适配成 domain 的 {@link MessageRepository}。
 *
 * <p>★ 存在的理由与其它适配器相同：<strong>框架类型不许进领域层</strong>。
 *
 * <p>★ <strong>这个类刻意很薄</strong>：{@link #save} 只做一次 {@code save}，没有任何"顺手把会话计数 +1" 或"顺手算一下密级"的逻辑 ——
 * 状态机与密级抬升只发生在领域对象上（{@link Message#appendText} / {@code
 * Conversation.raiseClassification}），适配器碰它们一次就多一个"谁说了算"的歧义。
 *
 * <p>★ 关于 {@code jsonb} 列（{@code references}）的读写：本类<strong>不碰</strong>它。 {@link
 * Message#bindReferences} 把契约的 {@code SourceRef} 列表放在领域对象上， 由 JPA 的 JSON 映射（{@code
 * Message.references} 上的 {@code @JdbcTypeCode(SqlTypes.JSON)}） 原样写进 {@code jsonb} 列、原样读回来。★
 * 这里<strong>不做</strong>任何字段筛选 / 改名 / 排序： 快照的意义就是"当时是什么样，现在还是什么样"。
 */
@Component
public class MessageRepositoryAdapter implements MessageRepository {

  private final JpaMessageRepository jpa;

  public MessageRepositoryAdapter(JpaMessageRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Message save(Message message) {
    return jpa.save(message);
  }

  @Override
  public Optional<Message> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<Message> listByConversation(UUID conversationId) {
    return jpa.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
  }
}
