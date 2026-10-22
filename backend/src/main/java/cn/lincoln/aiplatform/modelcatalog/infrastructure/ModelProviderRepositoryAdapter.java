package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** 把 Spring Data 的服务商仓储适配成 domain 的 {@link ModelProviderRepository}。 */
@Component
public class ModelProviderRepositoryAdapter implements ModelProviderRepository {

  private final JpaModelProviderRepository jpa;

  public ModelProviderRepositoryAdapter(JpaModelProviderRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<ModelProvider> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<ModelProvider> findAll() {
    return jpa.findAll(Sort.by(Sort.Direction.ASC, "name"));
  }

  @Override
  public ModelProvider save(ModelProvider provider) {
    return jpa.save(provider);
  }

  @Override
  public void delete(ModelProvider provider) {
    jpa.delete(provider);
  }

  @Override
  public Optional<ModelProvider> findByKindAndApiBase(ProviderKind kind, String apiBase) {
    return jpa.findByKindAndApiBase(kind, apiBase);
  }
}
