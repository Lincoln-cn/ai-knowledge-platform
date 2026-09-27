package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.AuthService;
import cn.lincoln.aiplatform.identity.application.CurrentUserView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点。第 2 周只有 {@code /users/me} 与改密 —— 完整的用户 CRUD 是第 3 周的事。
 *
 * <p>★ 当前用户从 {@code @AuthenticationPrincipal Jwt} 取（官方方式）， 不用 {@code SecurityContextHolder}、也不自研
 * ThreadLocal 用户上下文（{@code 08 §4.2.2}）。 用户的 id 就是令牌的 {@code sub} 声明。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final AuthService authService;

  public UserController(AuthService authService) {
    this.authService = authService;
  }

  /** 契约 {@code getCurrentUser}：{@code GET /users/me}（{@code identity.ts:81}）。 */
  @GetMapping("/me")
  public ApiResponse<CurrentUserView> me(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(authService.currentUser(UUID.fromString(jwt.getSubject())));
  }

  /**
   * 改密（契约 {@code changePassword}：{@code PUT /users/me/password}）。
   *
   * <p>★ 没有 {@code @PreAuthorize} 是**刻意的**，而不是漏写：它落在 {@code 08 §4.3.2} 白名单的 {@code
   * /users/me/**}（本人即可）里 —— 没有哪个权限点叫"修改我自己的密码"。 闸门 {@code AuthorizationDeclarationTest}
   * 只要求<strong>写端点</strong>声明权限， 而这个白名单目前是按类名放过的（{@code UserController}？不 —— 见下面那条注释）。
   */
  @PutMapping("/me/password")
  public ApiResponse<Void> changePassword(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ChangePasswordBody body) {
    authService.changePassword(
        UUID.fromString(jwt.getSubject()), body.oldPassword(), body.newPassword());
    return ApiResponse.ok();
  }

  /** 契约 {@code ChangePasswordBody}（{@code identity.ts:83-86}）。 */
  record ChangePasswordBody(@NotBlank String oldPassword, @NotBlank String newPassword) {}
}
