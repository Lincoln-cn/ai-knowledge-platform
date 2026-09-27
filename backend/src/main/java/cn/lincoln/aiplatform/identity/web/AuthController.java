package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.AuthService;
import cn.lincoln.aiplatform.identity.application.CaptchaView;
import cn.lincoln.aiplatform.identity.application.LoginResult;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（契约 {@code frontend/src/api/identity.ts}）。
 *
 * <p>★ 这四个路径在 {@code SecurityConfig} 里是 {@code permitAll} 的（{@code /logout} 除外）—— 其中 {@code
 * /refresh} 必须免认证，否则"刷新令牌"这件事在逻辑上就是循环的 （令牌过期了才能刷新，可刷新又要求令牌有效）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /**
   * 取一张图形验证码（等保要求登录带验证码）。
   *
   * <p>★ 免认证是必然的 —— 还没登录的人才能需要它。
   */
  @GetMapping("/captcha")
  public ApiResponse<CaptchaView> captcha() {
    return ApiResponse.ok(authService.issueCaptcha());
  }

  @PostMapping("/login")
  public ApiResponse<LoginResult> login(@RequestBody @Valid LoginBody body) {
    return ApiResponse.ok(
        authService.login(body.username(), body.password(), body.captchaId(), body.captchaCode()));
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

  /**
   * 契约 {@code LoginRequest}。
   *
   * <p>★ 验证码两个字段**不加 {@code @NotBlank}**：是否强制由 {@code app.security.captcha.enabled}
   * 决定（等保环境开启，压测/自动化可关）。 把"必填"写死在注解上，就等于把这个开关废掉了。
   */
  record LoginBody(
      @NotBlank String username, @NotBlank String password, String captchaId, String captchaCode) {}

  /** 刷新的请求体：前端在 {@code lib/http.ts:55} 直接 POST {@code {refreshToken}}。 */
  record RefreshBody(@NotBlank String refreshToken) {}
}
