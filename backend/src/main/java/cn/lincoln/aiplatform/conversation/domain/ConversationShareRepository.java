package cn.lincoln.aiplatform.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话分享链接的仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>没有"按 token 找并顺手过滤"的入口：可用性（未撤销 / 未过期）是领域判断，放在 {@link ConversationShare#usableAt}
 * 与服务层，仓储只回答"这一行在不在"。
 */
public interface ConversationShareRepository {

  ConversationShare save(ConversationShare share);

  Optional<ConversationShare> findByToken(String token);

  Optional<ConversationShare> findById(UUID id);

  List<ConversationShare> listByConversation(UUID conversationId);
}
