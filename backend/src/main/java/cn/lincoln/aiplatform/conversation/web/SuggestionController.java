package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.SuggestionService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐追问（「猜你想问」）的生成端点。
 *
 * <h3>为什么是独立的一次请求，而不是塞进回答那一轮</h3>
 *
 * <p>契约里 {@code artifact}（含 suggestions）必须落在 {@code delta*} 之后、{@code done} 之前。 若在那一轮里顺手生成，用户会**等完
 * 60 秒回答、再等 10~20 秒才看到"完成"**（1.5B 的速度）。 追问是锦上添花，不该拖慢主回答 ⇒ 前端在 {@code done} 之后另发一次。
 *
 * <p>★ 单开一个控制器而不是往 {@code ChatController} 里塞：那个类已经有 9 个依赖的长构造器， 再加一个会变成"什么都在里面"的入口类；而这条链路的职责很窄（生成 3
 * 个短问题 + 落库）。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class SuggestionController {

  private final SuggestionService suggestions;

  public SuggestionController(SuggestionService suggestions) {
    this.suggestions = suggestions;
  }

  /**
   * 为一条**已生成完成**的助手消息生成推荐追问。
   *
   * <p>★ 幂等：已有存量时直接返回存量，不重复调用模型（前端可能因为重试/切页重复请求）。
   *
   * <p>★ 失败返回空数组而不是错误：追问生成不出来时界面上只是不显示 chips， 而用户刚刚拿到一条正常回答 —— 不该在这里弹一个错误。
   *
   * @param messageId 助手消息 id
   * @return 追问列表（生成失败 ⇒ 空）
   */
  @PostMapping("/{messageId}/suggestions")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<List<String>> suggestions(@PathVariable UUID messageId) {
    return ApiResponse.ok(suggestions.generate(messageId));
  }
}
