package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
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
 * {@code #198} 第四块（发布编排版本）的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>为什么必须走真库</h3>
 *
 * <p>这个端点的三条判据里有两条<strong>只存在于数据库里</strong>：① "校验不过不能留下任何行"是一次事务边界读数； ② 版本号递增靠 {@code unique
 * (assistant_id, version)} 兜底。两者在假仓储里都会退化成"内存里加一"， 那正好把最值得验的东西（取号与约束有没有配上）验成绿。
 *
 * <h3>读数</h3>
 *
 * <ol>
 *   <li>校验不过 ⇒ <b>400</b>，且 {@code as_workflow_version} 里 <b>0 行</b>；
 *   <li>同一张图连发两次 ⇒ {@code version} 依次是 1、2，每条发布在审计表里各留一行；
 *   <li>★ 发布后再改草稿，<b>已发布版本仍是旧图</b>（快照不跟随草稿）；
 *   <li>应用不存在 ⇒ 404「应用不存在」，与草稿端点同一句文案。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class WorkflowPublishHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String EMPTY_GRAPH = "{\"nodes\":[],\"edges\":[]}";

  /** 合法图：一个开始节点 → 一个 AI 对话节点（starts=1、有可达输出、无 ERROR）。 */
  private static final String GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"}]}";

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Test
  void rejectedGraphWritesNoRow() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "发布校验");

    HttpResponse<String> response = publish(admin, app, EMPTY_GRAPH, "空图试试");

    assertThat(response.statusCode()).as("校验不过 ⇒ 400：%s", response.body()).isEqualTo(400);
    assertThat(response.body())
        .as("★ 400 要带上 issues 明细，而不是一句「校验失败」")
        .contains("EMPTY_GRAPH")
        .contains("流程里没有任何节点");
    assertThat(rowCount(app)).as("★ 校验不过不能留下任何行").isZero();
  }

  @Test
  void consecutivePublishesTakeTheNextVersion() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "版本递增");

    HttpResponse<String> first = publish(admin, app, GRAPH, "第一版");
    assertThat(first.statusCode()).as("合法图 ⇒ 200：%s", first.body()).isEqualTo(200);
    assertThat(intField(first.body(), "version")).isEqualTo(1);
    assertThat(intField(first.body(), "nodeCount")).as("图上两个节点").isEqualTo(2);

    HttpResponse<String> second = publish(admin, app, GRAPH, "第二版");
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(intField(second.body(), "version")).as("第二次发布 = 2，不是覆盖第一版").isEqualTo(2);

    assertThat(rowCount(app)).as("两条版本行").isEqualTo(2);
    assertThat(rowCount(app, 1)).as("version=1 的行只有一条").isEqualTo(1);
    assertThat(auditCount(app)).as("★ 每次发布各留一条审计（同事务写入，不吞异常）").isEqualTo(2);
  }

  @Test
  void publishedSnapshotDoesNotFollowLaterDraftEdits() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "快照不跟随");

    HttpResponse<String> first = publish(admin, app, GRAPH, "发布旧图");
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    assertThat(intField(first.body(), "version")).isEqualTo(1);

    String edited = GRAPH.replace("AI 对话", "AI 对话（改过）").replace("\"开始\"", "\"开始（改过）\"");
    HttpResponse<String> saved = saveDraft(admin, app, edited);
    assertThat(saved.statusCode()).as("改草稿必须成功：%s", saved.body()).isEqualTo(200);

    String publishedV1 = publishedGraph(app, 1);
    assertThat(publishedV1).as("★ 已发布版本是快照：草稿改了它不动").contains("AI 对话").doesNotContain("改过");

    HttpResponse<String> second = publish(admin, app, edited, "发布新图");
    assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
    // 同一个应用里草稿只存最新一份，所以这里发的就是草稿当前的样子 —— v1 仍应是旧图。
    assertThat(publishedGraph(app, 2)).as("新一版才带上改动").contains("改过");
    assertThat(publishedGraph(app, 1)).as("★ 回过头再看 v1，还是旧图").doesNotContain("改过");
  }

  @Test
  void unknownAssistantIsTheSame404AsDraft() throws Exception {
    String admin = login("admin", "admin");
    UUID missing = UUID.randomUUID();

    HttpResponse<String> response = publish(admin, missing, GRAPH, "");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    assertThat(response.body()).contains("应用不存在：" + missing);
  }

  // ────────────────────────── helpers ──────────────────────────

  private HttpResponse<String> publish(String token, UUID app, String graph, String note)
      throws Exception {
    return send(
        token,
        "POST",
        "/api/v1/assistants/" + app + "/workflow/publish",
        "{\"graph\":" + graph + ",\"note\":\"" + note + "\"}");
  }

  /**
   * 保存草稿。
   *
   * <p>★ body 用<strong>字符串</strong>形状（{@code {"graph":"<json>"}}），因为后端 {@code SaveBody} 的字段是 {@code
   * String}；契约 {@code saveWorkflowDraft} 传的是对象 —— 两者今天并不一致（见提交说明里的自曝）。
   */
  private HttpResponse<String> saveDraft(String token, UUID app, String graph) throws Exception {
    return send(
        token,
        "PUT",
        "/api/v1/assistants/" + app + "/workflow/draft",
        "{\"graph\":\"" + jsonStringLiteral(graph) + "\"}");
  }

  /**
   * 建一个<b>编排</b>应用。
   *
   * <p>★ 请求体里<b>没有</b> {@code modelId}：编排应用没有应用级模型（模型在各节点里），{@code Assistant.requireTypeModelPair}
   * 与库里的 {@code chk_as_model_by_type} 都按这条规则拦，带上它会被 400 拒掉。
   */
  private UUID createWorkflowApp(String token, String prefix) throws Exception {
    String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\""
                + name
                + "\",\"icon\":\"🧩\",\"description\":\"发布编排版本读数\","
                + "\"type\":\"WORKFLOW\",\"rolePrompt\":\"你是编排读数助手\"}");
    assertThat(response.statusCode()).as("建应用：%s", response.body()).isEqualTo(200);
    String id = stringField(response.body(), "id");
    assertThat(id).as("建应用必须回 id：%s", response.body()).isNotNull();
    return UUID.fromString(id);
  }

  private int rowCount(UUID app) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from as_workflow_version where assistant_id = ?::uuid",
            Integer.class,
            app.toString());
    return count == null ? 0 : count;
  }

  private int rowCount(UUID app, int version) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from as_workflow_version where assistant_id = ?::uuid and version = ?",
            Integer.class,
            app.toString(),
            version);
    return count == null ? 0 : count;
  }

  private int auditCount(UUID app) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from pf_audit_log where action = 'workflow.publish' and resource_id ="
                + " ?",
            Integer.class,
            app.toString());
    return count == null ? 0 : count;
  }

  private String publishedGraph(UUID app, int version) {
    return jdbc.queryForObject(
        "select graph::text from as_workflow_version where assistant_id = ?::uuid and version = ?",
        String.class,
        app.toString(),
        version);
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(response.statusCode()).as("%s 必须能登录：%s", username, response.body()).isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private String base() {
    return "http://localhost:" + port;
  }

  /** 把一段 JSON 变成能塞进 JSON 字符串字面量里的内容（只处理引号与反斜杠，够用了）。 */
  private static String jsonStringLiteral(String json) {
    return json.replace("\\", "\\\\").replace("\"", "\\\"");
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

  private static Integer intField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int at = json.indexOf(':', keyAt) + 1;
    while (at < json.length() && json.charAt(at) == ' ') {
      at++;
    }
    int end = at;
    while (end < json.length()
        && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return end == at ? null : Integer.valueOf(json.substring(at, end));
  }
}
