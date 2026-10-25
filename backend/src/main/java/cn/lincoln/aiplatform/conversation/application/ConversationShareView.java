package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.ConversationShare;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条分享链接的管理面形状（契约 {@code ConversationShare}）。
 *
 * <p>{@code url} 由服务端拼：前端不需要知道路由怎么长，换路由也不会让已发出去的链接换形状（已发出的链接仍是旧路由，这里只影响新生成的）。
 *
 * @param token 链接里的那一串
 * @param url 可直接复制的站内链接
 * @param maxClassification 创建时冻结的密级上限
 * @param expiresAt 有效期；{@code null} = 长期有效
 * @param revokedAt 撤销时间；{@code null} = 未撤销
 */
public record ConversationShareView(
    UUID id,
    String token,
    String url,
    String scope,
    UUID messageId,
    ClassificationLevel maxClassification,
    Instant expiresAt,
    Instant revokedAt,
    Instant createdAt) {

  /** 站内分享路由；与前端 {@code /share/:token} 一致。 */
  public static final String PUBLIC_PATH_PREFIX = "/#/share/";

  public static ConversationShareView from(ConversationShare share) {
    return new ConversationShareView(
        share.id(),
        share.token(),
        PUBLIC_PATH_PREFIX + share.token(),
        share.scope(),
        share.messageId(),
        share.maxClassification(),
        share.expiresAt(),
        share.revokedAt(),
        share.createdAt());
  }
}
