package cn.lincoln.aiplatform.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link PublicRateLimitFilter} 的判定表（{@code #214}）。
 *
 * <h3>★ 为什么不启 Spring</h3>
 *
 * <p>这里要验的是<strong>计数与边界</strong>：第几次开始拒、拒的形状、桶按什么分。 这些全是过滤器的纯函数行为；起一个容器只会让每条用例多几十秒，还会把
 * "过滤器到底有没有装进链里"与"过滤器算得对不对"混在一起。 <strong>后者</strong>（装没装进去）由 {@code PublicRateLimitHttpTest} 用真
 * HTTP 回答 —— 两条合起来才等于"限流生效"。
 *
 * <h3>★ 时钟是注入的</h3>
 *
 * <p>"窗口会滚动"这条如果用 {@code Thread.sleep} 去验，要么等一分钟、要么靠机器够快， 两种都不是判据。这里推进一个 {@link AtomicLong} 就够。
 */
class PublicRateLimitFilterTest {

  /** 一个固定的起点，落在一个 60 秒窗口的开头附近（便于读"还剩多少秒"）。 */
  private final AtomicLong now = new AtomicLong(1_000_000L);

  private PublicRateLimitFilter filter(int capacity, int windowSeconds) {
    return filter(capacity, windowSeconds, true);
  }

  private PublicRateLimitFilter filter(int capacity, int windowSeconds, boolean enabled) {
    return new PublicRateLimitFilter(
        new PublicRateLimitProperties(enabled, capacity, windowSeconds), now::get);
  }

  private static MockHttpServletRequest publicRequest(String token) {
    return new MockHttpServletRequest("GET", "/api/v1/public/" + token);
  }

  private static MockHttpServletRequest chatRequest(String credential) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/conversations/chat");
    if (credential != null) {
      request.addHeader(PublicRateLimitFilter.CREDENTIAL_HEADER, credential);
    }
    return request;
  }

  /** 跑一次过滤器，返回"它有没有被放行到链里"。 */
  private static boolean passes(
      PublicRateLimitFilter filter,
      MockHttpServletRequest request,
      MockHttpServletResponse response)
      throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return chain.getRequest() != null;
  }

  @Test
  void exactlyCapacityRequestsGetThroughAndTheNextOneIsRejected() throws Exception {
    PublicRateLimitFilter filter = filter(2, 60);

    assertThat(passes(filter, publicRequest("t1"), new MockHttpServletResponse()))
        .as("第 1 次必须在额度内")
        .isTrue();
    assertThat(passes(filter, publicRequest("t1"), new MockHttpServletResponse()))
        .as("第 2 次正好是边界 —— 边界必须是「允许」，不是「拒绝」")
        .isTrue();
    assertThat(passes(filter, publicRequest("t1"), new MockHttpServletResponse()))
        .as("第 3 次才该被拒（容量 2）")
        .isFalse();
  }

  @Test
  void theRejectionCarriesHttp429AndTheEnvelopeCode() throws Exception {
    PublicRateLimitFilter filter = filter(1, 60);
    passes(filter, publicRequest("t2"), new MockHttpServletResponse());

    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThat(passes(filter, publicRequest("t2"), response)).isFalse();

    assertThat(response.getStatus()).as("HTTP 429 —— 前端有一条按状态码分支的路径").isEqualTo(429);
    assertThat(response.getContentType())
        .as("公开页的 Accept 只允许 JSON，Content-Type 不对会被协商成 406")
        .contains("application/json");
    assertThat(response.getHeader("Retry-After")).as("必须告诉客户端什么时候可以再试").isNotBlank();
    assertThat(response.getContentAsString())
        .as("包络里的 code 也必须是 429 —— 另一条分支按它判")
        .contains("\"code\":429")
        .contains("\"data\":null")
        .contains("秒");
  }

  @Test
  void oneLinkBeingThrottledDoesNotThrottleAnotherOne() throws Exception {
    PublicRateLimitFilter filter = filter(1, 60);
    passes(filter, publicRequest("busy-link"), new MockHttpServletResponse());

    assertThat(passes(filter, publicRequest("busy-link"), new MockHttpServletResponse())).isFalse();
    assertThat(passes(filter, publicRequest("quiet-link"), new MockHttpServletResponse()))
        .as("配额跟着链接走：一条被贴到群里刷爆，不该让另一条打不开")
        .isTrue();
  }

  @Test
  void theChatEndpointIsCountedOnlyWhenItCarriesACredential() throws Exception {
    PublicRateLimitFilter filter = filter(2, 60);

    // 登录面的问答（没有凭据头）重复多少次都不计数 —— 它已经有 JWT 与「所属会话」两层边界，
    // 再按 IP 掐等于让同一个出口 IP 的同事互相影响
    for (int i = 0; i < 5; i++) {
      assertThat(passes(filter, chatRequest(null), new MockHttpServletResponse()))
          .as("第 %d 次登录面问答不该被限流", i + 1)
          .isTrue();
    }

    // 公开面问答走同一个键：与「解析链接」共用一份配额
    assertThat(passes(filter, chatRequest("cred-a"), new MockHttpServletResponse())).isTrue();
    assertThat(passes(filter, publicRequest("cred-a"), new MockHttpServletResponse())).isTrue();
    assertThat(passes(filter, chatRequest("cred-a"), new MockHttpServletResponse()))
        .as("公开面「解析 + 两轮问答」= 3 次 > 容量 2 —— 两者必须共享同一个桶")
        .isFalse();
  }

  @Test
  void ordinaryAuthenticatedPathsAreNeverCounted() throws Exception {
    PublicRateLimitFilter filter = filter(1, 60);

    for (int i = 0; i < 5; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
      assertThat(passes(filter, request, new MockHttpServletResponse()))
          .as("第 %d 次知识库列表不该被限流", i + 1)
          .isTrue();
    }
  }

  @Test
  void disablingTheLimitLetsEverythingThrough() throws Exception {
    PublicRateLimitFilter filter = filter(1, 60, false);

    for (int i = 0; i < 5; i++) {
      assertThat(passes(filter, publicRequest("t3"), new MockHttpServletResponse()))
          .as("关掉之后第 %d 次仍该放行", i + 1)
          .isTrue();
    }
  }

  @Test
  void theWindowRollsOverSoTheNextWindowStartsFromZero() throws Exception {
    PublicRateLimitFilter filter = filter(1, 60);
    passes(filter, publicRequest("t4"), new MockHttpServletResponse());
    assertThat(passes(filter, publicRequest("t4"), new MockHttpServletResponse()))
        .as("同一窗口内第二次必须被拒")
        .isFalse();

    now.addAndGet(61_000L);

    assertThat(passes(filter, publicRequest("t4"), new MockHttpServletResponse()))
        .as("窗口滚过之后，同一个链接必须重新可用（否则限流会变成永久封禁）")
        .isTrue();
  }

  @Test
  void aNonPositiveCapacityIsTreatedAsOneInsteadOfBlockingEverything() throws Exception {
    PublicRateLimitFilter filter = filter(0, 60);

    assertThat(passes(filter, publicRequest("t5"), new MockHttpServletResponse()))
        .as("误配成 0 时取 1：一条也过不去比配错更难被发现")
        .isTrue();
    assertThat(passes(filter, publicRequest("t5"), new MockHttpServletResponse())).isFalse();
  }
}
