package cn.lincoln.aiplatform.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 角色仓储。接口在 {@code domain}，实现由 {@code infrastructure} 提供。 */
public interface RoleRepository {

  Optional<Role> findById(UUID id);

  /** 全量。★ 权限点目录的同步要遍历所有角色（角色总数是个位数，不需要分页）。 */
  List<Role> findAll();
}
