package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.AuthService;
import cn.lincoln.aiplatform.identity.application.LoginResult;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（契约 {@code frontend/src/api/identity.ts}）。
 *
 * <p>★ 这三个路径在 {@code SecurityConfig} 里是 {@code permitAll} 的 —— 其中 {@code /refresh}
 * 必须免认证，否则"刷新令牌"这件事在逻辑上就是循环的 （令牌过期了才能刷新，可刷新又要求令牌有效）。
 *
 * <p>★ {@code /logout} 走的是 {@code anyRequest().authenticated()}，没有额外声明权限点 —— 它属于 {@code 08 §4.3.2}
 * 白名单里的 {@code /auth/**}（登录/登出是本人即可）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ApiResponse<LoginResult> login(@RequestBody @Valid LoginBody body) {
    return ApiResponse.ok(authService.login(body.username(), body.password()));
  }

  @PostMapping("/refresh")
  public ApiResponse<LoginResult> refresh(@RequestBody @Valid RefreshBody body) {
    return ApiResponse.ok(authService.refresh(body.refreshToken()));
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout(@AuthenticationPrincipal Jwt jwt) {
    authService.logout(UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  /** 契约 {@code LoginRequest}。 */
  record LoginBody(@NotBlank String username, @NotBlank String password) {}

  /** 刷新的请求体：前端在 {@code lib/http.ts:55} 直接 POST {@code {refreshToken}}。 */
  record RefreshBody(@NotBlank String refreshToken) {}
}
