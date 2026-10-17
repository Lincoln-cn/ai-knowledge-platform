package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatMessageView;
import cn.lincoln.aiplatform.conversation.application.MessageFeedbackService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息自己的资源端点（契约前缀 {@code /messages}）。
 *
 * <p>★ 为什么单独一个控制器而不是塞进 {@code ChatController}：契约里这两族的前缀就是 <strong>两条</strong>（会话族的 {@code
 * /conversations/{id}/messages} 与消息自己的 {@code /messages/{id}/…}）。 塞进前者会让后端的路由与契约的写法对不上 —— 而 {@code
 * ContractDriftTest} 是<strong>按前缀</strong> 登记欠账的，混住会让"这个前缀还欠多少"变得说不清。
 *
 * <p>★ 本批只落<strong>一条</strong>（赞踩）。契约里 {@code /messages} 下若还有别的（编辑 / 删除 / 重生成）， 它们各自等到自己那一批 ——
 * 一次只落一条，比"顺手把一族写完"更容易核对。
 */
@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

  private final MessageFeedbackService feedback;

  public MessageController(MessageFeedbackService feedback) {
    this.feedback = feedback;
  }

  /**
   * 赞 / 踩 / 取消（契约 {@code submitMessageFeedback}）。
   *
   * <p>★ 权限点用 {@code conversation:view}：评价一条回答的前提是<strong>看得见这个会话</strong>，
   * 而本仓没有（也不该有）一个"能不能评价"的独立权限点 —— 那会把"看得见"与"能操作"再分叉一次。 真正的归属收窄在服务层（看不见 ⇒ 404）。
   *
   * <p>★ {@code type} 允许为 {@code null}（= 取消）：契约原话「赞踩互斥且<b>可撤销</b>：type 传 null 表示取消」， 所以 {@link
   * RequestBody} 是必填的（区分"传了空对象"与"没传 body"），但<b>字段值</b>允许 {@code null}。
   *
   * @param messageId 目标消息 id
   * @param body 只有 {@code type} 一个字段
   * @param jwt 当前登录用户
   * @return 落库后的消息（含新的 {@code feedback}）—— 前端就地更新那一格
   */
  @PostMapping("/{messageId}/feedback")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<ChatMessageView> submitFeedback(
      @PathVariable UUID messageId,
      @RequestBody FeedbackBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        ChatMessageView.of(
            feedback.rate(messageId, subjectOf(jwt), body == null ? null : body.type())));
  }

  /**
   * 赞踩的请求体。
   *
   * @param type {@code 'UP'} / {@code 'DOWN'} / {@code null}（取消）。★ 用 {@code String} 而不是枚举：
   *     非法取值要能变成一条**带合法清单的 400**（{@code MessageFeedback#parse} 的异常消息）， 而 Jackson 绑枚举时抛的是 {@code
   *     HttpMessageNotReadableException}，那条的原因里没有"该填什么"。
   */
  public record FeedbackBody(String type) {}

  /** 当前用户 id —— 与 {@code ChatController} 同一条取法（{@code JWT} 的 {@code sub}）。 */
  private static UUID subjectOf(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
