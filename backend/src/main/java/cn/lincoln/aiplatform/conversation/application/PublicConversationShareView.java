package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.time.Instant;
import java.util.List;

/**
 * 公开面一条分享链接能读到的东西。
 *
 * <p>{@code messages} 复用 {@link ChatMessageView}：公开页与登录页看到的是<strong>同一个线上形状</strong>，
 * 免得两边对同一字段各写一套映射（那样 {@code refs} / {@code tokens} 的可空语义迟早会分叉）。
 */
public record PublicConversationShareView(
    String title,
    Instant createdAt,
    ClassificationLevel maxClassification,
    Instant expiresAt,
    List<ChatMessageView> messages) {}
