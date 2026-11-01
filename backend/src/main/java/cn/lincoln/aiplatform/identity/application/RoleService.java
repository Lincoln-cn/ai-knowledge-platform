package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.infrastructure.PermissionCatalog;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理（{@code GET /roles} · {@code PUT /roles/:id/permissions} · {@code PUT
 * /roles/:id/data-scope}）。
 *
 * <p>★ 两个维度**分两个端点改**，这是刻意的（{@code 07 §3.2} 末尾）： 勾权限点是"这个岗位能做什么"，调范围是"这个岗位能看到谁的东西" —— 两者语义不同、
 * 决策的人也常常不同。合成一次提交会让"只想调范围"的人不得不把全部权限点重新确认一遍。
 *
 * <p>★ 「角色挂哪些权限点」的真源是<strong>库</strong>（{@code iam_role_permission}），不是 {@code Permissions.of}。
 * 这里只做校验 + 写库；鉴权读的是登录时写进令牌的 {@code authorities}。
 */
@Service
public class RoleService {

  private static final Logger log = LoggerFactory.getLogger(RoleService.class);

  /** 与前端 {@code RoleManage.vue} 的 {@code isAdminRole} 同口径。 */
  private static final String ADMIN_ROLE_CODE = PermissionCatalog.ADMIN_ROLE_CODE;

  private final RoleRepository roles;
  private final UserRepository users;
  private final RolePermissionRepository rolePermissions;

  /**
   * 审计（`#257`）。
   *
   * <p>★ 授权变更必须留痕：这是"谁把看到什么的权利给了谁"，事后追责与等保检查都会问它。审计与写库同事务（见 {@link AuditLogPort#record}）。
   */
  private final AuditLogPort audit;

  public RoleService(
      RoleRepository roles,
      UserRepository users,
      RolePermissionRepository rolePermissions,
      AuditLogPort audit) {
    this.roles = roles;
    this.users = users;
    this.rolePermissions = rolePermissions;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<RoleView> list() {
    List<Role> all = roles.findAll();
    Map<UUID, List<String>> grants =
        rolePermissions.findByRoleIds(all.stream().map(Role::id).toList());
    Map<UUID, Long> memberCounts = new HashMap<>();
    for (Role role : all) {
      memberCounts.put(role.id(), users.countByRoleId(role.id()));
    }

    return all.stream()
        .map(
            role ->
                view(
                    role,
                    grants.getOrDefault(role.id(), List.of()),
                    memberCounts.getOrDefault(role.id(), 0L)))
        .toList();
  }

  /**
   * 改角色的权限点（全量覆盖）。
   *
   * <p>★ 先校验"每个 code 都在目录里"。不校验的话，一个拼错的权限点会被安静地写进库， 而它在鉴权时**永远不会被匹配到** —— 现象是"勾了没反应"，且没有任何报错。
   *
   * <p>★ 拒绝<strong>空列表</strong>：空列表与"该角色从未初始化"在库里无法区分， 会被启动 seed 静默塞回来；而且零权限角色本身就是无效配置。
   *
   * <p>★ 拒绝改 <strong>ADMIN</strong>：ADMIN 的权限点由代码维护（见 {@code PermissionCatalog}）， 不支持在界面上修改。理由与前端
   * {@code RoleManage.vue} 的文案一致，必须一字不差 —— 前端原文展示后端 message，两处各写一套会变成"界面说 A、接口说 B"。
   */
  @Transactional
  public RoleView updatePermissions(UUID roleId, List<String> codes, UUID actorId) {
    Role role = requireRole(roleId);
    rejectAdminRoleChange(role, "权限点");

    Set<String> requested = new LinkedHashSet<>(codes == null ? List.of() : codes);
    if (requested.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "一个角色至少保留一个权限点", "空权限角色无法使用任何功能，请至少勾选一项");
    }

    Set<String> known =
        PermissionCatalog.ITEMS.stream()
            .map(PermissionCatalog.Item::code)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    for (String code : requested) {
      if (!known.contains(code)) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "权限点「" + code + "」不存在", "它可能已从代码中移除，请刷新页面");
      }
    }

    List<String> before = new ArrayList<>(rolePermissions.findByRoleId(roleId));

    // 差分替换（不是先清空再插入）：清空窗口会让并发鉴权全部被拒
    rolePermissions.replaceAll(roleId, requested);

    audit.record(
        AuditAction.GRANT_CHANGE,
        "ROLE",
        roleId.toString(),
        actorId,
        java.util.Map.of(
            "roleCode", role.code(), "from", before, "to", new ArrayList<>(requested)));

    log.info("角色 {} 的权限点已更新：{} 个", role.code(), requested.size());
    return view(role, new ArrayList<>(requested), users.countByRoleId(roleId));
  }

  /**
   * 改角色的数据范围与密级上限。
   *
   * <p>★ 密级上限（clearance）也在这里改，因为它与范围是**同一个谓词构建器的两个输入** （{@code 13 §8.3}）—— 界面上的分组也一样（都在"权限与范围"那一栏）。
   *
   * <p>★ 拒绝改 <strong>ADMIN</strong>：ADMIN 在 {@code 07 §4.2} 的数据面矩阵里就是 {@code all}。
   * 收窄会让管理员看不到系统管理数据，属于自锁，且是不可恢复类风险。 文案与前端 {@code RoleManage.vue} 一字不差（前端原文展示 message）。
   *
   * <p>⚠️ 改动最长 {@code accessTtl}（30 分钟）后才生效：范围和密级随令牌下发。 界面上应当提示这一点，否则管理员会以为"改了没用"。
   */
  @Transactional
  public RoleView updateDataScope(UUID roleId, DataScope scope, String rule) {
    Role role = requireRole(roleId);
    rejectAdminRoleChange(role, "数据范围");
    if (scope == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "数据范围不能为空");
    }
    role.changeDataScope(scope, rule);
    roles.save(role);

    log.info("角色 {} 的数据范围改为 {}（规则：{}）", role.code(), scope, rule);
    return view(role, rolePermissions.findByRoleId(roleId), users.countByRoleId(roleId));
  }

  /** 权限点全量清单（{@code GET /permissions}）。★ 来自代码里的目录，不是查库。 */
  @Transactional(readOnly = true)
  public List<PermissionView> permissions() {
    return PermissionCatalog.ITEMS.stream()
        .map(item -> new PermissionView(item.code(), item.label(), item.module()))
        .toList();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Role requireRole(UUID roleId) {
    return roles
        .findById(roleId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在", "它可能已被删除"));
  }

  /**
   * ADMIN 角色的权限点与数据范围都由代码维护，界面不可改。
   *
   * <p>★ 文案必须与前端 {@code RoleManage.vue} 的提示<strong>一字不差</strong>（那边原文展示后端 message）。 另写一套会变成两处各说一套
   * —— 本项目已有 "label/module 照抄前端 fixture" 的先例。
   */
  private static void rejectAdminRoleChange(Role role, String what) {
    if (!ADMIN_ROLE_CODE.equals(role.code())) {
      return;
    }
    if ("权限点".equals(what)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员角色固定拥有全部权限点，不可裁剪", "避免把唯一的管理员锁在门外");
    }
    throw new BusinessException(
        ErrorCode.BAD_REQUEST, "管理员角色的数据范围固定为全平台，不可修改", "收窄会让管理员看不到系统管理，属于自锁");
  }

  private static RoleView view(Role role, List<String> grants, long memberCount) {
    return new RoleView(
        role.id(),
        role.name(),
        role.code(),
        memberCount,
        List.copyOf(grants),
        role.dataScope(),
        role.dataScopeRule(),
        role.maxClassification(),
        role.labels());
  }
}
