package cn.lincoln.aiplatform.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * 当前用户视图（契约 {@code CurrentUser}，{@code frontend/src/api/identity.ts:28-37}）。
 *
 * <p>★ 契约里 {@code dataScope} 是**可选**字段，第 3 周随角色与数据范围一起落。契约写明"未下发时前端按权限点降级"。
 *
 * @param permissions 权限点。★ 来源是 {@code iam_role_permission}（库），与令牌里那份是同一处真源 —— 不是 {@code
 *     Permissions.of} 初始映射。否则界面会显示一套、{@code @PreAuthorize} 认另一套
 */
public record CurrentUserView(
    UUID id, String username, String displayName, String roleName, List<String> permissions) {}
