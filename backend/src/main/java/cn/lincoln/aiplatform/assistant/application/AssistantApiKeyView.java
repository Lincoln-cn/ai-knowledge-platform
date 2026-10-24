package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.AssistantApiKey;
import java.time.Instant;
import java.util.UUID;

/**
 * API Key 的管理视图（契约 {@code ApiKeyItem}）。
 *
 * <p>★ {@code key} 是明文：这份视图只在 {@code assistant:edit} 后面下发，管理页要能复制它。 与模型服务商的 {@code
 * ModelProviderView} 刻意不同 —— 那是上游密钥，永远不回传。
 */
public record AssistantApiKeyView(
    UUID id, String key, boolean enabled, Instant createdAt, Instant lastUsedAt) {

  public static AssistantApiKeyView from(AssistantApiKey entity) {
    return new AssistantApiKeyView(
        entity.id(), entity.keyValue(), entity.enabled(), entity.createdAt(), entity.lastUsedAt());
  }
}
