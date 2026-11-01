package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.RoleService;
import cn.lincoln.aiplatform.identity.application.RoleView;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色端点（契约 {@code frontend/src/api/identity.ts} 的 {@code listRoles} / {@code updateRolePermissions}
 * / {@code updateRoleDataScope}）。
 *
 * <p>全部归 {@code system:manage}（{@code 08 §4.3.4}：改角色的能力范围是系统管理的事）。
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

  private final RoleService roleService;

  public RoleController(RoleService roleService) {
    this.roleService = roleService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<List<RoleView>> list() {
    return ApiResponse.ok(roleService.list());
  }

  /** 全量覆盖权限点（契约的语义就是"提交一个完整列表"，不是增量）。 */
  @PutMapping("/{id}/permissions")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<RoleView> updatePermissions(
      @PathVariable UUID id, @RequestBody PermissionsBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        roleService.updatePermissions(id, body.permissions(), UUID.fromString(jwt.getSubject())));
  }

  /** ★ 与权限点**分开**的端点：改范围不该让人重新确认一遍权限点（{@code 07 §3.2}）。 */
  @PutMapping("/{id}/data-scope")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<RoleView> updateDataScope(
      @PathVariable UUID id, @RequestBody @Valid DataScopeBody body) {
    return ApiResponse.ok(roleService.updateDataScope(id, body.dataScope(), body.dataScopeRule()));
  }

  record PermissionsBody(List<String> permissions) {}

  /**
   * ★ {@code dataScope} 是<strong>枚举</strong>，用 {@code @NotNull} 而不是 {@code @NotBlank}：
   * {@code @NotBlank} 只认 {@code CharSequence}，标在枚举上会直接抛 {@code UnexpectedTypeException}（500），
   * 而不是"你没传 dataScope"（400）。枚举缺省校验交给 {@code RoleService} 的空值判断。
   */
  record DataScopeBody(@NotNull DataScope dataScope, String dataScopeRule) {}
}
