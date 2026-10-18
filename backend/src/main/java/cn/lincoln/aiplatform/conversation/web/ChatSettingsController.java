package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatSettingsService;
import cn.lincoln.aiplatform.conversation.application.ChatSettingsView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「对话设置」的 web 入口（平台级 · 契约 {@code frontend/src/api/chatSettings.ts}）。
 *
 * <h3>为什么需要它（{@code #181} 之后的设计缺口）</h3>
 *
 * <p>在此之前，「通用问答」（{@code appId} 为空）**没有任何配置入口**： 模型由 {@code pickChatModel()} 随便挑一个、流式参数只能改 yaml 并重启。
 * 而 CPU + 小模型上"长回答被 60s 判吊死"这件事，用户在页面上**无处可调**。
 *
 * <h3>权限</h3>
 *
 * <p>改的是**平台级**默认行为（影响所有通用问答），所以用 {@code system:manage} 而不是 {@code
 * conversation:view}：后者的持有者能看图自己范围内的会话，不等于能改全局默认。 ★ 读用 {@code
 * conversation:view}：界面上要显示"当前生效值"，普通用户该看得到。
 */
@RestController
@RequestMapping("/api/v1/chat-settings")
public class ChatSettingsController {

  private final ChatSettingsService settings;

  public ChatSettingsController(ChatSettingsService settings) {
    this.settings = settings;
  }

  /**
   * @return 当前通用会话设置（界面显示"当前生效值"用）
   */
  @GetMapping
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<ChatSettingsView> get() {
    return ApiResponse.ok(settings.get());
  }

  /**
   * 整行覆盖更新。
   *
   * @param body 新设置
   * @param jwt 令牌（操作人取 {@code sub}）
   * @return 落库后的设置
   */
  @PutMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<ChatSettingsView> update(
      @RequestBody ChatSettingsService.ChatSettingsCommand body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(settings.update(body, UUID.fromString(jwt.getSubject())));
  }
}
