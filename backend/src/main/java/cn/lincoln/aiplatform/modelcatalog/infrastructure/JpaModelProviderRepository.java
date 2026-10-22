package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 服务商的 Spring Data 仓储（与 {@link JpaModelRepository} 同一条：框架类型不进 domain）。 */
public interface JpaModelProviderRepository extends JpaRepository<ModelProvider, UUID> {

  Optional<ModelProvider> findByKindAndApiBase(ProviderKind kind, String apiBase);
}
