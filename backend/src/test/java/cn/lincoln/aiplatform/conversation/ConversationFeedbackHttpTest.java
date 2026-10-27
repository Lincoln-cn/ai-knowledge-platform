package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 「会话评分」端点的<strong>真 HTTP + 真库</strong>读数（{@code ROADMAP §2.1} 里 S3 的最后一条）。
 *
 * <h3>★★ 为什么这一组必须打真 HTTP（本类是被一个真 bug 逼出来的）</h3>
 *
 * <p>{@code 2026-10-27} 真栈闸门 {@code frontend/check_s3_conversation_chain_e2e.py} 在 S3 的第 ⑦ 步读到
 * <strong>404</strong>，而领域层的 {@code ConversationFeedbackTest} 一直是全绿的。根因在 {@code
 * ChatController#submitConversationFeedback}：它把服务层两个 {@link UUID} 参数传反了 （写成 {@code
 * submit(subjectOf(jwt), conversationId, …)}，而签名是 {@code submit(conversationId, userId, …)}），于是
 * {@code conversations.findById(userId).filter(c -> c.userId().equals(conversationId))} 恒为空 ⇒
 * <strong>任何会话都返回 404「会话不存在」</strong>，评分这一格其实从来没通过过。
 *
 * <p>★★ 领域层测试抓不到它的原因很具体：{@code ConversationFeedbackTest} 直接调服务层， 参数顺序天然是对的 —— 它证明的是"服务层能存"，不是"那个
 * HTTP 请求真能打到这个能力上"。 这与本项目反复踩过的那条（{@code 契约成对 ≠ 功能成对}）同一个形状，所以判据要落在 <strong>真容器 + 真过滤器链 +
 * 真鉴权</strong>这一层，与 {@code ConversationStopEndpointTest} 同一条理由。
 *
 * <h3>四条用例各自钉住一件事</h3>
 *
 * <table>
 *   <tr><th>用例</th><th>钉住什么</th><th>反证（去掉哪一步它会红）</th></tr>
 *   <tr><td>{@link #ownerRatesOwnConversationAndItLandsInTheDatabase()}</td>
 *       <td>自己的会话 ⇒ {@code 200} 且这一行<b>真进了库</b></td>
 *       <td>★ 把两个 UUID 参数换回传反的顺序，这一条立刻红（实测见类注释：404）</td></tr>
 *   <tr><td>{@link #resubmittingOverwritesInsteadOfAppending()}</td>
 *       <td>再提交是<b>覆盖</b>：仍是一行、分数变、{@code createdAt} 不变</td>
 *       <td>把"先查后改"换成"直接插一行"，行数会变 2</td></tr>
 *   <tr><td>{@link #ratingSomeoneElsesConversationIs404()}</td>
 *       <td>别人的会话 ⇒ {@code 404}（不是 200、也不是 500）</td>
 *       <td>去掉归属过滤，这一条会变成 200</td></tr>
 *   <tr><td>{@link #outOfRangeScoreIs400WithTheReadableRange()}</td>
 *       <td>分数越界 ⇒ {@code 400} + 可读区间（不是 500）</td>
 *       <td>把校验挪到实体里，异常会以 {@code IllegalArgumentException} 逃出去变 500</td></tr>
 * </table>
 *
 * <p>★ 会话行直接 {@code insert}（不建应用、不起长驻服务）：本端点只按 {@code conversation.user_id} 判归属，夹具越薄越不容易把别的闸门拖进来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {"app.security.captcha.enabled=false"})
class ConversationFeedbackHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** 另一个用户（造"别人的会话"用；★ 库里没有这个人也没关系 —— 归属判定只比 id）。 */
  private static final UUID SOMEBODY_ELSE = UUID.fromString("00000000-0000-4000-8000-00000000dead");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private String token;

  @BeforeEach
  void login() throws Exception {
    token = login(ADMIN, ADMIN_PASSWORD);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation where"
            + " user_id = ?::uuid)",
        ADMIN_ID);
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation where"
            + " user_id = ?::uuid)",
        SOMEBODY_ELSE);
    jdbc.update("delete from cv_conversation_feedback where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from cv_conversation_feedback where user_id = ?::uuid", SOMEBODY_ELSE);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", SOMEBODY_ELSE);
  }

  // ────────────────────────── ① 自己的会话 ⇒ 200 且真进库 ──────────────────────────

  @Test
  void ownerRatesOwnConversationAndItLandsInTheDatabase() throws Exception {
    UUID conversation = seedConversation(ADMIN_ID, "评分夹具：自己的会话");

    Receipt receipt = rate(token, conversation, "{\"score\":5,\"comment\":\"答得不错\"}");

    assertThat(receipt.status())
        .as("自己的会话必须能评上分（★ 参数传反时这里是 404「会话不存在」）—— 实际响应：%s", receipt.body())
        .isEqualTo(200);
    assertThat(intField(receipt.body(), "score")).as("回执里的分数").isEqualTo(5);
    assertThat(stringField(receipt.body(), "comment")).as("回执里的评论").isEqualTo("答得不错");

    assertThat(countRows(conversation)).as("这一行必须真的落进 cv_conversation_feedback").isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select score from cv_conversation_feedback where conversation_id = ?",
                Integer.class,
                conversation))
        .as("库里的分数")
        .isEqualTo(5);
  }

  // ────────────────────────── ② 再提交是覆盖，不是追加 ──────────────────────────

  @Test
  void resubmittingOverwritesInsteadOfAppending() throws Exception {
    UUID conversation = seedConversation(ADMIN_ID, "评分夹具：覆盖");

    Receipt first = rate(token, conversation, "{\"score\":5}");
    assertThat(first.status()).as("第一次评分：%s", first.body()).isEqualTo(200);

    Receipt second = rate(token, conversation, "{\"score\":3,\"comment\":\"改一次\"}");
    assertThat(second.status()).as("第二次评分：%s", second.body()).isEqualTo(200);

    assertThat(countRows(conversation)).as("★ 设计册里只有「提交评分」、没有「追加」⇒ 一人一场会话必须只有一行").isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select score from cv_conversation_feedback where conversation_id = ?",
                Integer.class,
                conversation))
        .as("覆盖后库里的分数")
        .isEqualTo(3);
    assertThat(stringField(second.body(), "createdAt"))
        .as("覆盖不许动 createdAt（它是「首次提交时间」）")
        .isEqualTo(stringField(first.body(), "createdAt"));
    assertThat(stringField(second.body(), "updatedAt"))
        .as("覆盖必须推 updatedAt（否则「改过一次」在数据面完全看不出）")
        .isNotEqualTo(stringField(first.body(), "updatedAt"));
  }

  // ────────────────────────── ③ 别人的会话 ⇒ 404 ──────────────────────────

  @Test
  void ratingSomeoneElsesConversationIs404() throws Exception {
    UUID theirs = seedConversation(SOMEBODY_ELSE, "评分夹具：别人的会话");

    Receipt receipt = rate(token, theirs, "{\"score\":5}");

    assertThat(receipt.status())
        .as("别人的会话不许评上分（★ 要 404，不是 200，也不是 500）—— 实际响应：%s", receipt.body())
        .isEqualTo(404);
    assertThat(countRows(theirs)).as("被拒之后库里不许留下任何一行").isZero();
  }

  // ────────────────────────── ④ 分数越界 ⇒ 400 且文案可读 ──────────────────────────

  @Test
  void outOfRangeScoreIs400WithTheReadableRange() throws Exception {
    UUID conversation = seedConversation(ADMIN_ID, "评分夹具：越界");

    Receipt receipt = rate(token, conversation, "{\"score\":6}");

    assertThat(receipt.status())
        .as(
            "分数越界要 400（不是 500 —— 校验若只留在实体里会以 IllegalArgumentException 逃出去）—— 实际响应：%s",
            receipt.body())
        .isEqualTo(400);
    assertThat(receipt.body()).as("文案要让用户知道合法区间").contains("1").contains("5");
    assertThat(countRows(conversation)).as("被拒之后不许落库").isZero();
  }

  // ────────────────────────── 夹具与读数 ──────────────────────────

  /** 直接造一场会话（本端点只按 {@code user_id} 判归属，不需要应用/模型）。 */
  private UUID seedConversation(UUID userId, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, created_at, updated_at)"
            + " values (?, ?, ?, now(), now())",
        id,
        userId,
        title);
    return id;
  }

  private int countRows(UUID conversationId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from cv_conversation_feedback where conversation_id = ?",
            Integer.class,
            conversationId);
    return count == null ? 0 : count;
  }

  private record Receipt(int status, String body) {}

  private String base() {
    return "http://localhost:" + port;
  }

  private String login(String username, String password) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("种子账号必须能登录（否则后面每一条都读不到真实鉴权链）").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  /** 打一次会话评分端点（契约 {@code submitConversationFeedback}：{@code POST /conversations/{id}/feedback}）。 */
  private Receipt rate(String token, UUID conversationId, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(base() + "/api/v1/conversations/" + conversationId + "/feedback"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Receipt(response.statusCode(), response.body());
  }

  /** 从 JSON 里取一个顶层字符串字段（与 {@code SecurityChainTest} 同一手法）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  /** 从 JSON 里取一个顶层整数/浮点字段（{@code "score":5}）。 */
  private static int intField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return Integer.MIN_VALUE;
    }
    int start = json.indexOf(':', keyAt) + 1;
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length()
        && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return end > start ? Integer.parseInt(json.substring(start, end)) : Integer.MIN_VALUE;
  }
}
