package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AssistantApiKey;
import cn.lincoln.aiplatform.assistant.domain.AssistantApiKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 把 Spring Data 的 API Key 仓储适配成领域端口。 */
@Component
public class AssistantApiKeyRepositoryAdapter implements AssistantApiKeyRepository {

  private final JpaAssistantApiKeyRepository jpa;

  public AssistantApiKeyRepositoryAdapter(JpaAssistantApiKeyRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public List<AssistantApiKey> list(UUID assistantId) {
    return jpa.findByAssistantIdOrderByCreatedAtDesc(assistantId);
  }

  @Override
  public AssistantApiKey add(UUID assistantId, String keyValue) {
    // ★ 主键在这里生成：库侧没有 gen_random_uuid() 依赖，与收藏表同口径
    return jpa.save(
        new AssistantApiKey(UUID.randomUUID(), assistantId, keyValue, true, Instant.now()));
  }

  @Override
  public Optional<AssistantApiKey> find(UUID assistantId, UUID keyId) {
    return jpa.findByIdAndAssistantId(keyId, assistantId);
  }

  @Override
  public boolean setEnabled(UUID assistantId, UUID keyId, boolean enabled) {
    Optional<AssistantApiKey> found = jpa.findByIdAndAssistantId(keyId, assistantId);
    if (found.isEmpty()) {
      return false;
    }
    AssistantApiKey key = found.get();
    key.setEnabled(enabled);
    jpa.save(key);
    return true;
  }
}
