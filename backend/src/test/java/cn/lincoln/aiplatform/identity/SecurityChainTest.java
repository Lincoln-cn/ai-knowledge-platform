package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 认证链的端到端验证（{@code ROADMAP §4.7.3} 第 2 周测试项 ① 与 ④）。
 *
 * <p>★ 它对着<strong>真实启动的应用</strong>发 HTTP —— 不是 MockMvc。理由： MockMvc
 * 会绕过真实的过滤器链与嵌入式容器，而本周要验的恰恰是<strong>过滤器链</strong> （哪些路径免认证、401 长什么样）。用 MockMvc
 * 测出来的绿，对"前端真的能登录吗"这个问题没有说服力。
 *
 * <p>★ 最重要的一条是 {@link #不带令牌访问受保护端点返回401且是包络()}： 前端 {@code lib/http.ts:84} 是看 <em>HTTP</em> 401
 * 才触发"静默刷新并重放一次"的。 如果这里返回的是 200 + body 里一个 401，那条逻辑永远不会触发 —— 而表现为"登录过期后直接被踢回登录页"，功能"能用"、只是不对。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
// ★ 显式关掉验证码：这个类验的是**认证链本身**（哪些路径免认证、401 长什么样、刷新与重放）。
//   验证码开启时的行为由 ComplianceTest 覆盖 —— 那边**不关**，跑的是生产路径。
//   拆成两个类而不是在一个类里混着开关，是为了让"哪个测试在测哪条路径"一眼可见。
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class SecurityChainTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void 健康探针免认证() throws Exception {
    assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
  }

  @Test
  void 不带令牌访问受保护端点返回401且是包络() throws Exception {
    HttpResponse<String> res = get("/api/v1/users/me", null);

    assertThat(res.statusCode())
        .as("★ 必须是 HTTP 401 —— 前端靠它触发静默刷新；只在 body 里放 401 那条逻辑不会触发")
        .isEqualTo(401);
    assertThat(res.body()).contains("\"code\":401").contains("\"data\":null");
    assertThat(res.body()).as("401 也要给可行动的原因").contains("hint");
  }

  @Test
  void 坏令牌同样返回401() throws Exception {
    assertThat(get("/api/v1/users/me", "not-a-jwt-at-all").statusCode()).isEqualTo(401);
  }

  @Test
  void 登录免认证且能拿到双令牌() throws Exception {
    HttpResponse<String> res = login("admin", "admin");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(jsonField(res.body(), "accessToken")).isNotBlank();
    assertThat(jsonField(res.body(), "refreshToken")).isNotBlank();
    assertThat(res.body()).contains("\"mustChangePassword\":false");
  }

  @Test
  void 带合法令牌可以取到当前用户与其权限点() throws Exception {
    String accessToken = jsonField(login("admin", "admin").body(), "accessToken");

    HttpResponse<String> res = get("/api/v1/users/me", accessToken);

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"username\":\"admin\"").contains("\"roleName\":\"管理员\"");
    // 契约要求登录后一次性下发权限点；ADMIN 是全部 16 个
    assertThat(res.body()).contains("knowledge:create").contains("system:manage");
  }

  /**
   * ★ 轮换与重放检测 —— 本周最有价值的一条。
   *
   * <p>刷新一次之后，旧令牌必须<strong>失效</strong>；而且再次使用它不能只是"被拒绝"， 应当触发"撤销该用户全部会话"（因为对方手里那个令牌是有效的）。
   */
  @Test
  void 刷新会轮换令牌且旧令牌再使用会被判为重放() throws Exception {
    String firstRefresh = jsonField(login("admin", "admin").body(), "refreshToken");

    HttpResponse<String> rotated = post("/api/v1/auth/refresh", refreshBody(firstRefresh), null);
    System.out.println("[诊断] 首次刷新响应体 = " + rotated.body());
    assertThat(rotated.statusCode()).as("第一次刷新应当成功").isEqualTo(200);
    String secondRefresh = jsonField(rotated.body(), "refreshToken");
    assertThat(secondRefresh).as("刷新必须换发新的刷新令牌，而不是原样返回").isNotEqualTo(firstRefresh);

    HttpResponse<String> replay = post("/api/v1/auth/refresh", refreshBody(firstRefresh), null);
    assertThat(replay.statusCode()).as("旧令牌再用必须是失败的").isEqualTo(401);
    assertThat(replay.body()).as("提示要说清发生了什么 —— 服务端文案是『刷新令牌已被使用，为安全已登出全部会话』").contains("已被使用");
  }

  /**
   * ★ 不区分"账号不存在"与"密码错误"（{@code AuthService.login} 的注释里写了理由： 区分它们等于对外提供一个用户名探测器）。这条测试把那个决定钉住 ——
   * 免得将来有人"顺手"把提示改得更友好一些。
   */
  @Test
  void 登录失败不区分账号不存在与密码错误() throws Exception {
    HttpResponse<String> wrongPassword = login("admin", "definitely-wrong");
    HttpResponse<String> noSuchUser = login("nobody-here", "whatever");

    assertThat(wrongPassword.statusCode()).isEqualTo(401);
    assertThat(noSuchUser.statusCode()).isEqualTo(401);
    assertThat(jsonField(noSuchUser.body(), "message"))
        .as("两种失败的文案必须一致，否则就是个用户名探测器")
        .isEqualTo(jsonField(wrongPassword.body(), "message"));
  }

  @Test
  void 登出会撤销刷新令牌() throws Exception {
    String accessToken = jsonField(login("admin", "admin").body(), "accessToken");

    assertThat(post("/api/v1/auth/logout", "{}", accessToken).statusCode()).isEqualTo(200);
  }

  /**
   * ★ 未实现的端点必须返 404，不能返 500。
   *
   * <p>这条是**前后端联调时抓出来的**：前端的分析看板调 {@code /api/v1/analytics/overview} （后端第 2 周还没实现），界面收到
   * 500「服务内部错误」， 于是排查方向变成"后端崩了"，而真实原因是"这个接口还没做"。
   *
   * <p>★ 2026-10-22（#193）：分析端点已经实现，这条改用**仍未实现**的 {@code /workflow/node-types} 作为样本；
   * 2026-10-22（#196）`/prompt-configs` 也已落地，不能再拿它当“未实现”样本。 已实现端点缺参数的情形由下一条用例守（400，不是 500）。
   *
   * <p>根因是兜底的 {@code @ExceptionHandler(Exception.class)} 把 Spring 的 {@code
   * NoResourceFoundException} 也抓了 —— 它继承自 Exception。
   */
  @Test
  void 未实现的端点返回404而不是500() throws Exception {
    String accessToken = jsonField(login("admin", "admin").body(), "accessToken");

    HttpResponse<String> res = get("/api/v1/workflow/node-types", accessToken);

    assertThat(res.statusCode()).as("★ 未实现 ≠ 服务出错：返 500 会让'接口还没做'看起来像'后端崩了'").isEqualTo(404);
    assertThat(res.body()).contains("\"code\":404");
    assertThat(res.body()).as("hint 要直接点出可能的原因").contains("端点");
  }

  /**
   * ★ 已实现端点缺必填参数 ⇒ 400，不能是 500。
   *
   * <p>分析端点的 {@code from}/{@code to} 是必填查询参数。缺参数是调用方自己能修的问题， 不能被兜底 handler 改写成「服务内部错误」。
   */
  @Test
  void 已实现端点缺必填参数返回400而不是500() throws Exception {
    String accessToken = jsonField(login("admin", "admin").body(), "accessToken");

    HttpResponse<String> res = get("/api/v1/analytics/overview", accessToken);

    assertThat(res.statusCode()).as("缺参数是调用方能修的输入问题，必须是 400").isEqualTo(400);
    assertThat(res.body()).contains("\"code\":400");
    assertThat(res.body()).contains("参数");
  }

  // ────────────────────────── HTTP 与 JSON 小工具 ──────────────────────────

  /**
   * 取一个 JSON 字符串字段的值。
   *
   * <p>★ 手工解析而不是注入 ObjectMapper：这里只需要取几个顶层的字符串字段， 而 Boot 4 已切到 Jackson 3（包名与 2 不同，{@code 09
   * §4.4}）—— 为一个测试工具去绑定具体序列化器版本不划算。
   */
  private static String jsonField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  private static String refreshBody(String refreshToken) {
    return "{\"refreshToken\":\"" + refreshToken + "\"}";
  }

  private HttpResponse<String> login(String username, String password) throws Exception {
    return post(
        "/api/v1/auth/login",
        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
        null);
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30)).GET();
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String base() {
    return "http://localhost:" + port;
  }
}
