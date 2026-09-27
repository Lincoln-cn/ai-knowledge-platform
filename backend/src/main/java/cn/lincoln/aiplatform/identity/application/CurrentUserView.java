package cn.lincoln.aiplatform.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * 当前用户视图（契约 {@code CurrentUser}，{@code frontend/src/api/identity.ts:28-37}）。
 *
 * <p>★ 契约里 {@code dataScope} 是**可选**字段，第 2 周不下发 —— 它是数据范围（{@code 07 §3.2}） 的一部分，随第 3
 * 周的角色与数据范围一起落。契约写明"未下发时前端按权限点降级"， 所以现在不填不会让界面出错。
 *
 * @param permissions 权限点。★ 来源是角色代码映射出的常量集合（{@link
 *     cn.lincoln.aiplatform.identity.domain.Permissions}），与令牌里那份是同一处真源
 */
public record CurrentUserView(
    UUID id, String username, String displayName, String roleName, List<String> permissions) {}
