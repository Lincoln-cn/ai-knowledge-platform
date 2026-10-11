package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data 实现，同时满足 {@link RoleRepository} 这个 domain 接口。 */
public interface JpaRoleRepository extends JpaRepository<Role, UUID>, RoleRepository {

  /**
   * 按角色 code 找（{@code GrantSubjectAdapter} 用它校验"这个共享主体存在吗"）。
   *
   * <p>★ 共享主体里的 {@code ROLE} 存的是 <strong>code 而不是 uuid</strong>（{@code 07 §8.1}：
   * 角色代码在审计与共享里更可读、也不随重建角色变化）⇒ 校验必须按 code 查，否则"共享给某角色"永远命中不了。
   */
  Optional<Role> findByCode(String code);
}
