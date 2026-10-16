package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatMessageView;
import cn.lincoln.aiplatform.conversation.application.PublicChatService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开面（免登录）的两个 HTTP 端点：{@code GET /api/v1/public/{token}}（应用上下文）与 {@code POST
 * /api/v1/public/{token}/messages/{messageId}/stop}（停止生成）。
 *
 * <h3>★★ 为什么这里<strong>只有两条</strong>（第 17 周 · Lead 裁决 ① + {@code #143} 前半）</h3>
 *
 * <p>本批一度在这里加了 {@code POST /public/{token}/messages}（公开问答流），<strong>已被删除</strong>。
 * 理由是真源在客户端那一侧：公开页的问答流走的是 <strong>{@code POST /api/v1/conversations/chat} + {@code
 * X-Access-Credential} 头</strong> —— 见 {@code frontend/src/lib/sse.ts}（{@code if
 * (options.credentialToken) headers['X-Access-Credential']} 与 {@code
 * fetch(`${API_PREFIX}/conversations/chat`)}）与公开页 {@code pages/conversation/public/Index.vue} （它把
 * token 当 {@code credentialToken} 传下去）。
 *
 * <p>⇒ 公开面的问答流不是"另一条端点"，而是"<strong>同一条端点、另一种正当性</strong>"：分叉与授权都在 {@link
 * ChatController#chat}（{@code @PreAuthorize} 的两个条件 + 凭据头分支）， 而"这条链接是谁、能看多密"仍由 {@link
 * PublicChatService} 一处回答。 ★ 我们自造的那条路径会成为第二套公开面形状（前端永不调用、却要一直对账）——那正是要避免的东西。
 *
 * <p>★ <strong>停止是那条裁决的例外，而且理由充分</strong>（2026-10-16 用户拍板）：它<strong>不能</strong>复用登录面那条端点 —— 公开页的
 * {@code conversationId} 是合成串 {@code public:<token>}、公开面每一轮都是新会话（请求体里没有会话 id可传）、 而登录面的归属判定是"会话
 * {@code user_id} = 调用者"。 所以 {@link #stop} 是唯一形状成立的落点：<strong>按消息 id</strong>， 会话由服务端从消息行反解。★ 判据见
 * {@code PublicChatService#stopTurn} 的类注释；"公开面能不能停"这条口径也已在设计册落一节。
 *
 * <h3>★★ 为什么它能匿名访问（安全配置的边界）</h3>
 *
 * <p>放行规则是既有的那一条：{@code SecurityConfig.apiFilterChain} 里的 {@code
 * .requestMatchers("/api/v1/public/**").permitAll()}。★ 它为什么能免认证：公开面的正当性证明
 * <strong>不是</strong>"证明你是你"（那要 JWT），而是"<strong>令牌本身有效</strong>" （存在 / 未撤销 / 未过期 / 未停用）—— 由 {@code
 * PublicTokenResolver} 在服务层回答 （{@code 08 §4.2.3} / §4.4.4 的"两个正当性入口"）。
 *
 * <p>★★ <strong>边界（四条，都不许放宽）</strong>：
 *
 * <ol>
 *   <li>只放行 {@code /api/v1/public/**} 与（流那一条）{@code POST /api/v1/conversations/chat} —— 本类的 {@link
 *       #stop} 是<strong>写端点</strong>，所以它必须显式登记在 {@code AuthorizationDeclarationTest}
 *       的白名单里（那条闸门判的是"忘写注解"，不是"公开面不许有写端点"）；
 *   <li>放行的是<strong>认证</strong>，不是<strong>授权</strong>：本类的方法<strong>都不带</strong>
 *       {@code @PreAuthorize}（公开面上没有"动作权限点"可判，也不该借一个语义无关的权限点）， 而"能看到什么"由 {@code
 *       AccessScopePort#asSubject} 一处决定；★ {@link #stop} 刻意<strong>不</strong>包 {@code asSubject} ——
 *       它不读内容、不产出内容，包了会造出第二处"看起来像停止的权限判定"（理由逐字见 {@code PublicChatService#stopTurn}）；
 *   <li>匿名只意味着"没有当前用户"，<strong>不</strong>意味着范围更宽：{@code asSubject} 给的是 「<strong>链接归属人</strong>的范围 ∩
 *       <strong>冻结密级</strong>」，且当请求<strong>带了</strong>登录身份时
 *       再压到<strong>不大于调用者自己</strong>那一份（登录用户拿同一条链接<strong>不放大</strong>，{@code 08 §4.4.4} 判据②）；
 *   <li>★ {@link #stop} 的边界是"令牌有效 + 消息属于<strong>链接归属人</strong>的会话"两条，且它<strong>不收</strong> 请求体里的会话
 *       id —— "客户端指定这条链接能停哪个会话"这条越权在形状上就不存在。
 * </ol>
 *
 * <h3>★★ 撤销 / 停用与解析是<strong>同一条</strong>链（{@code 13 §5.2} 判据①）</h3>
 *
 * <p>本类不缓存任何东西，每一次访问都走 {@code PublicTokenResolver.resolve} → 实现里的 {@code
 * PublicCredentialResolver}。它按 token 缓存的是<strong>解析结果</strong>（凭证 → 应用 + 冻结值），
 * 而"凭证现在还可不可用"<strong>每次都复判一次</strong>；撤销 / 停用会把那一项当场清掉 （{@code AccessCredentialService.revoke} /
 * {@code setEnabled} → {@code PublicCredentialResolver.forget}）， 于是"同一个请求周期内再访问即失效"成立。★
 * 缓存里<strong>没有</strong>可见内容 —— 可见集合每次重算（{@code 13 §5.2} 第 ② 级）。
 *
 * <h3>★ 停用的账号 ⇒ 不出内容（{@code 08 §4.4.4} 末尾那条产品口径）</h3>
 *
 * <p>链接归属人 {@code iam_user.status != ACTIVE} 时，{@code SubjectIdentityPort} 载入不出身份 ⇒ {@code
 * asSubject} 装的是 {@code ScopeFilter.nobody()} ⇒ <strong>应用展示面也读不出来</strong> ⇒ 这里的 {@code
 * 404}「<strong>这条链接当前不可用</strong>」。★ 文案不是"应用不存在"（那是撒谎）， 也不借用四态码 —— 见 {@code
 * PublicChatService#linkUnavailable} 的完整口径。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicChatController {

  private final PublicChatService publicChat;

  public PublicChatController(PublicChatService publicChat) {
    this.publicChat = publicChat;
  }

  /**
   * 用访问凭证换应用上下文（契约 {@code resolvePublicContext}）。
   *
   * <p>★ 回执里的 {@code maxClassification} 是<strong>创建时冻结</strong>的值（{@code 13 §5.2} 第 ① 级）， 与库里的
   * {@code as_access_credential.max_classification} 同源 —— 公开页靠它告诉访客"这条链接读得到多密的内容"。
   *
   * @param token 链接里的令牌原文
   * @param jwt ★ 可空：匿名访客没有它。有值时只用于<strong>收窄</strong>（{@code asSubject} 内部）， 绝不当作正当性来源
   */
  @GetMapping("/{token}")
  public ApiResponse<PublicChatService.PublicContext> context(
      @PathVariable String token, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(publicChat.resolve(token, requesterId(jwt)));
  }

  /**
   * ★★ 公开面（免登录）的<strong>历史消息</strong>（契约 {@code listPublicMessages}；公开页**已在调**它）。
   *
   * <h3>它补的是哪一个"页面在调、后端没有"</h3>
   *
   * <p>契约 {@code conversations.ts} 的 {@code listPublicMessages(token)} 与 {@code
   * useChatSession#loadHistory} 的公开面分支一直在调这条路径，而本类此前<strong>只有</strong> {@code GET /{token}} 一个端点 ⇒
   * 公开页刷新后读不回历史（{@code ROADMAP §11.2} 第 17 周"下一步" 第 3 条点名的那一格；也是 A2「刷新不重置」在公开面缺的那一半）。
   *
   * <h3>★ 形状：只收 token，会话由服务端反解（与 {@link #stop} 同一条理由）</h3>
   *
   * <p>公开页的 {@code conversationId} 是合成串 {@code public:<token>}，拿不出一个真的 UUID ⇒ 归属键只能是 {@code (链接归属人,
   * 应用)} 的会话链（口径与判据见 {@code 13 §5.2.1}）。 判定与读取全在 {@link PublicChatService#listMessages}。
   *
   * @param token 链接里的令牌原文
   * @param jwt ★ 可空：匿名访客没有它。有值时只用于收窄，绝不当作正当性来源
   * @return 该会话链上的全部消息（{@code created_at} 升序；还没问过 ⇒ 空列表）
   */
  @GetMapping("/{token}/messages")
  public ApiResponse<List<ChatMessageView>> messages(
      @PathVariable String token, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(publicChat.listMessages(token, requesterId(jwt)));
  }

  /**
   * ★★ 公开面（免登录）的<strong>停止生成</strong>（{@code #143} 前半，用户 2026-10-16 拍板"做端点"）。
   *
   * <h3>它补的是哪一个静默降级</h3>
   *
   * <p>在此之前公开页的"停止"走的是客户端 {@code abort()}（掐 fetch）⇒ 服务端只看到"连接没了" ⇒ 本轮按 {@code DISCONNECT} 收尾、库里落
   * <strong>{@code FAILED}</strong>。 而契约给"用户停止"与"连接断开"定的是两个终态（{@code STOPPED} vs {@code FAILED}）⇒
   * 用户点了停止，却得到一条失败记录，且<strong>不报错</strong>。
   *
   * <h3>★★ 形状：按消息 id，会话 id 由服务端反解（不照抄登录面那条）</h3>
   *
   * <p>公开页的 {@code conversationId} 是合成串 {@code public:<token>}，且公开面每一轮都是新会话 ⇒ 请求体里没有可传的会话 id。
   * 端点的路径里<strong>只有令牌与消息 id</strong>，会话由服务端从消息行反解 —— 于是"客户端指定这条链接能停哪个会话"这条越权
   * <strong>在形状上就不存在</strong>。判定与处置全在 {@link PublicChatService#stopTurn}。
   *
   * <h3>★ 为什么<strong>不</strong>带 {@code @PreAuthorize}</h3>
   *
   * <p>与 {@link #context} 同一条（本类 javadoc 的边界第 ②
   * 条）：公开面上"放行的是<strong>认证</strong>，不是<strong>授权</strong>"，
   * 这里没有"动作权限点"可判，也<strong>不该借一个语义无关的权限点</strong>。★ 它是写端点却没有注解这件事 <strong>必须显式登记</strong>在 {@code
   * AuthorizationDeclarationTest} 的白名单里（那条闸门判的是"忘写注解"， 而不是"公开面不许有写端点"）—— 登记在那一处，加新端点时仍然各自要过那一关。
   *
   * <p>★ 错误响应的媒体类型仍由下面那个 {@link #onBusiness} 显式给出（公开页的 {@code Accept} 只允许 JSON， 交给全局处理器会被协商成 {@code
   * HttpMediaTypeNotAcceptableException} ⇒ 500）。
   *
   * @param token 链接里的令牌原文
   * @param messageId 助手消息 id（契约 {@code meta} 帧给出）
   * @param jwt ★ 可空：匿名访客没有它。有值时只用于收窄，绝不当作正当性来源
   * @return 停止回执（与登录面同形状：{@code accepted} + 服务端此刻读到的状态）
   */
  @PostMapping("/{token}/messages/{messageId}/stop")
  public ApiResponse<PublicChatService.StopReceipt> stop(
      @PathVariable String token, @PathVariable UUID messageId, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(publicChat.stopTurn(token, messageId, requesterId(jwt)));
  }

  /**
   * 把"流还没开始"那一段的<strong>统一包络</strong>显式写出来。
   *
   * <p>★ 与 {@code ChatController} 同一个理由：这条链路上的错误响应若交给 {@code @RestControllerAdvice}， 媒体类型会由请求的
   * {@code Accept} 协商，而公开页的 {@code Accept} 恰好只允许 JSON ⇒ 需要在这里<strong>显式</strong>给出 {@code
   * application/json}。
   *
   * <p>★ 四态码（{@code 40401}–{@code 40404}）的 HTTP 状态取 <strong>404</strong>：它们的数值不与 HTTP 状态重合 （{@code
   * 40001–59999}），全局处理器会落进 {@code default} 给 400 —— 而公开面上这四态与 {@code NOT_FOUND} 是同一族"这条链接打不开"。★
   * <strong>判据取包络里的 {@code code}，不取 HTTP 状态</strong> （前端 {@code utils/public-gate.ts} 就是这么分支的）。
   */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> onBusiness(BusinessException cause) {
    HttpStatus status =
        switch (cause.code()) {
          case NOT_FOUND,
              PUBLIC_TOKEN_UNKNOWN,
              PUBLIC_TOKEN_REVOKED,
              PUBLIC_TOKEN_EXPIRED,
              PUBLIC_TOKEN_DISABLED ->
              HttpStatus.NOT_FOUND;
          case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.from(cause));
  }

  /**
   * 发起解析的已登录用户；匿名时为 {@code null}。
   *
   * <p>★ 与登录面那条"取不到 {@code sub} ⇒ 401"<strong>刻意不同</strong>：这里没有登录是<strong>正常情况</strong> （访客），所以
   * {@code null} 就是匿名，而不是错误。而"有令牌却读不出 {@code sub}"这种半截状态由 {@code asSubject} 收窄成 {@code
   * nobody()}（fail-closed），不会退化成"当作匿名、把链接的范围给出去"。
   */
  private static UUID requesterId(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null) {
      return null;
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
