package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.RolePermissionGrant;
import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link RolePermissionRepository} 的 JdbcTemplate 实现。
 *
 * <p>★ 为什么是 JdbcTemplate 而不是 JPA：{@code iam_role_permission} 只有一个复合主键 + 两条外键， 没有聚合行为、没有懒加载需求。给它建一个
 * {@code @Entity} 只会让"一条授权"变成一个要被 Hibernate 跟踪的对象， 而这里真正需要的只是「查集合 / 差分替换」两件事。
 *
 * <p>★ 全工程对 {@code iam_role_permission} 的 SQL 都收在这里（见接口注释）。 {@code RoleService}、{@code
 * AuthService}、{@code PermissionCatalog} 一律走接口，不许再写一条。
 */
@Component
public class JdbcRolePermissionRepository implements RolePermissionRepository {

  private final JdbcTemplate jdbc;

  public JdbcRolePermissionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> findByRoleId(UUID roleId) {
    return jdbc.queryForList(
        "select permission_code from iam_role_permission where role_id = ? order by"
            + " permission_code",
        String.class,
        roleId);
  }

  @Override
  public Map<UUID, List<String>> findByRoleIds(Collection<UUID> roleIds) {
    if (roleIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<String>> byRole = new HashMap<>();
    // 角色个位数，一条 in 查询就够；不为 5 个角色打 5 次
    for (RolePermissionGrant grant : findAll()) {
      if (roleIds.contains(grant.roleId())) {
        byRole.computeIfAbsent(grant.roleId(), k -> new ArrayList<>()).add(grant.permissionCode());
      }
    }
    return byRole;
  }

  @Override
  public List<RolePermissionGrant> findAll() {
    return jdbc.query(
        "select role_id, permission_code from iam_role_permission order by role_id,"
            + " permission_code",
        (rs, rowNum) ->
            new RolePermissionGrant(
                rs.getObject("role_id", UUID.class), rs.getString("permission_code")));
  }

  /** 差分替换。★ 决不做「先 delete 再 insert」——理由见接口注释：清空窗口会让并发鉴权全部被拒。 */
  @Override
  public void replaceAll(UUID roleId, Collection<String> permissionCodes) {
    Set<String> requested = new LinkedHashSet<>(permissionCodes);
    Set<String> current = new HashSet<>(findByRoleId(roleId));

    for (String code : requested) {
      if (!current.contains(code)) {
        jdbc.update(
            "insert into iam_role_permission (role_id, permission_code, created_at)"
                + " values (?, ?, now()) on conflict do nothing",
            roleId,
            code);
      }
    }
    for (String code : current) {
      if (!requested.contains(code)) {
        jdbc.update(
            "delete from iam_role_permission where role_id = ? and permission_code = ?",
            roleId,
            code);
      }
    }
  }

  @Override
  public boolean hasAnyGrant(UUID roleId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from iam_role_permission where role_id = ?", Integer.class, roleId);
    return n != null && n > 0;
  }
}
