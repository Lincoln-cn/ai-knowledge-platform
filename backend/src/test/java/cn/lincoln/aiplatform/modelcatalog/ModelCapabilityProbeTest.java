package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M0 三份技术结论的探针（{@code ROADMAP §4.4} 0.0.1 验收第 ④ 条 / {@code §6.1 P3}）。
 *
 * <p>★ 这不是一个"断言某个值"的测试，而是一个<strong>把证据打印出来的探针</strong>： 三份结论的内容是"实测结果表"，只有对着真模型跑才有值。所以它的断言很薄
 * （保证探针本身没崩），结论靠 {@code System.out} 的记录窗口转抄进 {@code docs/技术结论/M0-技术结论.md}。
 *
 * <p>★ 它<strong>不装模型也能进测试套件</strong>：Ollama 不可达、或没声明 {@code AI_PROBE_MODELS} 时，{@code @BeforeAll} 的
 * assumption 会让整个类被<em>跳过</em> 而不是失败。否则 {@code mvn verify} 会在任何没装 9GB 模型的机器上变红， 那等于逼着后来的人把这个类删掉。
 *
 * <p>用法：
 *
 * <pre>
 * $env:AI_PROBE_MODELS = "模型A,模型B"
 * mvn -B test "-Dtest=ModelCapabilityProbeTest"
 * </pre>
 */
@SpringBootTest
@Testcontainers
class ModelCapabilityProbeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  /** 与 application.yml 的 spring.ai.openai.base-url 同一个默认值（Ollama 的 OpenAI 兼容端点）。 */
  private static final String BASE_URL =
      System.getenv().getOrDefault("AI_BASE_URL", "http://localhost:11434/v1");

  private static final String PROBE_MODELS = System.getenv().getOrDefault("AI_PROBE_MODELS", "");

  /** 一个必须"想一下"才能答对的问题 —— 不支持思考的模型会直接给出错误答案。 */
  private static final String TRICKY_QUESTION =
      "一个水池有甲乙两管。甲管单独注满需 6 小时，乙管单独注满需 12 小时，" + "池底有一个排水口单独排空需 8 小时。三处同时打开，几小时能注满？只答数字。";

  @Autowired ChatClient chatClient;

  @BeforeAll
  static void requireLocalModel() {
    assumeTrue(
        upstreamReachable(), "Ollama 不可达（" + BASE_URL + "）⇒ 跳过模型探针。装好模型后再跑，见 docs/技术结论/M0-技术结论.md");
    // ★ 反向保护：桩服务（backend/tools/openai_stub.py）默认也监听 11434，而它的"三态""稳定性"
    //   全是设定值。拿它当结论等于用自己写的答案考自己 —— 这里主动识别并拒绝，
    //   免得将来有人（包括未来的我）看到一堆漂亮数字就填进结论文档。
    assumeTrue(!looksLikeStub(), "★ 端口上跑着 OpenAI 桩服务（模型 id 以 stub- 开头）⇒ 跳过：那是设定值，不是模型能力的证据");
    assumeTrue(
        !models().isEmpty(), "未声明 AI_PROBE_MODELS ⇒ 跳过。例：$env:AI_PROBE_MODELS = \"qwen3:8b\"");
    System.out.println("=== 探针开始：模型 = " + models() + " ===");
  }

  // ────────────────────────── 结论一：思考三态 ──────────────────────────

  /**
   * 三态判定（口径见 {@code frontend/src/api/models.ts} 的 {@code ThinkingMode}）。
   *
   * <p>★ 这里用<strong>原始 HTTP</strong>而不是 Spring AI 取正文，是为了拿到完整的响应 JSON —— 思考内容在 OpenAI 兼容协议里通常走
   * {@code reasoning_content} 之类的<em>独立字段</em>， 只看 {@code content} 会把"模型在思考"误判成"模型没思考"。
   */
  @ParameterizedTest
  @MethodSource("probeModels")
  void 结论一_思考三态(String model) {
    RawResponse byDefault = rawChat(model, null);
    RawResponse thinkingOff = rawChat(model, "none");

    boolean sawDefault = sawReasoning(byDefault);
    boolean sawOff = sawReasoning(thinkingOff);

    String verdict;
    if (!sawDefault) {
      verdict = "NONE";
    } else if (sawOff) {
      verdict = "ALWAYS_ON?（★ 先排除'开关没下发'，见结论二）";
    } else {
      verdict = "TOGGLEABLE";
    }

    System.out.printf(
        "[结论一] %-24s → %-38s 默认:%-4s 关思考:%-4s (HTTP %d/%d)%n",
        model, verdict, yesNo(sawDefault), yesNo(sawOff), byDefault.status(), thinkingOff.status());
  }

  // ────────────────────────── 结论二：能否下发思考开关 ──────────────────────────

  /**
   * 两侧分开测 —— 这样能把问题定位到"框架没透出"还是"上游不认这个字段"， 而这两者的处置完全不同（前者走原生适配器，后者要改用 Ollama 原生 {@code think} 参数）。
   */
  @Test
  void 结论二_思考开关的两侧对照() {
    String model = models().get(0);

    RawResponse upstreamDefault = rawChat(model, null);
    RawResponse upstreamOff = rawChat(model, "none");

    String frameworkDefault = chat(model, null);
    String frameworkOff = chat(model, "none");

    boolean upstreamAcceptsStatus = upstreamOff.status() == 200;
    boolean upstreamChanged = !upstreamDefault.body().equals(upstreamOff.body());
    boolean frameworkChanged = !frameworkDefault.equals(frameworkOff);

    System.out.println("[结论二] 模型 = " + model);
    System.out.printf(
        "         上游侧: 带 reasoning_effort 的 HTTP 状态 = %d（%s）%n",
        upstreamOff.status(), upstreamAcceptsStatus ? "接受" : "★ 不接受这个字段");
    System.out.printf("                 响应体随开关变化 = %s%n", yesNo(upstreamChanged));
    System.out.printf(
        "         框架侧: 通过 Spring AI 传 reasoningEffort 后响应变化 = %s%n", yesNo(frameworkChanged));
    System.out.println("         ⇒ 判读：上游接受但框架侧无变化 ⇒ **框架没透出**，该调用点走原生适配器（R9 退路）");

    assertThat(upstreamDefault.status()).isEqualTo(200);
  }

  // ────────────────────────── 结论三：结构化输出稳定性 ──────────────────────────

  /** 单次成功没有意义 —— 我们的用法是每个请求都跑一遍，所以看的是 N 次里的失败率与失败形态。 */
  @ParameterizedTest
  @MethodSource("probeModels")
  void 结论三_结构化输出稳定性(String model) {
    int attempts = 20;
    int ok = 0;
    List<String> failures = new ArrayList<>();

    for (int i = 0; i < attempts; i++) {
      try {
        Extraction result =
            chatClient
                .prompt()
                .options(OpenAiChatOptions.builder().model(model))
                .user("从这句话里抽取标题与 3 个关键词，用 JSON 返回：" + "《2026 年第三季度安全生产检查通知》要求各车间在月底前完成设备巡检并提交台账。")
                .call()
                .entity(Extraction.class);

        if (result != null && result.title() != null && !result.title().isBlank()) {
          ok++;
        } else {
          failures.add("返回了空对象（★ 静默失败，最危险的一类）");
        }
      } catch (Exception e) {
        failures.add(e.getClass().getSimpleName());
      }
    }

    System.out.printf(
        "[结论三] %-24s → %d/%d 成功，失败率 %.0f%%，失败形态 = %s%n",
        model,
        ok,
        attempts,
        (attempts - ok) * 100.0 / attempts,
        failures.isEmpty() ? "无" : failures);

    assertThat(attempts).isPositive();
  }

  /** 结构化输出的目标形状。用 record 是因为 Spring AI 的转换器对它支持最好。 */
  record Extraction(String title, List<String> keywords) {}

  // ────────────────────────── 探针工具 ──────────────────────────

  /** 绕过 Spring AI 直接打 `/v1/chat/completions`，用来判定"上游"一侧的行为。 */
  private static RawResponse rawChat(String model, String reasoningEffort) {
    String effortPart =
        reasoningEffort == null ? "" : ",\"reasoning_effort\":\"" + reasoningEffort + "\"";
    String body =
        "{\"model\":\""
            + model
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
            + TRICKY_QUESTION
            + "\"}]"
            + effortPart
            + "}";

    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
              .timeout(Duration.ofMinutes(3))
              .header("Content-Type", "application/json")
              .header(
                  "Authorization",
                  "Bearer " + System.getenv().getOrDefault("AI_API_KEY", "ollama-local"))
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      return new RawResponse(response.statusCode(), response.body());
    } catch (Exception e) {
      return new RawResponse(-1, "{\"probeError\":\"" + e.getClass().getSimpleName() + "\"}");
    }
  }

  /** 经 Spring AI 走一遍（框架侧）。 */
  private String chat(String model, String reasoningEffort) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(model);
    if (reasoningEffort != null) {
      options.reasoningEffort(reasoningEffort);
    }
    try {
      String content = chatClient.prompt().options(options).user(TRICKY_QUESTION).call().content();
      return content == null ? "" : content;
    } catch (Exception e) {
      return "__ERROR__:" + e.getClass().getSimpleName();
    }
  }

  /** 端口上是不是桩服务（它的模型 id 以 stub- 开头，见 backend/tools/openai_stub.py）。 */
  private static boolean looksLikeStub() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BASE_URL + "/models"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      String body =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
      return body != null && body.contains("stub-");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean upstreamReachable() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BASE_URL + "/models"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      return HttpClient.newHttpClient()
              .send(request, HttpResponse.BodyHandlers.discarding())
              .statusCode()
          == 200;
    } catch (Exception e) {
      return false;
    }
  }

  static List<String> models() {
    return Stream.of(PROBE_MODELS.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  static Stream<String> probeModels() {
    return models().stream();
  }

  private static boolean sawReasoning(RawResponse response) {
    return response.body().contains("reasoning_content")
        || response.body().contains("\"reasoning\"");
  }

  private static String yesNo(boolean value) {
    return value ? "有" : "无";
  }

  private record RawResponse(int status, String body) {}
}
