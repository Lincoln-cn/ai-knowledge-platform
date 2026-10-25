package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ConversationShareService;
import cn.lincoln.aiplatform.conversation.application.ConversationShareView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话分享的管理面三条（创建 / 列出 / 撤销）。
 *
 * <p>权限点用 {@code conversation:share} —— 能分享是"这个动作"的权限，不是"能看见会话"的权限； 两个点分开，才能在角色上表达"能读但不能外发"。
 * 归属判定在服务层，且失败一律 404（不通过错误码暴露别人的会话 id 是否存在）。
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/shares")
public class ConversationShareController {

  private final ConversationShareService shares;

  public ConversationShareController(ConversationShareService shares) {
    this.shares = shares;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('conversation:share')")
  public ApiResponse<List<ConversationShareView>> list(
      @PathVariable UUID conversationId, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(shares.list(conversationId, actor(jwt)));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('conversation:share')")
  public ApiResponse<ConversationShareView> create(
      @PathVariable UUID conversationId,
      @RequestBody CreateShareBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        shares.create(
            conversationId, body.scope(), body.messageId(), body.expiresAt(), actor(jwt)));
  }

  @DeleteMapping("/{token}")
  @PreAuthorize("hasAuthority('conversation:share')")
  public ApiResponse<ConversationShareView> revoke(
      @PathVariable UUID conversationId,
      @PathVariable String token,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(shares.revoke(conversationId, token, actor(jwt)));
  }

  private static UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  /** 请求体；{@code expiresAt} 缺省 = 长期有效。 */
  public record CreateShareBody(String scope, UUID messageId, Instant expiresAt) {}
}
