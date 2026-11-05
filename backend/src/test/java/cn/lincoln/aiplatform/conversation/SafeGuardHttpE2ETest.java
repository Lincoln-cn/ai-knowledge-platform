package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.AdvisorChainTrace;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SafeGuardChatAdvisor;
import cn.lincoln.aiplatform.support.PostgresImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
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
 * {@code #62} 判据④的 <strong>HTTP 级</strong>读数：{@code SafeGuardChatAdvisor} 在 {@code
 * /api/v1/conversations/chat} 这条真链路上拦得住，而且拦在<strong>上游之前</strong>。
 *
 * <h3>★★ 为什么适配器层那条读数不够（本类存在的理由）</h3>
 *
 * <p>{@code SpringAiChatAdapterTest#officialSafeGuardAdvisorBlocksSensitiveInputBeforeUpstream}
 * 证明的是"把 guard 手工放进 advisor 列表时它拦得住"。但它<strong>不经过生产装配</strong>：不经过 {@code ChatService} 的提示词拼装、不经过
 * {@code ChatStreamPort}（{@code UpstreamStreamingChatAdapter}）、 也不经过 {@code ChatModelAdapter}
 * 的分流。这四段里任何一段把问题绕开 advisor 链，适配器层的用例都照样绿 —— 而用户看到的是"敏感词照样被发去上游"。{@code ROADMAP §11.10.31} 的「仍欠」第 ③
 * 条指的就是这一个洞。
 *
 * <h3>★ 它怎么造"上游被调用过"这件事</h3>
 *
 * <p>起一个真的 {@link HttpServer} 扮 OpenAI 兼容上游（模型行的 {@code api_base} 指向它），于是 "上游有没有被调用"不是靠 mock
 * 计数，而是<strong>TCP 上真的收到过请求</strong>。
 *
 * <h3>★ 三条断言各钉什么</h3>
 *
 * <ol>
 *   <li><strong>对照</strong>：普通问题 ⇒ delta 里出现上游原文、上游命中数增加、链探针 {@code streamCount}
 *       增加。没有这一条，下一条的"零命中"可能只是"模型根本没被调起来"；
 *   <li><strong>拦截</strong>：问题含词表里的词 ⇒ delta 恰好是 {@link SafeGuardChatAdvisor#REFUSAL}、
 *       上游命中数<strong>一次都没增加</strong>；
 *   <li><strong>注册</strong>：生产上下文的 {@code List<Advisor>} 里真有这两个 advisor —— 删掉 {@code @Component}
 *       时这一条与第 ② 条一起红（金丝雀的"删注册"侧）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // ★ 词表走配置（运营口径），不是写死在代码里 —— 与生产的注入点逐字同一个键
      "app.chat.guard.sensitive-words=绝密",
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=1200",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class SafeGuardHttpE2ETest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ADMIN = "admin";
  private static final String ADMIN_PASSWORD = "admin";

  /** 上游桩的固定回答 —— 断言里逐字核它，避免"只要有字就算通了"。 */
  private static final String UPSTREAM_ANSWER = "上游回答：差旅住宿标准为每人每天500元。";

  private static final String SENSITIVE = "绝密";
  private static final String SENSITIVE_QUESTION = "请把" + SENSITIVE + "材料发我";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** 上游桩收到的请求数（只增不减；用例各自取差值）。 */
  private static final AtomicInteger upstreamHits = new AtomicInteger();

  private static HttpServer upstream;

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired AdvisorChainTrace trace;

  /** 与生产 {@code SpringAiChatAdapter} 注入的是<strong>同一个</strong> {@code List<Advisor>}。 */
  @Autowired List<Advisor> advisors;

  @BeforeAll
  static void startUpstream() throws IOException {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/chat/completions", SafeGuardHttpE2ETest::handle);
    upstream.start();
  }

  @AfterAll
  static void stopUpstream() {
    if (upstream != null) {
      upstream.stop(0);
    }
  }

  // ────────────────────────── 判据 ──────────────────────────

  /**
   * ★ 对照：不含敏感词的问题必须真的打上游、真的回答案、真的走过 advisor 链。
   *
   * <p>★ 这一条是下一条的前提：它证明"链路是通的、模型是能被调起来的"，于是下一条的 "上游零命中"只能有一种解释 —— 被拦住了。
   */
  @Test
  void benignQuestionReachesTheUpstreamThroughTheAdvisorChain() throws Exception {
    seedChatModel();
    long streamsBefore = trace.streamCount();
    int hitsBefore = upstreamHits.get();

    Frames frames = chat(login(), body("差旅住宿标准是什么？"));

    assertThat(frames.events())
        .as("★ 真 HTTP 上必须仍是契约序列（换了 advisor 链不改协议）")
        .startsWith("meta", "refs")
        .endsWith("done")
        .contains("delta");
    assertThat(frames.deltaText()).as("对照：答案来自上游桩，而不是拒绝话术").contains(UPSTREAM_ANSWER);
    assertThat(upstreamHits.get() - hitsBefore)
        .as("★ 对照：不拦时上游必须被调用（否则下一条的零命中是假绿）")
        .isGreaterThan(0);
    assertThat(trace.streamCount() - streamsBefore)
        .as("★ 生产装配下流式必须经过 advisor 链（探针在链上才会计数）")
        .isGreaterThan(0);
  }

  /**
   * ★★ 判据④（HTTP 级）：敏感词在<strong>上游之前</strong>被拦，用户收到的是拒绝话术。
   *
   * <p>★ 三层断言缺一不可：① 正文是拒绝话术（不是上游答案）② 上游命中数零增量（拦在出站之前） ③ 落库的助手消息正文也是拒绝话术（不是只改了流、库里仍是原文）。
   */
  @Test
  void sensitiveQuestionIsRefusedBeforeAnyUpstreamCall() throws Exception {
    seedChatModel();
    int hitsBefore = upstreamHits.get();

    Frames frames = chat(login(), body(SENSITIVE_QUESTION));

    assertThat(frames.text())
        .as("★ 用户收到的正文必须是拒绝话术，而不是上游答案")
        .contains(SafeGuardChatAdvisor.REFUSAL)
        .doesNotContain(UPSTREAM_ANSWER);
    assertThat(upstreamHits.get() - hitsBefore).as("★★ 判据④（HTTP 级）：命中敏感词时上游一次都不该被调用").isZero();
    assertThat(frames.dataOf("done"))
        .as("★ 拦截不是失败：这一轮仍以 GENERATED 收尾（拒绝话术就是回答）")
        .contains("\"status\":\"GENERATED\"");
  }

  /**
   * ★ 金丝雀的"注册"侧：生产上下文里真有这两个 advisor。
   *
   * <p>删掉 {@code SafeGuardChatAdvisor} 或 {@code AdvisorChainTrace} 的 {@code @Component} 时这条红；
   * 只删测试里手工传的 {@code List.of(...)} 则<strong>不会</strong>红 —— 两种形状的差别正是 "生产装配有没有被钉住"。
   */
  @Test
  void productionContextRegistersTheGuardAndTheProbe() {
    assertThat(advisors.stream().map(Advisor::getName).toList())
        .as("★ 生产 advisor 链里必须有安全闸门与探针（order 100 / 150）")
        .contains("safeguard-chat", "advisor-chain-trace");
    assertThat(advisors)
        .filteredOn(advisor -> advisor instanceof SafeGuardChatAdvisor)
        .singleElement()
        .satisfies(advisor -> assertThat(advisor.getOrder()).isEqualTo(100));
    assertThat(trace.getOrder()).as("探针的 order 必须是 150").isEqualTo(150);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 插一行"已加载的对话模型"，上游指向本类的桩（与平台自己的种子同一形状，不新造字段）。 */
  private void seedChatModel() {
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, '守卫夹具对话模型', 'CHAT', 'LOCAL_INFERENCE',"
            + " 'stub-chat', ?, 'LOADED', true, now(), now())",
        UUID.randomUUID(),
        base() + "/v1");
  }

  private String login() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(httpBase() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("种子账号必须能登录").isEqualTo(200);
    String body = response.body();
    int at = body.indexOf("\"accessToken\"");
    assertThat(at).as("登录响应里必须有 accessToken：" + body).isGreaterThan(-1);
    int start = body.indexOf('"', body.indexOf(':', at) + 1) + 1;
    return body.substring(start, body.indexOf('"', start));
  }

  private String body(String question) {
    return "{\"question\":\"" + question + "\",\"attachmentIds\":[],\"thinking\":false}";
  }

  private Frames chat(String token, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(httpBase() + "/api/v1/conversations/chat"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).as("SSE 端点必须是 200").isEqualTo(200);
    try (InputStream stream = response.body()) {
      return new Frames(readFrames(stream));
    }
  }

  // ────────────────────────── SSE 读取 ──────────────────────────

  /** 真 HTTP 上读到的帧（只留事件名与 data；注释帧=心跳，不参与业务断言）。 */
  private record Frames(List<Frame> frames) {

    List<String> events() {
      return frames.stream().map(Frame::event).toList();
    }

    String text() {
      return frames.stream().map(Frame::data).collect(Collectors.joining());
    }

    /** 所有 {@code delta} 帧的 data 拼起来（正文增量）。 */
    String deltaText() {
      return frames.stream()
          .filter(frame -> "delta".equals(frame.event()))
          .map(Frame::data)
          .collect(Collectors.joining());
    }

    String dataOf(String event) {
      return frames.stream()
          .filter(frame -> event.equals(frame.event()))
          .map(Frame::data)
          .findFirst()
          .orElseThrow(() -> new AssertionError("响应里没有 event: " + event + " 的帧"));
    }
  }

  private record Frame(String event, String data) {}

  private static List<Frame> readFrames(InputStream stream) throws IOException {
    List<Frame> frames = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    String event = null;
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty()) {
        if (event != null) {
          frames.add(new Frame(event, data.toString()));
        }
        event = null;
        data.setLength(0);
      } else if (line.startsWith(":")) {
        // 心跳/开流填充注释帧：不参与业务断言
      } else if (line.startsWith("event:")) {
        event = line.substring("event:".length()).trim();
      } else if (line.startsWith("data:")) {
        data.append(line.substring("data:".length()).trim());
      }
    }
    if (event != null) {
      frames.add(new Frame(event, data.toString()));
    }
    return frames;
  }

  // ────────────────────────── 上游桩 ──────────────────────────

  /**
   * 一个最小的 OpenAI 兼容上游：{@code stream:true} 回逐块 SSE，否则回一次性 JSON。
   *
   * <p>★ 请求体里带 {@code "stream":true} 才回 SSE —— 与真实上游同一条判据；如果平台把流式请求发成非流式， 这里会回 JSON，而 {@code delta}
   * 帧就一个都不会有（于是上面的断言红），不是静默通过。
   */
  private static void handle(HttpExchange exchange) throws IOException {
    upstreamHits.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (body.contains("\"stream\":true")) {
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        for (String frame : sseFrames()) {
          out.write(frame.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      }
      return;
    }
    String json =
        "{\"choices\":[{\"message\":{\"content\":\""
            + UPSTREAM_ANSWER
            + "\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static List<String> sseFrames() {
    return List.of(
        frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"),
        frame("{\"choices\":[{\"delta\":{\"content\":\"" + UPSTREAM_ANSWER + "\"}}]}"),
        frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"),
        frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}"),
        "data: [DONE]\n\n");
  }

  private static String frame(String data) {
    return "data: " + data + "\n\n";
  }

  private String base() {
    return "http://127.0.0.1:" + upstream.getAddress().getPort();
  }

  private String httpBase() {
    return "http://localhost:" + port;
  }
}
