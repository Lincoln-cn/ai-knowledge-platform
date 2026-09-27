package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.application.CaptchaView;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.infrastructure.CaptchaService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 等保三项要求的端到端验证：登录验证码、登录失败锁定、口令强度。
 *
 * <p>★ 这个类<strong>不关闭验证码</strong>（与 {@code SecurityChainTest} 相反）—— 它要验的恰恰是"验证码开启时的行为"。而生产默认就是开启的，
 * 所以<b>这里跑的才是生产路径</b>；把验证码关掉再测登录，测的是另一条路。
 *
 * <p>★ 验证码是"只有人能读"的设计，自动化测试本无法得知答案 ⇒ 用 {@link CaptchaService#peekCodeForTesting} 旁路读取。那个方法的名字里带
 * ForTesting 就是为了让任何人一眼看出它不该被生产代码调用。
 *
 * <p>★ 锁定测试用的是 {@code chenjun}，不是 {@code admin} —— 锁定会持续 30 分钟，而测试等不起。用独立账号并在每次测试前重置它的状态，
 * 是让"锁定的副作用"不泄漏到其它测试的唯一办法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ComplianceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  private static final String ACCOUNT = "chenjun";
  private static final String PASSWORD = "123456";

  @LocalServerPort int port;

  @Autowired CaptchaService captchaService;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwordEncoder;

  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * 每次从干净状态开始：把口令重置回演示值、清掉失败计数与锁定。
   *
   * <p>★ 口令也必须重置，而且必须**绕过业务接口、直接写库** —— 因为演示口令 {@code 123456} 本身<em>不满足</em>等保强度要求（6 位、只有数字）， 走
   * {@code PUT /users/me/password} 是改不回去的。 那正是强度校验该有的效果；而测试要的是"确定的初始状态"，只能自己直接安排。
   *
   * <p>（这条是踩出来的：最初只在 {@code finally} 里"改回去"， 结果校验上线后恢复失败，密码卡在被改成的那个值上，后续所有测试连锁失败。）
   */
  @BeforeEach
  void resetAccountState() {
    users
        .findByUsername(ACCOUNT)
        .ifPresent(
            user -> {
              user.markPasswordChanged(passwordEncoder.encode(PASSWORD));
              user.recordSuccessfulLogin(Instant.now());
              users.save(user);
            });
  }

  // ────────────────────────── 验证码 ──────────────────────────

  @Test
  void 不带验证码登录被拒() {
    HttpResponse<String> res =
        post("/api/v1/auth/login", credentials(ACCOUNT, PASSWORD, null, null));

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("\"code\":1003").contains("验证码");
  }

  @Test
  void 验证码填错被拒() {
    CaptchaView captcha = captchaService.issue();

    HttpResponse<String> res =
        post("/api/v1/auth/login", credentials(ACCOUNT, PASSWORD, captcha.id(), "ZZZZ"));

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("\"code\":1003");
  }

  /** ★ 同一个验证码用两次：第二次必须失败（一次性消费）。 */
  @Test
  void 同一个验证码不能用两次() {
    CaptchaView captcha = captchaService.issue();
    String code = captchaService.peekCodeForTesting(captcha.id());

    assertThat(
            post("/api/v1/auth/login", credentials(ACCOUNT, PASSWORD, captcha.id(), code))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post("/api/v1/auth/login", credentials(ACCOUNT, PASSWORD, captcha.id(), code))
                .statusCode())
        .as("验证码是一次性的，重放必须被拒")
        .isEqualTo(400);
  }

  @Test
  void 带正确验证码可以登录() {
    users
        .findByUsername(ACCOUNT)
        .ifPresent(
            u ->
                System.out.println(
                    "[诊断] "
                        + ACCOUNT
                        + " hash="
                        + u.passwordHash().substring(0, Math.min(20, u.passwordHash().length()))
                        + " 状态="
                        + u.status()
                        + " 锁定至="
                        + u.lockedUntil()
                        + " 失败次数="
                        + u.failedLoginAttempts()));

    HttpResponse<String> res = login(ACCOUNT, PASSWORD);
    System.out.println("[诊断] 登录响应 = " + res.body());

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("accessToken").contains("refreshToken");
  }

  // ────────────────────────── 登录失败锁定 ──────────────────────────

  /**
   * ★ 等保的核心一条：连续失败 3 次 ⇒ 锁定。
   *
   * <p>注意每次尝试都要带**新的**验证码 —— 否则第二次起会因为验证码一次性而失败， 那测的是验证码而不是锁定逻辑。
   */
  @Test
  void 连续失败三次会锁定账号() {
    // 前两次：账号密码不正确（401），还没到阈值
    for (int i = 1; i <= 2; i++) {
      HttpResponse<String> res = login(ACCOUNT, "wrong-password-" + i);
      assertThat(res.statusCode()).as("第 " + i + " 次失败应是 401，不是锁定").isEqualTo(401);
      assertThat(res.body()).contains("\"code\":401");
    }

    // 第三次：触发锁定
    HttpResponse<String> third = login(ACCOUNT, "wrong-password-3");
    assertThat(third.statusCode()).isEqualTo(403);
    assertThat(third.body()).as("★ 第三次必须回锁定码，并且提示要说清锁多久").contains("\"code\":1001").contains("锁定");
  }

  /** ★ 锁定之后，**即使密码是对的**也进不去 —— 否则锁定就只是"拦住错密码"，没有意义。 */
  @Test
  void 锁定期间正确密码也登录不了() {
    for (int i = 1; i <= 3; i++) {
      login(ACCOUNT, "wrong-password-" + i);
    }

    HttpResponse<String> res = login(ACCOUNT, PASSWORD);

    assertThat(res.statusCode()).isEqualTo(403);
    assertThat(res.body()).contains("\"code\":1001");
  }

  /** 失败之后再成功一次，计数必须清零 —— 否则"偶尔打错一次"会累积成永久锁定。 */
  @Test
  void 成功登录会清掉失败计数() {
    assertThat(login(ACCOUNT, "wrong-password").statusCode()).isEqualTo(401);
    assertThat(login(ACCOUNT, PASSWORD).statusCode()).isEqualTo(200);

    // 又来两次失败：若计数没被清掉，这两次就会触发锁定
    assertThat(login(ACCOUNT, "wrong-1").statusCode()).isEqualTo(401);
    HttpResponse<String> second = login(ACCOUNT, "wrong-2");

    assertThat(second.statusCode()).as("成功一次之后计数应归零，所以这里还没到 3 次").isEqualTo(401);
    assertThat(second.body()).contains("\"code\":401");
  }

  // ────────────────────────── 口令强度 ──────────────────────────

  @Test
  void 改密时弱密码被拒并说清原因() {
    String token = accessToken(login(ACCOUNT, PASSWORD));

    HttpResponse<String> res =
        put(
            "/api/v1/users/me/password",
            "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"abc\"}",
            token);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("\"code\":1002").contains("至少 8 位");
  }

  @Test
  void 改密时含账号名的密码被拒() {
    String token = accessToken(login(ACCOUNT, PASSWORD));

    HttpResponse<String> res =
        put(
            "/api/v1/users/me/password",
            "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Chenjun@2026\"}",
            token);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("\"code\":1002").contains("账号名");
  }

  @Test
  void 改密时原密码错误被拒() {
    String token = accessToken(login(ACCOUNT, PASSWORD));

    HttpResponse<String> res =
        put(
            "/api/v1/users/me/password",
            "{\"oldPassword\":\"not-my-password\",\"newPassword\":\"Str0ng!Pass\"}",
            token);

    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.body()).contains("原密码");
  }

  /**
   * ★ 改密成功后：旧密码失效、新密码可用。
   *
   * <p>注意本测试会把口令改掉，所以它**必须**是最后一个改 chenjun 口令的 —— JUnit 的方法顺序不保证，因此这里在结尾把它改回去， 让其它测试即使跑在后面也仍然能用
   * {@code 123456} 登录。
   */
  @Test
  void 改密成功后新密码生效() {
    String token = accessToken(login(ACCOUNT, PASSWORD));

    HttpResponse<String> changed =
        put(
            "/api/v1/users/me/password",
            "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Str0ng!Pass\"}",
            token);
    assertThat(changed.statusCode()).isEqualTo(200);

    assertThat(login(ACCOUNT, PASSWORD).statusCode()).as("旧密码应失效").isEqualTo(401);
    assertThat(login(ACCOUNT, "Str0ng!Pass").statusCode()).as("新密码应可用").isEqualTo(200);
    // ★ 这里不需要把口令改回去：@BeforeEach 每轮都会把它重置成演示值。
    //   （而走业务接口也改不回去 —— 123456 不过强度校验，这正是应该的。）
  }

  // ────────────────────────── HTTP 小工具 ──────────────────────────

  private static String credentials(
      String username, String password, String captchaId, String captchaCode) {
    StringBuilder json =
        new StringBuilder("{\"username\":\"")
            .append(username)
            .append("\",\"password\":\"")
            .append(password)
            .append("\"");
    if (captchaId != null) {
      json.append(",\"captchaId\":\"").append(captchaId).append("\"");
    }
    if (captchaCode != null) {
      json.append(",\"captchaCode\":\"").append(captchaCode).append("\"");
    }
    return json.append("}").toString();
  }

  /** 每次调用都取一张**新**验证码 —— 验证码是一次性的，复用会让测试测错东西。 */
  private HttpResponse<String> login(String username, String password) {
    CaptchaView captcha = captchaService.issue();
    return post(
        "/api/v1/auth/login",
        credentials(
            username, password, captcha.id(), captchaService.peekCodeForTesting(captcha.id())));
  }

  private static String accessToken(HttpResponse<String> loginResponse) {
    return jsonField(loginResponse.body(), "accessToken");
  }

  private static String jsonField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  private HttpResponse<String> post(String path, String body) {
    return send("POST", path, body, null);
  }

  private HttpResponse<String> put(String path, String body, String bearer) {
    return send("PUT", path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      if (bearer != null) {
        builder.header("Authorization", "Bearer " + bearer);
      }
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("请求失败：" + method + " " + path, e);
    }
  }
}
