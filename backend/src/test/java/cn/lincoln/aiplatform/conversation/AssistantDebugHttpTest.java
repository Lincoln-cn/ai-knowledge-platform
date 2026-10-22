package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code #184}：应用调试端点的<strong>真 HTTP + 真库</strong>读数。
 *
 * <h3>★★ 判据（每条都能红）</h3>
 *
 * <ol>
 *   <li>{@code POST /api/v1/assistants/{id}/debug} 返回 200，响应含 {@code answer / refs / elapsedMs /
 *       tokens}；
 *   <li>调试前后 {@code cv_conversation} / {@code cv_message} 行数<strong>不增加</strong> ——
 *       这是"调试不落正式会话"的唯一硬读数；
 *   <li>调试的提示词里出现应用的角色设定与引用编号（与正式问答同一套配置）；
 *   <li>把应用阈值改到 0.99 ⇒ 同一问题的引用变空（应用级检索参数真的进了链路）。
 * </ol>
 *
 * <p>★ 假模型只住在测试里（{@code FakeChatStreamPorts} 同一条纪律），不起任何长驻服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // 调试是"一次阻塞调用"，超时给宽一点；本类不测超时。
      // ★ 心跳 × 3 ≤ 闲置超时 是 ChatStreamProperties 的硬约束（默认心跳 20000 与 15000 冲突）。
      "app.chat.heartbeat-interval-ms=2000",
      "app.chat.idle-timeout-ms=15000"
    })
class AssistantDebugHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ADMIN = "admin";
  private static final String ADMIN_PASSWORD = "admin";
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /**
   * 假模型：{@code @MockitoBean} 直接替换容器里的 {@code ChatStreamPort}。
   *
   * <p>★ 不能用又一个 {@code @Primary} 的 {@code @Bean}：生产侧已有 {@code UpstreamStreamingChatAdapter} 与
   * {@code ChatPortFallbackStreamAdapter} 两个候选，再加一个 primary 会得到 {@code
   * NoUniqueBeanDefinitionException}（本类实测踩到）。{@code @MockitoBean} 是按类型替换，不会制造 second primary。
   */
  @MockitoBean ChatStreamPort chatStream;

  /** 假模型要读的"这一次收到什么提示词"。 */
  private final AtomicReference<String> promptSeen = new AtomicReference<>();

  /** 假模型被调用了几次。 */
  private final AtomicInteger modelCalls = new AtomicInteger();

  @BeforeEach
  void stubModel() {
    promptSeen.set(null);
    modelCalls.set(0);
    doAnswer(
        invocation -> {
          promptSeen.set(invocation.getArgument(1));
          modelCalls.incrementAndGet();
          ChatStreamPort.Callback callback = invocation.getArgument(4);
          callback.onChunk("调试答案：差旅住宿标准为每人每天500元。");
          callback.onEnd(ChatStreamPort.End.COMPLETE, null);
          return null;
        })
        .when(chatStream)
        .stream(any(), any(), anyBoolean(), any(), any());
  }

  @Test
  void debugReturnsAnswerAndDoesNotWriteAnyConversationOrMessage() throws Exception {
    Fixture fixture = seedApp(0.01, "FREE_ANSWER");
    promptSeen.set(null);

    long conversationsBefore = count("cv_conversation");
    long messagesBefore = count("cv_message");
    int callsBefore = modelCalls.get();

    HttpResponse<String> response = postDebug(login(), fixture.appId(), "差旅住宿标准");

    assertThat(response.statusCode())
        .as("★ 调试端点必须存在（404 是 #184 的现象；响应体：%s）", response.body())
        .isEqualTo(200);
    assertThat(response.body())
        .as("★ 响应必须含 answer / refs / elapsedMs / tokens 四个字段")
        .contains("\"answer\"")
        .contains("调试答案")
        .contains("\"refs\"")
        .contains("\"elapsedMs\"")
        .contains("\"tokens\"");
    assertThat(response.body())
        .as("★ 假模型没有 onUsage ⇒ tokens 是 null（未知），不是 0（#142）")
        .contains("\"tokens\":null");
    assertThat(promptSeen.get())
        .as("★ 调试必须用应用的角色设定与引用编号（与正式问答同一套拼装）")
        .contains("你是法务助手")
        .contains("[1]");
    assertThat(modelCalls.get()).as("★ 调试确实调用了一次模型").isEqualTo(callsBefore + 1);

    assertThat(count("cv_conversation"))
        .as("★★ 调试前后 cv_conversation 行数不得增加（FR-02-20-5 / BR-04-09）")
        .isEqualTo(conversationsBefore);
    assertThat(count("cv_message"))
        .as("★★ 调试前后 cv_message 行数不得增加 —— 它此前是'先补 controller 再说'最容易漏的一条")
        .isEqualTo(messagesBefore);
  }

  @Test
  void appThresholdChangesTheRefsOfTheSameQuestion() throws Exception {
    Fixture fixture = seedApp(0.99, "FREE_ANSWER");
    String token = login();

    // ★ 这条问题有 5 个词元，而夹具分段只命中 3 个 ⇒ coverage = 0.6 < 0.99 ⇒ 引用必须为空。
    //   若 ChatService 仍读平台阈值（没有平台设置时是 0），这里会拿到一条引用 ⇒ 立刻红。
    HttpResponse<String> response = postDebug(token, fixture.appId(), "差旅住宿标准 报销 发票");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .as("★ 应用阈值 0.99 > coverage 0.6 ⇒ 零引用（平台阈值 0 时这里会有一条）")
        .contains("\"refs\":[]");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(UUID appId) {}

  private Fixture seedApp(double threshold, String noRefStrategy) {
    UUID chatModel = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, '调试夹具对话模型', 'CHAT', 'LOCAL_INFERENCE',"
            + " 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        chatModel);
    UUID embeddingModel = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at) values (?, '调试夹具向量模型', 'EMBEDDING',"
            + " 'LOCAL_INFERENCE', 'bge-m3', 'http://localhost:11434/v1', 768, 'LOADED', true,"
            + " now(), now())",
        embeddingModel);

    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, '调试夹具库', '制度规范', 'LOCAL', ?, 'INTERNAL', ?, 'PRIVATE',"
            + " now(), now())",
        kbId,
        embeddingModel,
        ADMIN_ID);

    UUID appId = UUID.randomUUID();
    jdbc.update(
        "insert into as_assistant (id, name, type, model_id, role_prompt, owner_id, visibility,"
            + " published, retrieval_mode, retrieval_threshold, retrieval_top_n,"
            + " retrieval_no_ref_strategy, created_at, updated_at)"
            + " values (?, '调试夹具应用', 'SIMPLE', ?, '你是法务助手', ?, 'PRIVATE', true,"
            + " 'FULLTEXT', ?, 5, ?, now(), now())",
        appId,
        chatModel,
        ADMIN_ID,
        threshold,
        noRefStrategy);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);

    UUID docId = DocumentFixture.insertSucceeded(jdbc, kbId.toString(), "差旅制度文档", "INTERNAL");
    UUID segmentId = UUID.randomUUID();
    String text = "差旅住宿标准";
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " content, search_text, content_hash, char_count, enabled, embedding_status,"
            + " created_at, updated_at, classification) values (?, ?, ?, 'KB', ?, 0, ?, ?, ?, ?, ?,"
            + " true, 'PENDING_EMBEDDING', now(), now(), (select classification from kb_document"
            + " where id = ?))",
        segmentId,
        docId,
        kbId,
        kbId,
        "差旅住宿标准章节",
        text,
        text,
        UUID.randomUUID().toString().replace("-", ""),
        text.length(),
        docId);
    return new Fixture(appId);
  }

  private long count(String table) {
    Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private String login() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("种子账号必须能登录").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private HttpResponse<String> postDebug(String token, UUID appId, String question)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/assistants/" + appId + "/debug"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"question\":\"" + question + "\"}", StandardCharsets.UTF_8))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }
}
