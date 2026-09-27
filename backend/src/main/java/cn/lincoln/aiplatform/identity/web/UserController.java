package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.AuthService;
import cn.lincoln.aiplatform.identity.application.CurrentUserView;
import cn.lincoln.aiplatform.identity.application.UserService;
import cn.lincoln.aiplatform.identity.application.UserView;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点（契约 {@code frontend/src/api/identity.ts}）。
 *
 * <p>★ 权限声明的分工（{@code 08 §4.3.4}）：
 *
 * <ul>
 *   <li>{@code /users/me} 与 {@code /users/me/password} —— <strong>本人即可</strong>，
 *       不声明权限点（没有哪个权限点叫"改我自己的密码"）
 *   <li>{@code /users} 的增删改查 —— {@code system:manage}
 * </ul>
 *
 * <p>★ 这些写端点**必须**带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest}
 * 会在构建期检查，漏一个就红。它盯的正是"新增功能忘配权限 ⇒ 功能隐形"那类病。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final AuthService authService;
  private final UserService userService;

  public UserController(AuthService authService, UserService userService) {
    this.authService = authService;
    this.userService = userService;
  }

  // ────────────────────────── 本人即可 ──────────────────────────

  @GetMapping("/me")
  public ApiResponse<CurrentUserView> me(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(authService.currentUser(UUID.fromString(jwt.getSubject())));
  }

  @PutMapping("/me/password")
  public ApiResponse<Void> changePassword(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ChangePasswordBody body) {
    authService.changePassword(
        UUID.fromString(jwt.getSubject()), body.oldPassword(), body.newPassword());
    return ApiResponse.ok();
  }

  // ────────────────────────── 用户管理 ──────────────────────────

  @GetMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<PageResult<UserView>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID roleId,
      @RequestParam(required = false) UserStatus status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(userService.list(keyword, roleId, status, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<UserView> detail(@PathVariable UUID id) {
    return ApiResponse.ok(userService.get(id));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<UserView> create(@RequestBody @Valid UpsertUserBody body) {
    return ApiResponse.ok(
        userService.create(
            body.username(), body.displayName(), parseUuid(body.roleId(), "角色"), body.password()));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<UserView> update(
      @PathVariable UUID id,
      @RequestBody @Valid UpdateUserBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        userService.update(
            id,
            body.username(),
            body.displayName(),
            parseUuid(body.roleId(), "角色"),
            body.password(),
            UUID.fromString(jwt.getSubject())));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    userService.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  /**
   * 把契约里的字符串 id 转成 UUID。
   *
   * <p>★ 契约把 id 声明为 {@code string}，而"不是合法 UUID"的输入必须变成 <strong>400</strong> 而不是 500 ——
   * 前者是"你传错了"（调用方自己能修），后者是"我崩了"（得来找我）。
   */
  private static UUID parseUuid(String raw, String field) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不是合法的 id：" + raw);
    }
  }

  /** 契约 {@code ChangePasswordBody}。 */
  record ChangePasswordBody(@NotBlank String oldPassword, @NotBlank String newPassword) {}

  /** 契约 {@code UpsertUserBody}（新建时 username 必填）。 */
  record UpsertUserBody(
      @NotBlank String username,
      @NotBlank String displayName,
      @NotBlank String roleId,
      String password) {}

  /**
   * 改用户的请求体。★ 契约里 {@code username} 与 {@code roleId} 都是必填形状。
   *
   * <p><strong>username 不可修改</strong>：前端编辑态会把 username 一起发回来（{@code UserFormDialog}）， 服务端必须比对 ——
   * 不一致返 400。若只是 Jackson 静默忽略，就是"界面允许改、后端不改、且不报错" 的静默失效。与现账号一致时正常通过。
   */
  record UpdateUserBody(
      String username, @NotBlank String displayName, @NotBlank String roleId, String password) {}
}
