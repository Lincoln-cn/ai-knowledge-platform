package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ {@code #142} ④：<strong>真栈上 {@code done.tokens} 必须等于假模型上报的用量</strong> —— 真 HTTP +
 * 真库（Testcontainers）+ 假模型报用量。
 *
 * <h3>★★ 它钉的是什么（为什么必须走真栈）</h3>
 *
 * <p>修之前，{@code StreamAssembler#addUsage} 在 {@code backend/src/main} 是<strong>零调用点</strong>： {@code
 * ChatStreamPort.Callback} 只有 {@code onChunk} / {@code onEnd}，没有任何用量通道 ⇒ 生产上 {@code done.tokens} 恒为
 * {@code TokenUsage.zero()}。而"零用量"与"没有模型调用"在成本口径上 完全不可分（{@code 10 §8.4}）——
 * <strong>它不报任何错</strong>，正是本仓头号病那一类。
 *
 * <p>★ 为什么域层那条用例不够：{@code StreamTokensTest} 直接调 {@code assembler.addUsage(...)}，
 * 它证明的是"累加口只有一个"，<strong>不证明</strong>"生产链路上有人喂"。这一条读的是<strong>全链路</strong>： 假模型（{@link
 * UsageReportingChatStreamPort}）→ {@code ChatStreamPort.Callback#onUsage} → {@code
 * ChatService.Run#onUsage} → {@code StreamAssembler#addUsage} → {@code done} 帧 + 库里那两列。
 *
 * <h3>★★ 判据的形状：三字段逐字 + 库里那两列</h3>
 *
 * <p>两个分量刻意取<strong>不同</strong>且都非零的值（137 / 55）：这样"只喂了其中一个分量" （例如只把 {@code completion}
 * 接上）与"两个都喂了但加错了"都会在这一条上暴露， 而"只断言 {@code total > 0}"那种写法对上面两种错法<strong>照样绿</strong>。
 *
 * <h3>★★ 金丝雀（本批实测做过一次）</h3>
 *
 * <p>把喂入点去掉（{@code ChatService.Run#onUsage} 里不再调 {@code assembler.addUsage}， 或让 {@code
 * Callback#onUsage} 不再被转发）⇒ 本用例第一条断言当场红： 实际是 {@code
 * "tokens":{"prompt":0,"completion":0,"total":0}}。逐字读数见交付报告。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      // ★ 与 ChatStreamHttpTest 同一条理由：本类验的是**问答链路**，登录只是拿令牌的手段。
      "app.security.captcha.enabled=false",
      "app.chat.heartbeat-interval-ms=300",
      // ★ 闲置超时给宽一点（15s）：心跳线程现在**检索之前**就起（#142 ③），而闲置超时看的是
      //   "距上次业务帧"—— 容器冷启动后的第一次检索若偶发变慢，1200ms 会把它误判成 IDLE_TIMEOUT，
      //   那会让这一条测 tokens 的用例红在一个与 tokens 无关的原因上。
      "app.chat.idle-timeout-ms=15000",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class ChatStreamUsageHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 假模型上报的提示词 token。★ 与 completion 取值不同（见类注释：只喂一个分量必须能读出来）。 */
  private static final int REPORTED_PROMPT_TOKENS = 137;

  /** 假模型上报的生成 token。 */
  private static final int REPORTED_COMPLETION_TOKENS = 55;

  /** 假模型的答案（不含换行与引号，便于直接断言原始帧文本）。 */
  private static final String ANSWER = "差旅住宿标准为每人每天500元，超出部分由个人承担。";

  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id（造"自己的会话"用）。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "delete from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?::uuid)",
        ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from ai_model where name like 'ChatStream 用量夹具%'");
  }

  @Test
  void realStackFeedsTheModelReportedUsageIntoDoneTokens() throws Exception {
    seedChatModel();
    String token = login();

    HttpResponse<String> response = postChat(token, body("差旅住宿标准"));

    assertThat(response.statusCode()).as("SSE 端点必须 200（登录面拿真令牌）").isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .as("★ 契约要求 text/event-stream")
        .startsWith("text/event-stream");

    String stream = response.body();
    String done = dataOf(stream, "done");
    assertThat(done)
        .as(
            "★★ 判据④（#142 · 真栈）：done.tokens 逐字等于假模型上报的两个分量与它们的和 —— "
                + "把喂入点（ChatService.Run#onUsage）去掉这一条立刻红：实际读到的是"
                + " {\"prompt\":0,\"completion\":0,\"total\":0}")
        .contains("\"tokens\":{\"prompt\":137,\"completion\":55,\"total\":192}");
    assertThat(done).contains("\"status\":\"GENERATED\"");

    String messageId = stringField(done, "messageId");
    assertThat(messageId).as("★ done 里的 messageId 是查库的把手").isNotNull();
    assertThat(promptTokensInDb(messageId))
        .as("★ 库里那一列与 wire 上那一帧同源（同一个累计用量，不是两处各算一遍）")
        .isEqualTo(REPORTED_PROMPT_TOKENS);
    assertThat(completionTokensInDb(messageId)).isEqualTo(REPORTED_COMPLETION_TOKENS);
    assertThat(totalTokensInDb(messageId))
        .as("★★ 非零 —— 这一条就是 #142 ④ 的判据本身（修之前恒为 0，且不报错）")
        .isEqualTo(REPORTED_PROMPT_TOKENS + REPORTED_COMPLETION_TOKENS);
    assertThat(statusInDb(messageId))
        .as("★ 这一轮正常收尾（tokens 的读数不是从一条失败路径上读来的）")
        .isEqualTo("GENERATED");
  }

  // ────────────────────────── 假模型（测试里唯一的那份上报点）──────────────────────────

  /**
   * 把"上报用量的假模型"装成容器里那个 {@link ChatStreamPort}。
   *
   * <p>★ {@code @Primary} 覆盖生产的兜底实现 {@code ChatPortFallbackStreamAdapter} ——
   * "测试用假的、生产用真的"仍由容器装配决定，业务代码里没有一行 if（与 {@code ChatStreamHttpTest} 同一条口径）。
   * 本类只有一个假模型，所以不需要那套"可替换分发器"。
   */
  @TestConfiguration
  static class UsageReportingModelConfig {

    @Bean
    @Primary
    ChatStreamPort usageReportingChatStreamPort() {
      return new UsageReportingChatStreamPort();
    }
  }

  /**
   * 上报用量的假模型：吐一段正文 → 上报用量 → 报终态。
   *
   * <p>★ 用量放在 {@code onEnd} <strong>之前</strong>那一刻，是<strong>真实现也会有的形状</strong>： 兼容 OpenAI
   * 的流式实现在最后一个块里给 usage（{@code stream_options.include_usage}）。 于是"用量与终态同源、但前者先到"这条时序在本用例里被真的走了一遍。
   */
  static final class UsageReportingChatStreamPort implements ChatStreamPort {

    @Override
    public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
      callback.onChunk(ANSWER);
      // ★ 假模型上报的两段（#142 ④）：一路喂到装配器，最终进 done.tokens 与 cv_message 的两列
      callback.onUsage(REPORTED_PROMPT_TOKENS, REPORTED_COMPLETION_TOKENS);
      callback.onEnd(End.COMPLETE, null);
    }
  }

  // ────────────────────────── HTTP 与库读数 ──────────────────────────

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
    assertThat(response.statusCode()).as("种子账号必须能登录（否则后面每一条都读不到真实鉴权链）").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private HttpResponse<String> postChat(String token, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/conversations/chat"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String body(String question) {
    return "{\"question\":\"" + question + "\",\"attachmentIds\":[],\"thinking\":false}";
  }

  /**
   * 从整条 SSE 流里取某个事件名的 {@code data}（手写解析，与 {@code ChatStreamHttpTest} 同一手法）。
   *
   * <p>★ 不引 SSE 库：本类只要 {@code done} 那一帧的 payload，而"帧长什么样"由那条真栈用例单独钉住。
   */
  private static String dataOf(String stream, String event) {
    for (String block : stream.split("\n\n")) {
      String name = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          data = line.substring("data:".length()).trim();
        }
      }
      if (event.equals(name)) {
        return data;
      }
    }
    throw new AssertionError("响应里没有 event: " + event + " 的帧；整条流：\n" + stream);
  }

  /** 从 JSON 里取一个顶层字符串字段（与 {@code ChatStreamHttpTest} / {@code SecurityChainTest} 同一手法）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  private String statusInDb(String messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, messageId);
  }

  private Integer promptTokensInDb(String messageId) {
    return jdbc.queryForObject(
        "select prompt_tokens from cv_message where id = ?::uuid", Integer.class, messageId);
  }

  private Integer completionTokensInDb(String messageId) {
    return jdbc.queryForObject(
        "select completion_tokens from cv_message where id = ?::uuid", Integer.class, messageId);
  }

  private Integer totalTokensInDb(String messageId) {
    return jdbc.queryForObject(
        "select prompt_tokens + completion_tokens from cv_message where id = ?::uuid",
        Integer.class,
        messageId);
  }

  /**
   * 造一个可用的对话模型（本类是通用问答：没有应用绑定 ⇒ {@code pickChatModel()} 要挑得到一个）。
   *
   * <p>★ 假模型其实不看 {@code modelId}，但 {@code ChatTurnWriter#prepare} 会在"一个可用的对话模型都没有"时 抛 400 ——
   * 那会让这条用例红在一个与 tokens 无关的原因上（而且看起来像"链路坏了"）。
   */
  private void seedChatModel() {
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, 'ChatStream 用量夹具对话模型', 'CHAT',"
            + " 'LOCAL_INFERENCE', 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(),"
            + " now())",
        UUID.randomUUID());
  }
}
