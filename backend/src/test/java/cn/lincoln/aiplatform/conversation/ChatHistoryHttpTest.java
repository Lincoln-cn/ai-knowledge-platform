package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
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
 * Login-side conversation history (contract listMessages): GET /conversations/{id}/messages.
 *
 * <p>This is the last cell of ROADMAP 11.2 week 17 "contract exists + page calls it + backend
 * missing".
 *
 * <h3>Four criteria</h3>
 *
 * <ol>
 *   <li>lastPageIsWhatTheChatWindowOpensWith - page=-1 means the LAST page (page 3 of 5 messages
 *       with size=2 holds exactly ONE message), and total is the conversation total;
 *   <li>aroundPutsTheTargetInsideTheWindow - the target must be IN the window;
 *   <li>aroundOfAMessageFromAnotherConversationIsNotFound - 404, not a silent fallback to page 1;
 *   <li>someoneElsesConversationIsNotFound - 404, not 403.
 * </ol>
 *
 * <h3>Why a real stack</h3>
 *
 * <p>The two easiest mistakes here only show up against a real database: paging ORDER (stable order
 * for messages created in the same instant) and the window ANCHOR (same-instant messages pushing
 * the target out of its own window). A unit test with tidy timestamps makes both look green.
 *
 * <h3>A measured trap, written here so nobody repeats it (round 3)</h3>
 *
 * <p>Do NOT assert "the last page must hold size rows": with 5 messages and size=2 the last page
 * (page 3) holds exactly ONE row. Round 3 mistook that 1 for a "missing row" bug and chased it for
 * three rounds. Assertions must state WHICH messages a page holds, not HOW MANY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ChatHistoryHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V4 seed account: owner of the fixture conversations. */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  private static final String CHENJUN = "chenjun";

  private static final Pattern ID_IN_ORDER = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation where"
            + " user_id = ?)",
        ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?", ADMIN_ID);
  }

  // ---------------------------- 1: page=-1 is the last page ----------------------------

  /**
   * page=-1 returns the LAST page and total is the conversation total.
   *
   * <p>Why it matters: the chat window opens with exactly this request (contract: "show the most
   * recent few first"). If -1 were clamped to page 1, users would open a conversation and see its
   * OLDEST messages, with nothing reporting an error.
   *
   * <p>Canary: drop the requested == -1 branch in ChatHistoryService -> safePage becomes 1 -> this
   * test goes red on the "which messages" assertion below.
   */
  @Test
  void lastPageIsWhatTheChatWindowOpensWith() throws Exception {
    UUID conversationId = seedConversation(ADMIN_ID, "history fixture");
    List<UUID> ids = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      ids.add(seedMessage(conversationId, "USER", "message-" + i, i));
    }
    String token = login("admin", "admin");

    HttpResponse<String> response =
        get("/api/v1/conversations/" + conversationId + "/messages?page=-1&size=2", token);

    assertThat(response.statusCode()).isEqualTo(200);
    // 5 messages, size=2 -> pages are [1,2] [3,4] [5]; the last page holds exactly ONE message.
    assertThat(idsInOrder(response.body()))
        .as("page=-1 must return the LAST page (here: message-5 only)")
        .containsExactly(ids.get(4).toString());
    assertThat(intField(response.body(), "total"))
        .as("total is the conversation total (5), not the page size (2)")
        .isEqualTo(5);
    assertThat(intField(response.body(), "page"))
        .as("the receipt carries the page that actually took effect")
        .isEqualTo(3);
  }

  // ---------------------------- 2: around keeps the target ----------------------------

  /**
   * around returns a window centred on the target, and the target MUST be inside it.
   *
   * <p>This pins "jump to a message" itself: a window cut only by createdAt can push the target out
   * when several messages share the same instant (common in the real stack), so the user lands
   * "near" it instead of on it, with nothing looking wrong.
   */
  @Test
  void aroundPutsTheTargetInsideTheWindow() throws Exception {
    UUID conversationId = seedConversation(ADMIN_ID, "window fixture");
    List<UUID> ids = new ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      ids.add(seedMessage(conversationId, "USER", "message-" + i, i));
    }
    UUID target = ids.get(3); // message-4
    String token = login("admin", "admin");

    // size=2 -> radius = max(1, 2/2) = 1 -> window = message-3, message-4, message-5
    HttpResponse<String> response =
        get(
            "/api/v1/conversations/" + conversationId + "/messages?around=" + target + "&size=2",
            token);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(idsInOrder(response.body()))
        .as("the window must be exactly [previous, target, next] for size=2")
        .containsExactly(ids.get(2).toString(), target.toString(), ids.get(4).toString());
    // ★★ total must be the CONVERSATION total (7), not the window size (3) - the mock does exactly
    //    this, and the frontend uses it to decide whether an earlier page exists: feeding it the
    //    window size makes "load earlier" vanish after a deep link, with nothing reporting an
    // error.
    assertThat(intField(response.body(), "total"))
        .as("around receipts carry the CONVERSATION total, not the window size")
        .isEqualTo(7);
  }

  // ---------------------------- 3: around across conversations -> 404 ----------------------------

  /**
   * around pointing at a message of ANOTHER conversation returns 404 (contract wording: "must
   * report 404, not silently fall back to the first page").
   *
   * <p>A silent fallback shows the FIRST PAGE to someone following a deep link, so they believe
   * they jumped while reading unrelated content.
   */
  @Test
  void aroundOfAMessageFromAnotherConversationIsNotFound() throws Exception {
    UUID mine = seedConversation(ADMIN_ID, "my conversation");
    UUID other = seedConversation(ADMIN_ID, "another conversation");
    seedMessage(mine, "USER", "mine", 2);
    UUID foreign = seedMessage(other, "USER", "foreign", 2);
    String token = login("admin", "admin");

    HttpResponse<String> response =
        get("/api/v1/conversations/" + mine + "/messages?around=" + foreign, token);

    assertThat(response.statusCode())
        .as("target outside this conversation -> 404 (contract: not a silent fallback to page 1)")
        .isEqualTo(404);
  }

  // ---------------------------- 4: someone else's conversation -> 404 ----------------------------

  /**
   * Someone else's conversation returns 404 (not 403).
   *
   * <p>Same discipline as ChatStopService / ChatTurnWriter (07 3.3 rule 2): a 403 tells the caller
   * "this id exists", which is a free probe.
   */
  @Test
  void someoneElsesConversationIsNotFound() throws Exception {
    UUID mine = seedConversation(ADMIN_ID, "admin conversation");
    seedMessage(mine, "USER", "only admin sees this", 2);
    String chenjun = login(CHENJUN, "123456");

    HttpResponse<String> response = get("/api/v1/conversations/" + mine + "/messages", chenjun);

    assertThat(response.statusCode())
        .as("someone else's conversation -> 404 (a 403 would confirm the id exists)")
        .isEqualTo(404);
  }

  // ---------------------------- fixtures and readings ----------------------------

  private UUID seedConversation(UUID userId, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, created_at, updated_at) values (?, ?, ?,"
            + " now(), now())",
        id,
        userId,
        title);
    return id;
  }

  /**
   * Fixture messages must carry DISTINCT created_at values (measured in round 3).
   *
   * <p>When several messages land in the same millisecond, the only tie-breaker is the random id,
   * so "message 4 and 5" are not the last two rows - and the assertion fails somewhere unrelated to
   * the code under test.
   */
  private UUID seedMessage(UUID conversationId, String role, String content, int stepSeconds) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status, created_at,"
            + " updated_at) values (?, ?, ?, ?, 'GENERATED', now() + (? * interval '1 second'),"
            + " now())",
        id,
        conversationId,
        role,
        content,
        stepSeconds);
    return id;
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    assertThat(response.statusCode())
        .as("login failed (%s): %s", username, response.body())
        .isEqualTo(200);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
    assertThat(m.find()).as("no accessToken in the login receipt: %s", response.body()).isTrue();
    return m.group(1);
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Ids inside the receipt's list, in returned order. */
  private static List<String> idsInOrder(String body) {
    // ★★ 不要用 "\[(.*?)]" 那种非贪婪匹配去切 list 数组（本批实测踩到）：JSON 里每条记录后面都有
    //   `}]`，非贪婪会在**第一条**就停 ⇒ 只拿到一个 id，而回执其实是 3 条（第一版的假红就是这个）。
    //   ⇒ 全局扫 `"id":"<uuid>"`（★ 只认 "id"，不认 "conversationId" —— 它是另一个键）。
    Matcher ids = ID_IN_ORDER.matcher(body);
    List<String> found = new ArrayList<>();
    while (ids.find()) {
      found.add(ids.group(1));
    }
    return found;
  }

  private static int intField(String json, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
    assertThat(m.find()).as("no %s field in the receipt: %s", field, json).isTrue();
    return Integer.parseInt(m.group(1));
  }
}
