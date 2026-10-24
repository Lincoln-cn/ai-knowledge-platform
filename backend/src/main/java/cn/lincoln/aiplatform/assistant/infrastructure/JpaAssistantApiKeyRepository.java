package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AssistantApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code as_assistant_api_key} 的 Spring Data 接口。 */
public interface JpaAssistantApiKeyRepository extends JpaRepository<AssistantApiKey, UUID> {

  List<AssistantApiKey> findByAssistantIdOrderByCreatedAtDesc(UUID assistantId);

  Optional<AssistantApiKey> findByIdAndAssistantId(UUID id, UUID assistantId);
}
