package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 服务商仓储（接口在 domain，实现放 infrastructure）。 */
public interface ModelProviderRepository {

  Optional<ModelProvider> findById(UUID id);

  List<ModelProvider> findAll();

  ModelProvider save(ModelProvider provider);

  void delete(ModelProvider provider);

  /** 快速绑定幂等用：同一类型 + 同一地址只应有一条。 */
  Optional<ModelProvider> findByKindAndApiBase(ProviderKind kind, String apiBase);
}
