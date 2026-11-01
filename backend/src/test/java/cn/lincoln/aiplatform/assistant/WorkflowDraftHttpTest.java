package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 草稿端点的<strong>传输形状</strong>读数（{@code #262}）。
 *
 * <h3>为什么单开一个类</h3>
 *
 * <p>这条端点曾经两边都写 {@code String}：库里存 jsonb 文本，线上也回文本 —— 于是画布对着真后端保存会 <b>400</b>、 读回来拿到的是一个被引号包住的字符串。而
 * mock 一直按对象回，两种模式下的页面读数完全一样（mock 能跑、 真后端不能），这正是 {@code silent-failure}
 * 的形状：<strong>错的那一侧只有在真栈上才看得见</strong>。
 *
 * <h3>读数</h3>
 *
 * <ol>
 *   <li>存进去的是对象、读回来的也是对象（{@code data.graph.nodes} 是数组，不是字符串）；
 *   <li>没保存过 ⇒ 空图 + {@code updatedAt: null}（"还没画"是正常状态，不是 404）；
 *   <li>★ 传字符串形状（{@code {"graph":"{...}"}}）⇒ <b>400</b>，不是"存下来再说"—— 放行它会存成一层转义，
 *       读回来仍然不是图，症状与改之前一模一样。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class WorkflowDraftHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 图上两个节点一条边（形状与画布导出的图一致）。 */
  private static final String GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"}]}";

  private final HttpClient http = HttpClient.newHttpClient();

  private final ObjectMapper mapper = new ObjectMapper();

  @LocalServerPort int port;

  @Test
  void theDraftRoundTripsAsAGraphObject() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "草稿形状");

    HttpResponse<String> saved = saveDraft(admin, app, "{\"graph\":" + GRAPH + "}");
    assertThat(saved.statusCode()).as("保存草稿：%s", saved.body()).isEqualTo(200);
    assertThat(dataField(saved.body(), "savedAt")).as("回 savedAt").isNotNull();

    HttpResponse<String> read =
        send("GET", "/api/v1/assistants/" + app + "/workflow/draft", admin, null);
    assertThat(read.statusCode()).as(read.body()).isEqualTo(200);

    Object graph = dataField(read.body(), "graph");
    assertThat(graph).as("★ 读回来必须是对象，不是被引号包住的字符串：%s", read.body()).isInstanceOf(Map.class);

    Map<String, Object> asMap = (Map<String, Object>) graph;
    assertThat((List<?>) asMap.get("nodes")).as("两个节点").hasSize(2);
    assertThat((List<?>) asMap.get("edges")).as("一条边").hasSize(1);
    assertThat(read.body()).as("标题原样带回（没有被二次转义）").contains("AI 对话").doesNotContain("\\\"nodes\\\"");
    assertThat(dataField(read.body(), "updatedAt")).as("保存过 ⇒ updatedAt 有值").isNotNull();
  }

  @Test
  void noDraftYetComesBackAsAnEmptyGraph() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "没画过");

    HttpResponse<String> read =
        send("GET", "/api/v1/assistants/" + app + "/workflow/draft", admin, null);
    assertThat(read.statusCode()).as("★ 没草稿是 200 + 空图，不是 404：%s", read.body()).isEqualTo(200);

    Map<String, Object> asMap = (Map<String, Object>) dataField(read.body(), "graph");
    assertThat((List<?>) asMap.get("nodes")).as("空图").isEmpty();
    assertThat((List<?>) asMap.get("edges")).as("空图").isEmpty();
    assertThat(dataField(read.body(), "updatedAt")).as("还没画过 ⇒ updatedAt 为 null").isNull();
  }

  @Test
  void aStringGraphIsRejected() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "字符串形状");

    HttpResponse<String> response =
        saveDraft(admin, app, "{\"graph\":\"{\\\"nodes\\\":[],\\\"edges\\\":[]}\"}");

    assertThat(response.statusCode()).as("字符串形状必须被拒：%s", response.body()).isEqualTo(400);
    assertThat(response.body()).contains("图对象");

    HttpResponse<String> read =
        send("GET", "/api/v1/assistants/" + app + "/workflow/draft", admin, null);
    Map<String, Object> asMap = (Map<String, Object>) dataField(read.body(), "graph");
    assertThat((List<?>) asMap.get("nodes")).as("★ 被拒的保存没有留下任何东西").isEmpty();
  }

  // ────────────────────────── helpers ──────────────────────────

  private HttpResponse<String> saveDraft(String token, UUID app, String body) throws Exception {
    return send("PUT", "/api/v1/assistants/" + app + "/workflow/draft", token, body);
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/auth/login",
            null,
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(response.statusCode()).as("登录：%s", response.body()).isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private UUID createWorkflowApp(String token, String prefix) throws Exception {
    String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/assistants",
            token,
            "{\"name\":\""
                + name
                + "\",\"icon\":\"🧩\",\"description\":\"草稿形状读数\","
                + "\"type\":\"WORKFLOW\",\"rolePrompt\":\"你是编排读数助手\"}");
    assertThat(response.statusCode()).as("建应用：%s", response.body()).isEqualTo(200);
    return UUID.fromString(stringField(response.body(), "id"));
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private Object dataField(String json, String field) throws Exception {
    Map<String, Object> body = mapper.readValue(json, new TypeReference<>() {});
    Object data = body.get("data");
    return data instanceof Map ? ((Map<String, Object>) data).get(field) : null;
  }

  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return end < 0 ? null : json.substring(start, end);
  }
}
