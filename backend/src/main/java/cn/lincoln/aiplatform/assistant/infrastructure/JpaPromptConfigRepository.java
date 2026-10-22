package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 提示词模板的 Spring Data 仓储（不放进 domain，避免领域依赖 JPA）。 */
public interface JpaPromptConfigRepository extends JpaRepository<PromptConfig, UUID> {

  List<PromptConfig> findAllByOrderByCreatedAtDesc();

  long countByTypeId(UUID typeId);
}
