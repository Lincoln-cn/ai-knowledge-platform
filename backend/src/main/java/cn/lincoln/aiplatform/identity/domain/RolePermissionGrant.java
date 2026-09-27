package cn.lincoln.aiplatform.identity.domain;

import java.util.UUID;

/**
 * 一条「角色 → 权限点」授权。
 *
 * <p>★ 为什么不直接用 {@code Map.Entry} 或两个参数到处传：授权是有身份的一条记录（它有 {@code created_at}，将来可能还有授予人/理由），
 * 用命名类型能让"这是一条授权"在签名里一眼可见，也让 {@link RolePermissionRepository} 的返回值自解释。
 *
 * @param roleId 角色 id
 * @param permissionCode 权限点 code（与 {@link Permissions} 的常量同口径）
 */
public record RolePermissionGrant(UUID roleId, String permissionCode) {}
