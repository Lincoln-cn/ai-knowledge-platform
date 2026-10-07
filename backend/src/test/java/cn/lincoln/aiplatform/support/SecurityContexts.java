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
   * @param clearance 这个"测试用户"的密级上限
   * @param body 要执行的取数逻辑
   */
  public static <T> T asClearance(ClassificationLevel clearance, Supplier<T> body) {
    SecurityContext previous = SecurityContextHolder.getContext();
    try {
      SecurityContextHolder.setContext(contextFor(clearance));
      return body.get();
    } finally {
      // ★ 必须还原：SecurityContext 是 ThreadLocal，而 surefire 会复用线程跑后续用例
      SecurityContextHolder.setContext(previous);
    }
  }

  private static SecurityContext contextFor(ClassificationLevel clearance) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .claim("role", "ADMIN")
            // dataScope=ALL：本辅助只管"密级"这一维，数据范围不该顺手被放宽（要验范围的测试自己造令牌）
            .claim("scope", "ALL")
            .claim("clearance", clearance.name())
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
