package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.PermissionView;
import cn.lincoln.aiplatform.identity.application.RoleService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限点清单（契约 {@code listPermissions}：{@code GET /permissions}）。
 *
 * <p>★ 它住在 {@code /permissions} 而不是 {@code /roles/permissions}：权限点是
 * <strong>平台级目录</strong>（新增功能时它自己多一项），不属于某个角色的子资源。 放在角色下面会让"权限点一共有哪些"看起来像"这个角色的权限"，而那正是要避免的混淆。
 *
 * <p>★ 内容是**代码常量**，不是查库：真源是 {@code Permissions}， 库里的 {@code iam_permission} 只是它的镜像（供角色管理页渲染）。
 * 所以这个端点在库还没同步时也能正确应答 —— 它不依赖任何数据。
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

  private final RoleService roleService;

  public PermissionController(RoleService roleService) {
    this.roleService = roleService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<List<PermissionView>> list() {
    return ApiResponse.ok(roleService.permissions());
  }
}
