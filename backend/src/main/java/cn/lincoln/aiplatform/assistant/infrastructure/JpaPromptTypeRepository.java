package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 提示词类型的 Spring Data 仓储（不放进 domain，避免领域依赖 JPA）。 */
public interface JpaPromptTypeRepository extends JpaRepository<PromptType, UUID> {

  boolean existsByTypeNameIgnoreCase(String typeName);

  boolean existsByTypeNameIgnoreCaseAndIdNot(String typeName, UUID id);

  List<PromptType> findAllByOrderByCreatedAtAsc();
}
