package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.PublicChatService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免登录公开面上<strong>唯一</strong>的 HTTP 端点：{@code GET /api/v1/public/{token}} （契约 {@code
 * frontend/src/api/conversations.ts} 的 {@code resolvePublicContext}）。
 *
 * <h3>★★ 为什么这里<strong>只有</strong>一个端点（第 17 周 · Lead 裁决 ①）</h3>
 *
 * <p>本批一度在这里加了第二个端点 {@code POST /public/{token}/messages}（公开问答流），<strong>已被删除</strong>。
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
 * <h3>★★ 为什么它能匿名访问（安全配置的边界）</h3>
 *
 * <p>放行规则是既有的那一条：{@code SecurityConfig.apiFilterChain} 里的 {@code
 * .requestMatchers("/api/v1/public/**").permitAll()}。★ 它为什么能免认证：公开面的正当性证明
 * <strong>不是</strong>"证明你是你"（那要 JWT），而是"<strong>令牌本身有效</strong>" （存在 / 未撤销 / 未过期 / 未停用）—— 由 {@code
 * PublicTokenResolver} 在服务层回答 （{@code 08 §4.2.3} / §4.4.4 的"两个正当性入口"）。
 *
 * <p>★★ <strong>边界（三条，都不许放宽）</strong>：
 *
 * <ol>
 *   <li>只放行 {@code /api/v1/public/**} 与（流那一条）{@code POST /api/v1/conversations/chat} ——
 *       本类不再有写端点，所以这里没有新的放行面；
 *   <li>放行的是<strong>认证</strong>，不是<strong>授权</strong>：本方法<strong>不带</strong>
 *       {@code @PreAuthorize}（公开面上没有"动作权限点"可判，也不该借一个语义无关的权限点）， 而"能看到什么"由 {@code
 *       AccessScopePort#asSubject} 一处决定；
 *   <li>匿名只意味着"没有当前用户"，<strong>不</strong>意味着范围更宽：{@code asSubject} 给的是 「<strong>链接归属人</strong>的范围 ∩
 *       <strong>冻结密级</strong>」，且当请求<strong>带了</strong>登录身份时
 *       再压到<strong>不大于调用者自己</strong>那一份（登录用户拿同一条链接<strong>不放大</strong>，{@code 08 §4.4.4} 判据②）。
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
