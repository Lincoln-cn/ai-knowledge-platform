package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ConversationShareService;
import cn.lincoln.aiplatform.conversation.application.PublicConversationShareView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开面读取一条会话分享链接（免登录）。
 *
 * <p>放行沿用 {@code SecurityConfig} 的 {@code /api/v1/public/**} 那一条；本类<strong>刻意不带</strong>
 * {@code @PreAuthorize} —— 公开面上没有"动作权限点"可判，正当性由令牌本身回答（存在 / 未撤销 / 未过期）。
 */
@RestController
@RequestMapping("/api/v1/public/shares")
public class PublicShareController {

  private final ConversationShareService shares;

  public PublicShareController(ConversationShareService shares) {
    this.shares = shares;
  }

  @GetMapping("/{token}")
  public ApiResponse<PublicConversationShareView> read(@PathVariable String token) {
    return ApiResponse.ok(shares.resolvePublic(token));
  }
}
