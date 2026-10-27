package cn.lincoln.aiplatform.platform.security;

import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 公开面（免登录）限流过滤器（{@code #214}，口径见 {@link PublicRateLimitProperties}）。
 *
 * <h3>★★ 它管哪两条路径（而且只有这两条）</h3>
 *
 * <ol>
 *   <li>{@code /api/v1/public/**} —— 链接解析、读历史、停止生成。★ 键取<strong>路径里的令牌</strong>， 不取来源
 *       IP：这三条端点的配额本来就该跟着"<strong>这条链接</strong>"走 （同一条链接被贴到群里，压力是这条链接被刷爆，而不是某个 IP 自己刷爆）；也没法按登录用户限
 *       —— 公开面上压根没有当前用户；
 *   <li>{@code POST /api/v1/conversations/chat} <strong>且带了 {@code X-Access-Credential}</strong> ——
 *       公开页的问答流。 ★ 只在这条头部存在时计数：不带它的是<strong>登录面</strong>的问答，那里已经有 JWT 与 "所属会话"两层边界，不该再按 IP
 *       掐（同一个办公室的人共用一个出口 IP， 按 IP 限登录面等于让同事互相影响）；★ 这一条也是"同一份配额"：公开页先解析链接再提问， 两件事落在同一个键上，访客读一次链接 +
 *       问一轮不会变成两份额度。
 * </ol>
 *
 * <h3>★ 为什么它<strong>不</strong>是一个 {@code @Component}</h3>
 *
 * <p>Spring Boot 会把容器里的每个 {@code Filter} bean <strong>自动注册到 servlet 链</strong>上； 那样它会跑两遍（一次在
 * Security 链外、一次在链内），而链外那一遍拿不到 "这条请求最终会不会被放行"，计数会与真实请求量不一致。所以它只在 {@code
 * SecurityConfig.apiFilterChain} 里被装进 Security 链（与该文件里 {@code ApiKeyAuthenticationFilter} 同一个理由）。
 *
 * <h3>★ 429 的形状</h3>
 *
 * <p>HTTP 状态 <strong>429</strong> + 包络里的 {@code code = 429}（契约 {@code ErrorCode.TOO_MANY_REQUESTS}，
 * 与 4xx 对齐），并给 {@code Retry-After} 秒数。★ 两者都给的理由与 {@code ErrorResponses} 同一条： 前端有按 HTTP
 * 状态分支的代码路径，也有按包络 {@code code} 分支的代码路径， 少给一边就会出现"某个客户端永远看不到限流"。
 *
 * <p>★ <strong>手工拼 JSON、不注入 ObjectMapper</strong>：这两个字段由本类控制， 而且过滤器跑在 MVC 之外 ——
 * 引入序列化器只会多一个"它可能抛异常"的路径 （限流器自己 500 是最糟的形态：本来要保护后端，结果成了新的故障点）。
 */
public class PublicRateLimitFilter extends OncePerRequestFilter {

  /** 公开面的凭据头，与前端 {@code lib/sse.ts} 的 {@code X-Access-Credential} 同一个常量。 */
  public static final String CREDENTIAL_HEADER = "X-Access-Credential";

  private static final String PUBLIC_PREFIX = "/api/v1/public/";
  private static final String CHAT_PATH = "/api/v1/conversations/chat";

  /**
   * 键表超过这个规模就在每次请求时顺手清一遍过期桶。
   *
   * <p>★ 没有这一步，一张"每个 token 一个桶"的表就是<strong>内存泄漏</strong>（token 是一次性的， 每个访客都会造一个新键）。★
   * 也不用定时任务清：清理与判定在同一条请求路径上， 少一个后台线程就是少一个"它在别的线程里出错"的形状。
   */
  private static final int PURGE_THRESHOLD = 4096;

  private final PublicRateLimitProperties properties;
  private final LongSupplier clockMillis;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /** 生产装配用：时钟取系统时间。 */
  public PublicRateLimitFilter(PublicRateLimitProperties properties) {
    this(properties, System::currentTimeMillis);
  }

  /**
   * ★ 时钟可注入只为一件事：让"窗口真的会滚动"成为一条<strong>可断言</strong>的读法。
   *
   * <p>用 {@code Thread.sleep(61_000)} 去等窗口过期会让闸门多跑一分钟且结果依赖机器快慢； 传入一个可推进的时钟，判据就变成确定的。
   */
  public PublicRateLimitFilter(PublicRateLimitProperties properties, LongSupplier clockMillis) {
    this.properties = properties;
    this.clockMillis = clockMillis;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = keyOf(request);
    if (!properties.enabled() || key == null) {
      chain.doFilter(request, response);
      return;
    }

    int capacity = Math.max(1, properties.capacity());
    long windowMillis = Math.max(1, properties.windowSeconds()) * 1000L;
    long now = clockMillis.getAsLong();
    long index = Math.floorDiv(now, windowMillis);
    purgeExpired(index);

    Bucket bucket =
        buckets.compute(
            key,
            (ignored, existing) ->
                existing == null || existing.index != index ? new Bucket(index) : existing.hit());
    if (bucket.count <= capacity) {
      chain.doFilter(request, response);
      return;
    }
    reject(response, capacity, windowMillis, index, now);
  }

  /**
   * 这次请求算到哪个桶里；返回 {@code null} 表示<strong>这条请求不在本过滤器的范围里</strong>。
   *
   * <p>★ 返回 {@code null} 与"放行"是两件事，但在这里效果相同（都直接进链）—— 刻意不再区分，是因为"不在范围内"没有任何后续动作可做。
   */
  private static String keyOf(HttpServletRequest request) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }

    if (path.startsWith(PUBLIC_PREFIX)) {
      String rest = path.substring(PUBLIC_PREFIX.length());
      int slash = rest.indexOf('/');
      String token = slash < 0 ? rest : rest.substring(0, slash);
      return token.isBlank() ? "ip:" + request.getRemoteAddr() : "token:" + token;
    }

    if ("POST".equals(request.getMethod()) && CHAT_PATH.equals(path)) {
      String credential = request.getHeader(CREDENTIAL_HEADER);
      if (credential == null || credential.isBlank()) {
        return null;
      }
      return "token:" + credential.strip();
    }

    return null;
  }

  private void reject(
      HttpServletResponse response, int capacity, long windowMillis, long index, long now)
      throws IOException {
    // 本窗口还剩多少毫秒 ⇒ 折算成向上取整的秒数；至少 1，免得给客户端一个"立刻重试"的假信号
    long retryAfterSeconds = Math.max(1, ((index + 1) * windowMillis - now + 999) / 1000);
    String message = "请求过于频繁";
    String hint =
        "同一条链接或同一来源每 "
            + (windowMillis / 1000)
            + " 秒最多 "
            + capacity
            + " 次，请在 "
            + retryAfterSeconds
            + " 秒后再试";

    response.setStatus(429);
    response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
    response.setContentType("application/json; charset=utf-8");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"code\":"
                + ErrorCode.TOO_MANY_REQUESTS.code()
                + ",\"message\":\""
                + message
                + "\",\"data\":null,\"hint\":\""
                + hint
                + "\"}");
  }

  private void purgeExpired(long index) {
    if (buckets.size() <= PURGE_THRESHOLD) {
      return;
    }
    buckets.entrySet().removeIf(entry -> entry.getValue().index != index);
  }

  /** 一个键在一个窗口内的计数。★ 可变对象放在 {@code ConcurrentHashMap#compute} 里改，按 key 原子。 */
  private static final class Bucket {

    private final long index;
    private int count = 1;

    private Bucket(long index) {
      this.index = index;
    }

    private Bucket hit() {
      count++;
      return this;
    }
  }
}
