package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.AuthService;
import cn.lincoln.aiplatform.identity.application.CurrentUserView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端点。第 2 周只有 {@code /users/me} —— 完整的用户 CRUD 是第 3 周的事。
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
}
