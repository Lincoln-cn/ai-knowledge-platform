package cn.lincoln.aiplatform.support;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 在测试里装一个"已认证请求"的上下文（第 11 周 · 0.3.3 起需要）。
 *
 * <h3>为什么测试突然需要它</h3>
 *
 * <p>密级 pre-filter 落地之前，直接调 {@code SegmentSearchPort} 是不需要身份的：谓词里只有启用位。 现在不行了 —— {@code
 * JdbcSegmentSearchQuery.clearanceRank()} 对<strong>取不到身份的调用</strong>返回 {@code
 * -1}（fail-closed，{@code 13 §三}），于是所有密级都过不了，检索返空。
 *
 * <p>所以"直接调端口"的测试必须自己装一个身份。这里<strong>不是</strong>为了让测试变绿而绕开安全 —— 恰恰相反：它把"没有身份 ⇒
 * 什么都看不见"这条规则留在生产代码里，让测试去适配它。 （反过来做——把空身份当成"无限制"——就是一次不会报警的全员可见。）
 *
 * <p>★ 与 {@code AccessScopeAdapter} 的口径对齐：密级、范围、角色都从<strong>令牌</strong>里读， 所以这里造的是一个真的（未签名的）{@link
 * Jwt}，而不是伪造一个 scope 对象 —— 走的是同一条解析路径。
 */
public final class SecurityContexts {

  private SecurityContexts() {}

  /**
   * 以给定的密级上限执行一段取数逻辑（模拟一个已认证请求）。
   *
   * <p>★ 主体是<strong>随机 UUID</strong>：调用方只关心"密级这一维"，不关心"我是谁"。 需要<strong>确定的那个主体</strong>时用 {@link
   * #asUser(UUID, ClassificationLevel, Supplier)} —— 例如"归属人自己解析自己的公开凭证"（{@code
   * AccessScopePort.asScope} 会校验主体与发起人是同一个人）。
   *
   * @param clearance 这个"测试用户"的密级上限
   * @param body 要执行的取数逻辑
   */
  public static <T> T asClearance(ClassificationLevel clearance, Supplier<T> body) {
    return asUser(UUID.randomUUID(), clearance, body);
  }

  /**
   * 以一个<strong>确定的主体</strong>执行一段取数逻辑（第 16 周 · 0.4.4 新增）。
   *
   * <p>★ 为什么必须能指定主体：{@code AccessScopePort.asScope} 的第一条纪律是
   * "发起人必须是当前请求者"（否则它就是"以任意人身份取数"的后门）。于是"归属人解析自己的公开凭证" 这类测试必须让令牌里的 {@code sub}
   * <strong>就是那个归属人</strong> —— 用随机 sub 会让它 红在"无法以别人的身份解析公开令牌"上，而那个红与本类要验的东西无关。
   */
  public static <T> T asUser(UUID userId, ClassificationLevel clearance, Supplier<T> body) {
    SecurityContext previous = SecurityContextHolder.getContext();
    try {
      SecurityContextHolder.setContext(contextFor(userId, clearance));
      return body.get();
    } finally {
      // ★ 必须还原：SecurityContext 是 ThreadLocal，而 surefire 会复用线程跑后续用例
      SecurityContextHolder.setContext(previous);
    }
  }

  /**
   * 以给定的密级上限 <strong>与密级标签</strong>执行一段取数逻辑（Issue {@code #26} 起需要）。
   *
   * <p>★ 标签这一维与密级同源：都从<strong>令牌</strong>里读（{@code clLabels}）， 所以这里加的是同一个声明， 而不是另造一个"测试专用的范围对象"——
   * 否则测出来的就不是生产那条解析路径。
   *
   * <p>★ {@code labels} 传空集是<strong>合法且常见</strong>的状态（绝大多数角色没配标签）， 它的语义是"不按标签限制"，不是"什么都看不到"。
   */
  public static <T> T asUserWithLabels(
      UUID userId, ClassificationLevel clearance, java.util.Set<String> labels, Supplier<T> body) {
    SecurityContext previous = SecurityContextHolder.getContext();
    try {
      SecurityContextHolder.setContext(contextFor(userId, clearance, labels));
      return body.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private static SecurityContext contextFor(UUID userId, ClassificationLevel clearance) {
    return contextFor(userId, clearance, java.util.Set.of());
  }

  private static SecurityContext contextFor(
      UUID userId, ClassificationLevel clearance, java.util.Set<String> labels) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .claim("role", "ADMIN")
            // dataScope=ALL：本辅助只管"密级"这一维，数据范围不该顺手被放宽（要验范围的测试自己造令牌）
            .claim("scope", "ALL")
            .claim("clearance", clearance.name())
            .claim("clLabels", java.util.List.copyOf(labels))
            .build();
    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
    // ★★ 这一行是必须的，而且踩过：`new JwtAuthenticationToken(jwt)` 之后 `isAuthenticated()`
    //   是 **false**（Spring Security 6 不再由构造器代设）。而 `AccessScopeAdapter.currentJwt()`
    //   第一句话就是 `!authentication.isAuthenticated() → null ⇒ ScopeFilter.nobody()`。
    //   症状极具迷惑性：上下文**装上了**（日志里能看到 JwtAuthenticationToken），身份却是空的，
    //   于是检索安静地返回 0 条 —— 看起来像"谓词写错了"。
    authentication.setAuthenticated(true);

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    return context;
  }
}
