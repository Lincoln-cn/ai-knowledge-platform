package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data 实现，同时满足 {@link RoleRepository} 这个 domain 接口。 */
public interface JpaRoleRepository extends JpaRepository<Role, UUID>, RoleRepository {}
