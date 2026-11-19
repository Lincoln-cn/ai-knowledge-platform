package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简单应用调试端点（{@code #184}）—— {@code POST /api/v1/assistants/{id}/debug}。
 *
 * <h3>★★ 为什么它住在 {@code conversation} 而不是 {@code assistant}</h3>
 *
 * <p>调试的<strong>执行者</strong>是问答链路：要读应用绑定、检索、拼提示词、调模型端口 —— 这些都在 {@code conversation}。 而 {@code
 * assistant} 模块不依赖 {@code conversation}（依赖矩阵 {@code 01 §三.2}），也不该为了一个"试跑一次"的端点 反向依赖问答链路。URL
 * 归应用（{@code /assistants/...}）是产品语义，类归链路是依赖语义，两者不必同一个包。
 *
 * <p>★ 它<strong>不</strong>写 {@code cv_conversation} / {@code cv_message}（{@code 02 FR-02-20-5} /
 * {@code BR-04-09}）， 这一点由 {@link ChatService#debugTurn} 的整条路径保证（没有一次 prepare / flush / finish）， 判据见
 * {@code AssistantDebugHttpTest} 的"前后行数不变"。
 *
 * <h3>★ 权限点</h3>
 *
 * <p>用 {@code assistant:view}：调试是"看这个应用配置会答成什么样"，与读取应用配置同一档； 它不是发布、也不是改配置。
 */
@RestController
@RequestMapping("/api/v1/assistants")
public class DebugChatController {

  private final ChatService chat;

  public DebugChatController(ChatService chat) {
    this.chat = chat;
  }

  /**
   * 用应用当前已保存的配置跑一轮问答，返回答案 / 引用 / 耗时 / 用量，<strong>不产生正式会话记录</strong>。
   *
   * <p>★ 读的是<strong>已保存</strong>的配置（不是请求体里的草稿）：前端点「发送」前会先保存草稿（配置页的既有行为）， 于是"调试看到的就是线上会跑的"这条承诺才成立。
   */
  @PostMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/debug")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<ChatService.DebugAnswer> debug(
      @PathVariable UUID id, @RequestBody DebugBody body) {
    return ApiResponse.ok(chat.debugTurn(id, body == null ? null : body.question()));
  }

  /**
   * 对比调试：同一个问题跑两次 —— 已保存的角色设定 vs 请求里带的那一版。
   *
   * <h3>★ 与「请求体里收配置」的区别（{@code BR-02-08}）</h3>
   *
   * <p>那条禁令防的是「调试用 A、线上用 B」。这里<strong>两份都跑、两份都返回</strong>，并且响应里明确分成 {@code saved} 与 {@code draft}
   * 两个字段；请求体那份不落库、不影响任何后续轮。它是对照项，不是配置来源。
   *
   * <p>权限同上（{@code assistant:view}）：它只是"多跑一次给配置者看"，没有写任何东西。
   */
  @PostMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/debug/compare")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<ChatService.DebugComparison> compare(
      @PathVariable UUID id, @RequestBody CompareBody body) {
    return ApiResponse.ok(
        chat.debugCompare(
            id,
            body == null ? null : body.question(),
            body == null ? null : body.draftRolePrompt()));
  }

  /**
   * 调试请求体。
   *
   * <p>★ 只有 {@code question}：模型 / 知识库 / 角色 / 检索参数全部来自应用配置 —— 请求体里再收一份， 就等于允许"调试时用 A 配置、线上用 B 配置"，
   * 而那正是 {@code BR-02-08} 要防的。
   */
  public record DebugBody(String question) {}

  /**
   * 对比调试的请求体。
   *
   * @param question 同一个问题（两次都用它）
   * @param draftRolePrompt 表单里还没保存的角色设定；为 null / 空白时只跑一次并回 {@code draftRan = false}
   */
  public record CompareBody(String question, String draftRolePrompt) {}
}
