package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Spring AI ↔ OpenAI 桩服务的契约测试。
 *
 * <p>★ 它测的**不是模型能力**，而是<strong>我们这一侧的行为</strong>。两件事只有它能回答：
 *
 * <ol>
 *   <li><strong>Spring AI 实际把请求打到了哪个路径</strong>。`base-url` 写成 {@code http://host/v1} 而客户端的默认路径又是
 *       {@code /v1/chat/completions} 时， 真实请求会变成 {@code /v1/v1/chat/completions} —— 这类错误的表现是"调用
 *       404"， 而排查时人会先去怀疑模型和网络。桩服务把每个请求的<em>完整路径</em>记下来， 于是这个问题在第一次跑测试时就现形。
 *   <li><strong>Spring AI 有没有把 {@code reasoning_effort} 发出去</strong>。 这正是 M0 结论二的"框架侧"那一半 —— 真
 *       Ollama 不会告诉你它收到了什么， 而桩服务会。★ 注意它<em>只能</em>证明"框架发了没有"， 不能证明"上游认不认这个字段"（后者仍要在真模型上测）。
 * </ol>
 *
 * <p>桩服务不在时整体跳过（assumption），所以它进了测试套件也不会让 {@code mvn verify} 在没起桩的机器上变红。启动方式：
 *
 * <pre>python backend/tools/openai_stub.py</pre>
 */
@SpringBootTest
@Testcontainers
class OpenAiStubContractTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 桩服务的根（不含 /v1）—— 它是 base-url 去掉末段后的地址。 */
  private static final String STUB_ROOT =
      System.getenv().getOrDefault("AI_STUB_ROOT", "http://localhost:11434");

  private static final Pattern PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

  @Autowired ChatClient chatClient;

  @BeforeAll
  static void requireStub() {
    assumeTrue(
        stubReachable(), "桩服务不在 " + STUB_ROOT + " ⇒ 跳过。启动：python backend/tools/openai_stub.py");
  }

  @BeforeEach
  void resetStubRecords() {
    send("POST", STUB_ROOT + "/__stub/reset", "{}");
  }

  @Test
  void 桩服务能被SpringAI连上并返回内容() {
    String answer = ask("stub-plain", null);

    assertThat(answer).as("Spring AI 拿到正文 ⇒ 请求形状被它接受了").isNotBlank();
    System.out.println("[契约] 非流式回答：" + answer);
  }

  @Test
  void 桩服务记录了SpringAI实际请求的完整路径() {
    ask("stub-plain", null);

    List<String> paths = recordedPaths();
    System.out.println("[契约] ★ Spring AI 实际请求的路径：" + paths);

    assertThat(paths).as("桩没收到任何请求 ⇒ Spring AI 根本没打过来").isNotEmpty();
    assertThat(paths.get(0))
        .as("路径里出现两层 /v1 ⇒ application.yml 的 base-url 多写了一层")
        .doesNotContain("/v1/v1/");
  }

  /**
   * ★ M0 结论二的「框架侧」。
   *
   * <p>断言的是<strong>我们的期望</strong>（给了 {@code reasoningEffort} 就该在请求体里看到它）。
   * 如果这条红了，那结论②的答案就已经出来了：<em>框架没透出</em> —— 于是该调用点要走原生适配器（R9 的退路），而不是继续在 Ollama 参数上找原因。
   */
  @Test
  void 框架侧_给了reasoningEffort请求体里就该有它() {
    ask("stub-think-toggleable", "none");

    String records = fetchRecords();
    System.out.println("[契约] ★ 请求体里是否出现 reasoning_effort：" + records.contains("reasoning_effort"));

    assertThat(records)
        .as("★ 框架没把 reasoning_effort 发出去 —— 这就是结论二的答案（走原生适配器）")
        .contains("reasoning_effort");
  }

  @Test
  void 框架侧_不给参数时请求体里就没有它() {
    ask("stub-think-toggleable", null);

    assertThat(fetchRecords())
        .as("没给参数却发了字段 ⇒ 默认值被框架塞进去了，需查明来源")
        .doesNotContain("reasoning_effort");
  }

  /** 桩服务按 schema 生成合法 JSON ⇒ 这里验的是**解析链路**（不是模型会不会好好输出）。 */
  @Test
  void 结构化输出能被解析成record() {
    Extraction extraction =
        chatClient
            .prompt()
            .options(OpenAiChatOptions.builder().model("stub-plain"))
            .user("从这句话里抽取标题与关键词，用 JSON 返回：《安全生产检查通知》要求各车间月底前完成巡检。")
            .call()
            .entity(Extraction.class);

    assertThat(extraction).isNotNull();
    assertThat(extraction.title()).isNotBlank();
    assertThat(extraction.keywords()).as("关键词是数组 ⇒ 嵌套 schema 也被正确还原").isNotEmpty();
    System.out.println("[契约] 结构化输出解析结果：" + extraction);
  }

  @Test
  void embeddings返回配置的维度且可复现() {
    String first =
        send(
            "POST", STUB_ROOT + "/v1/embeddings", "{\"model\":\"stub-embed\",\"input\":\"同一段文本\"}");
    String again =
        send(
            "POST", STUB_ROOT + "/v1/embeddings", "{\"model\":\"stub-embed\",\"input\":\"同一段文本\"}");

    assertThat(first).as("维度应为 768（桩的 --dim 默认值）").contains("\"embedding\"");
    assertThat(vectorBody(first)).as("同一段文本两次必须得到同一个向量，否则检索类测试无法复现").isEqualTo(vectorBody(again));
  }

  record Extraction(String title, List<String> keywords) {}

  // ────────────────────────── 工具 ──────────────────────────

  private String ask(String model, String reasoningEffort) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(model);
    if (reasoningEffort != null) {
      options.reasoningEffort(reasoningEffort);
    }
    return chatClient.prompt().options(options).user("用一个词回答：你好").call().content();
  }

  private static boolean stubReachable() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(STUB_ROOT + "/v1/models"))
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

  private static String fetchRecords() {
    return send("GET", STUB_ROOT + "/__stub/requests", null);
  }

  private static List<String> recordedPaths() {
    Matcher matcher = PATH_PATTERN.matcher(fetchRecords());
    List<String> paths = new ArrayList<>();
    while (matcher.find()) {
      paths.add(matcher.group(1));
    }
    return paths;
  }

  /** 只保留 embedding 数组本身，便于比较（响应里还有 id/usage 等会变的部分）。 */
  private static String vectorBody(String embeddingJson) {
    int start = embeddingJson.indexOf("[", embeddingJson.indexOf("\"embedding\""));
    int end = embeddingJson.indexOf("]", start);
    return start >= 0 && end > start ? embeddingJson.substring(start, end + 1) : embeddingJson;
  }

  private static String send(String method, String url, String body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
      if (body == null) {
        builder.GET();
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body));
      }
      return HttpClient.newHttpClient()
          .send(builder.build(), HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (Exception e) {
      return "";
    }
  }
}
