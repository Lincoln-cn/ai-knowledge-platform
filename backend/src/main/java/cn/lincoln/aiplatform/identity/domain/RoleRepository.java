package cn.lincoln.aiplatform.identity.domain;

import java.util.Optional;
import java.util.UUID;

/** 角色仓储。接口在 {@code domain}，实现由 {@code infrastructure} 提供。 */
public interface RoleRepository {

  Optional<Role> findById(UUID id);
}
