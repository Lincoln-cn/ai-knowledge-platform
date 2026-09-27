package cn.lincoln.aiplatform.identity.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「角色 → 权限点」授权仓储（接口在 {@code domain}，JdbcTemplate 实现在 {@code infrastructure}）。
 *
 * <p>★ <strong>这张表是鉴权的真源</strong>（第 3 周第二段）：管理员在角色管理页勾什么， {@code @PreAuthorize} 就认什么。此前 {@code
 * Permissions.of(roleCode)} 被当成真源， 界面上的勾选<em>能存能回显但鉴权不理会</em> —— 那正是本项目最忌讳的静默失效。
 *
 * <p>★ {@link Permissions#of} 降级为<strong>初始映射</strong>：只用于启动 seed 与 ADMIN 的代码维护集， 不再参与登录/刷新/ {@code
 * /users/me} 的授权计算。
 *
 * <p>★ 全工程对 {@code iam_role_permission} 的 SQL 只允许出现在实现类里 —— 散开写会出现 "RoleService
 * 删了、PermissionCatalog 还在按旧语义清" 这种半截子改动。
 */
public interface RolePermissionRepository {

  /** 某角色当前持有的权限点 code（登录签发 / {@code /users/me} 用）。 */
  List<String> findByRoleId(UUID roleId);

  /** 批量查多个角色的授权，避免列表页 N+1。 */
  Map<UUID, List<String>> findByRoleIds(Collection<UUID> roleIds);

  /** 全量「角色-授权对」（启动同步的孤儿清理与角色列表页用）。 */
  List<RolePermissionGrant> findAll();

  /**
   * 全量替换某角色的授权（差分实现：该加的加、该删的删）。
   *
   * <p>★ <strong>不先清空再插入</strong>：清空重插会在事务窗口内让角色持有 0 个权限点， 并发鉴权请求落在窗口里会全部被拒 —— 一次"改权限"制造一批莫名其妙的
   * 403。
   */
  void replaceAll(UUID roleId, Collection<String> permissionCodes);

  /** 该角色是否已有任何授权行。★ 区分「从未初始化」（可 seed）与「已初始化」（以库为准）的唯一依据。 */
  boolean hasAnyGrant(UUID roleId);
}
