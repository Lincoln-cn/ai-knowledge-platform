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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code #198} 第五块（版本列表 + 单版图快照）的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>为什么必须走真库</h3>
 *
 * <p>这几条判据里有两条只存在于数据库里：① 列表的"降序"与"节点数"来自 {@code as_workflow_version} 的行与 {@code graph} 快照； ②
 * "别的应用的版本读不到"只有在<strong>真的存在另一个应用的另一行</strong>时才验得出来 —— 假仓储里永远只有一条，这条断言会退化成"恒绿"。
 *
 * <h3>读数</h3>
 *
 * <ol>
 *   <li>同一应用发两次 ⇒ 列表按 {@code version} 降序，{@code nodeCount} = <strong>快照里</strong>{@code nodes} 的长度，
 *       且列表不带 {@code graph}；
 *   <li>详情取回发布那一刻的图快照；发布后再改草稿，快照<strong>不动</strong>；
 *   <li>★ 别的应用的版本 id / 版本号都读不到（404），且 404 里<strong>没有图</strong>；
 *   <li>从没发布过 ⇒ <b>200 + 空数组</b>（不是 404）；
 *   <li>★ 列表里的 {@code publishedByName}
 *       是<strong>读时</strong>解析的当前人名：发布之后改用户名，历史里立刻是新名（若名字在发布时被存进版本表，这条会红）；
 *   <li>★ 发布人被软删 ⇒ 列表仍 <b>200</b>，该条 {@code publishedByName} 为 {@code null}（前端显示「（未知用户）」）、{@code
 *       publishedBy} 仍是 uuid —— 不是 500，也不是把 uuid 当名字。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class WorkflowVersionHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 两个节点：开始 → AI 对话（starts=1、有可达输出、类型都在目录里）。 */
  private static final String TWO_NODE_GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"}]}";

  /** 三个节点：开始 → AI 对话 → 回复（用于让两版的 nodeCount 不相等）。 */
  private static final String THREE_NODE_GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"},"
          + "{\"id\":\"r1\",\"type\":\"REPLY_NODE\",\"title\":\"回复\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"},"
          + "{\"id\":\"e2\",\"source\":\"a1\",\"target\":\"r1\"}]}";

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Test
  void historyIsNewestFirstAndCountsNodesFromSnapshot() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "版本列表");

    JsonNode first = publish(admin, app, TWO_NODE_GRAPH, "第一版");
    JsonNode second = publish(admin, app, THREE_NODE_GRAPH, "第二版");
    assertThat(first.path("version").asInt()).isEqualTo(1);
    assertThat(second.path("version").asInt()).isEqualTo(2);

    HttpResponse<String> list =
        send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions");
    assertThat(list.statusCode()).as("列表：%s", list.body()).isEqualTo(200);

    JsonNode body = json.readTree(list.body());
    assertThat(body.path("code").asInt()).as("成功包络 code=0：%s", list.body()).isZero();
    JsonNode data = body.path("data");
    assertThat(data.isArray()).as("data 必须是数组：%s", list.body()).isTrue();
    assertThat(data.size()).as("两条版本").isEqualTo(2);

    assertThat(data.path(0).path("version").asInt()).as("★ 新的在前（降序）").isEqualTo(2);
    assertThat(data.path(0).path("id").asString()).isEqualTo(second.path("id").asString());
    assertThat(data.path(0).path("note").asString()).isEqualTo("第二版");
    assertThat(data.path(0).path("nodeCount").asInt()).as("★ 节点数从快照数：三个节点").isEqualTo(3);
    assertThat(data.path(0).has("graph")).as("★ 列表刻意不带图").isFalse();
    assertThat(data.path(0).path("publishedBy").asString()).as("发布人").isNotBlank();
    assertThat(data.path(0).path("publishedAt").asString()).as("发布时间").isNotBlank();

    assertThat(data.path(1).path("version").asInt()).isEqualTo(1);
    assertThat(data.path(1).path("id").asString()).isEqualTo(first.path("id").asString());
    assertThat(data.path(1).path("nodeCount").asInt()).isEqualTo(2);

    assertThat(data.path(0).path("nodeCount").asInt())
        .as("★ 与库里的快照对一次：nodeCount 是数出来的，不是存下来的")
        .isEqualTo(nodeLengthInDb(app, 2));
    assertThat(data.path(1).path("nodeCount").asInt()).isEqualTo(nodeLengthInDb(app, 1));
  }

  @Test
  void detailIsThePublishedSnapshotAndIgnoresLaterDraftEdits() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "快照不变");
    JsonNode first = publish(admin, app, TWO_NODE_GRAPH, "发布旧图");
    String versionId = first.path("id").asString();

    HttpResponse<String> byId =
        send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions/" + versionId);
    JsonNode graph = detailGraph(byId);
    assertThat(graph.path("nodes").size()).as("★ 响应体是裸图 {nodes, edges}，不是 {graph: …}").isEqualTo(2);
    assertThat(graph.path("edges").size()).isEqualTo(1);
    assertThat(graph.path("nodes").path(1).path("title").asString()).isEqualTo("AI 对话");

    // ★ 契约里这条路径是 `:version`（版本号），页面 previewVersion(item.version) 传的就是它 ⇒
    //   同一个版本两种写法都必须取得到。
    HttpResponse<String> byNumber =
        send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions/1");
    assertThat(byNumber.statusCode()).as("★ 按版本号也要能取：%s", byNumber.body()).isEqualTo(200);
    assertThat(detailGraph(byNumber).path("nodes").size()).isEqualTo(2);

    String edited = TWO_NODE_GRAPH.replace("AI 对话", "AI 对话（改过）");
    HttpResponse<String> saved = saveDraft(admin, app, edited);
    assertThat(saved.statusCode()).as("改草稿必须成功：%s", saved.body()).isEqualTo(200);

    JsonNode afterEdit =
        detailGraph(
            send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions/" + versionId));
    assertThat(afterEdit.path("nodes").size()).isEqualTo(2);
    assertThat(afterEdit.toString()).as("★ 已发布版本是快照：草稿改了它不动").doesNotContain("改过");

    JsonNode second = publish(admin, app, edited, "发布新图");
    assertThat(second.path("version").asInt()).isEqualTo(2);
    assertThat(
            detailGraph(
                    send(
                        admin,
                        "GET",
                        "/api/v1/assistants/" + app + "/workflow/versions/" + versionId))
                .toString())
        .as("★ 回过头再看 v1，还是旧图")
        .doesNotContain("改过");
  }

  @Test
  void anotherAppsVersionIsNotReadable() throws Exception {
    String admin = login("admin", "admin");
    UUID a = createWorkflowApp(admin, "甲的版本");
    UUID b = createWorkflowApp(admin, "乙的版本");
    JsonNode aFirst = publish(admin, a, TWO_NODE_GRAPH, "甲的 v1");
    JsonNode bFirst = publish(admin, b, THREE_NODE_GRAPH, "乙的 v1");
    assertThat(aFirst.path("version").asInt()).isEqualTo(1);
    assertThat(bFirst.path("version").asInt()).isEqualTo(1);

    // ① 拿「别的应用的版本 id」⇒ 必须 404，且不能把那张图给出去
    HttpResponse<String> crossApp =
        send(
            admin,
            "GET",
            "/api/v1/assistants/" + b + "/workflow/versions/" + aFirst.path("id").asString());
    assertThat(crossApp.statusCode())
        .as("★ 版本属于别的应用 ⇒ 404（不能返回它）：%s", crossApp.body())
        .isEqualTo(404);
    assertThat(crossApp.body()).contains("版本不存在：").doesNotContain("nodes");

    // ② 版本号只在应用内唯一：乙应用的 1 号必须是乙自己的图
    HttpResponse<String> bNumberOne =
        send(admin, "GET", "/api/v1/assistants/" + b + "/workflow/versions/1");
    assertThat(bNumberOne.statusCode()).as(bNumberOne.body()).isEqualTo(200);
    assertThat(detailGraph(bNumberOne).path("nodes").size())
        .as("★ 乙的 1 号是乙的图（三个节点），不是甲的（两个节点）")
        .isEqualTo(3);

    // ③ 从没发布过的应用，1 号也是 404 —— 版本号是"每个应用各自从 1 开始"，不是全局号
    UUID c = createWorkflowApp(admin, "丙的版本");
    HttpResponse<String> cNumberOne =
        send(admin, "GET", "/api/v1/assistants/" + c + "/workflow/versions/1");
    assertThat(cNumberOne.statusCode()).as(cNumberOne.body()).isEqualTo(404);
    assertThat(cNumberOne.body()).contains("版本不存在：1");

    // ④ 应用不存在 ⇒ 404「应用不存在」，与草稿/发布端点同一句文案
    UUID missing = UUID.randomUUID();
    HttpResponse<String> missingList =
        send(admin, "GET", "/api/v1/assistants/" + missing + "/workflow/versions");
    assertThat(missingList.statusCode()).as(missingList.body()).isEqualTo(404);
    assertThat(missingList.body()).contains("应用不存在：" + missing);

    HttpResponse<String> missingDetail =
        send(admin, "GET", "/api/v1/assistants/" + missing + "/workflow/versions/1");
    assertThat(missingDetail.statusCode()).as(missingDetail.body()).isEqualTo(404);
    assertThat(missingDetail.body()).contains("应用不存在：" + missing);
  }

  @Test
  void neverPublishedIsAnEmptyArrayNot404() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "还没发布");

    HttpResponse<String> list =
        send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions");
    assertThat(list.statusCode()).as("★ 从没发布过是正常状态 ⇒ 200，不是 404：%s", list.body()).isEqualTo(200);

    JsonNode body = json.readTree(list.body());
    assertThat(body.path("code").asInt()).isZero();
    JsonNode data = body.path("data");
    assertThat(data.isArray()).as("data 是空数组，不是 null：%s", list.body()).isTrue();
    assertThat(data.size()).isZero();
  }

  @Test
  void historyResolvesPublisherNameAtReadTimeNotAtPublishTime() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "发布人改名");

    JsonNode published = publish(admin, app, TWO_NODE_GRAPH, "第一版");
    String actorId = published.path("publishedBy").asString();
    assertThat(actorId).as("发布响应仍回 uuid（本块没改发布响应）").isNotBlank();
    String originalName = displayNameInDb(UUID.fromString(actorId));

    JsonNode row = versionList(admin, app).path(0);
    assertThat(row.path("publishedBy").asString()).as("id 仍原样回（前端放进 title）").isEqualTo(actorId);
    assertThat(row.path("publishedByName").asString())
        .as("★ 发布历史里给人看的是人名，不是 uuid")
        .isEqualTo(originalName)
        .isNotEqualTo(actorId);

    // ★ 这条是「为什么不在版本表里加 published_name 列」的反证：名字是读时解析的，改完立刻生效。
    String renamed = "改名后-" + UUID.randomUUID().toString().substring(0, 6);
    jdbc.update("update iam_user set display_name = ? where id = ?::uuid", renamed, actorId);
    try {
      assertThat(versionList(admin, app).path(0).path("publishedByName").asString())
          .as("★ 名字读时解析：发布之后改名，历史里立刻是新名（存快照的话这里还是旧名）")
          .isEqualTo(renamed);
    } finally {
      jdbc.update("update iam_user set display_name = ? where id = ?::uuid", originalName, actorId);
    }
  }

  @Test
  void deletedPublisherShowsUnknownInsteadOfUuidOr500() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "发布人已删");
    publish(admin, app, TWO_NODE_GRAPH, "发布人随后被删");

    // 直接造「发布过、但人已经不在了」的那一行：让新用户真的去发布，还得先有 assistant:edit 且能登录，
    // 那测的是权限；这里要测的是「名字解析不到」这条分支。
    UUID ghost =
        createUser(admin, "ghost-" + UUID.randomUUID().toString().substring(0, 8), "将被删除的人");
    jdbc.update(
        "update as_workflow_version set published_by = ?::uuid"
            + " where assistant_id = ?::uuid and version = 1",
        ghost.toString(),
        app.toString());
    jdbc.update("update iam_user set deleted_at = now() where id = ?::uuid", ghost.toString());

    HttpResponse<String> list =
        send(admin, "GET", "/api/v1/assistants/" + app + "/workflow/versions");
    assertThat(list.statusCode()).as("★ 发布人解析不到不能让整页发布历史 500：%s", list.body()).isEqualTo(200);

    JsonNode row = json.readTree(list.body()).path("data").path(0);
    assertThat(row.path("publishedBy").asString())
        .as("id 仍要留着：前端把它挂进 title")
        .isEqualTo(ghost.toString());
    assertThat(row.has("publishedByName")).as("契约上这个字段始终在，null 才是「解析不到」：%s", list.body()).isTrue();
    assertThat(row.path("publishedByName").isNull())
        .as("★ 已删的用户 ⇒ null（前端显示「（未知用户）」），不是 500")
        .isTrue();
    assertThat(row.path("publishedByName").asString())
        .as("★ 也不能把 uuid 塞进人名那一格")
        .isNotEqualTo(ghost.toString());
  }

  // ────────────────────────── helpers ──────────────────────────

  /** 版本列表的 {@code data} 数组；顺手钉住 200。 */
  private JsonNode versionList(String token, UUID app) throws Exception {
    HttpResponse<String> list =
        send(token, "GET", "/api/v1/assistants/" + app + "/workflow/versions");
    assertThat(list.statusCode()).as("列表：%s", list.body()).isEqualTo(200);
    return json.readTree(list.body()).path("data");
  }

  /** 用户表里的当前显示名 —— 断言「页面上的名字」与「库里的名字」是同一个。 */
  private String displayNameInDb(UUID userId) {
    return jdbc.queryForObject(
        "select display_name from iam_user where id = ?::uuid", String.class, userId.toString());
  }

  /**
   * 建一个用户（沿用 admin 的角色；这里不测权限，只测「名字能不能解析到」）。
   *
   * <p>★ 不传 {@code password}：走默认初始口令，免得踩密码策略 —— 这个用户根本不用登录。
   */
  private UUID createUser(String token, String username, String displayName) throws Exception {
    String roleId =
        jdbc.queryForObject(
            "select role_id::text from iam_user where username = 'admin'", String.class);
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\""
                + displayName
                + "\",\"roleId\":\""
                + roleId
                + "\"}");
    assertThat(response.statusCode()).as("建用户：%s", response.body()).isEqualTo(200);
    return UUID.fromString(json.readTree(response.body()).path("data").path("id").asString());
  }

  private JsonNode publish(String token, UUID app, String graph, String note) throws Exception {
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/assistants/" + app + "/workflow/publish",
            "{\"graph\":" + graph + ",\"note\":\"" + note + "\"}");
    assertThat(response.statusCode()).as("发布：%s", response.body()).isEqualTo(200);
    return json.readTree(response.body()).path("data");
  }

  /**
   * 保存草稿。
   *
   * <p>★ body 用<strong>字符串</strong>形状（{@code {"graph":"<json>"}}），因为后端 {@code SaveBody} 的字段是 {@code
   * String}；契约 {@code saveWorkflowDraft} 传的是对象 —— 两者今天并不一致（既有缺陷，不在本块范围）。
   */
  private HttpResponse<String> saveDraft(String token, UUID app, String graph) throws Exception {
    return send(
        token,
        "PUT",
        "/api/v1/assistants/" + app + "/workflow/draft",
        "{\"graph\":\"" + jsonStringLiteral(graph) + "\"}");
  }

  /** 详情响应里的图本身 —— 顺带钉住"没有 {graph: …} 包一层"。 */
  private JsonNode detailGraph(HttpResponse<String> response) {
    assertThat(response.statusCode()).as("详情：%s", response.body()).isEqualTo(200);
    JsonNode data = json.readTree(response.body()).path("data");
    assertThat(data.isObject()).as("★ data 必须是图对象本身：%s", response.body()).isTrue();
    return data;
  }

  private int nodeLengthInDb(UUID app, int version) {
    Integer length =
        jdbc.queryForObject(
            "select jsonb_array_length(graph -> 'nodes') from as_workflow_version"
                + " where assistant_id = ?::uuid and version = ?",
            Integer.class,
            app.toString(),
            version);
    return length == null ? 0 : length;
  }

  /**
   * 建一个<b>编排</b>应用。
   *
   * <p>★ 请求体里<b>没有</b> {@code modelId}：编排应用没有应用级模型（模型在各节点里）。
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
                + "\",\"icon\":\"🧩\",\"description\":\"版本读取读数\","
                + "\"type\":\"WORKFLOW\",\"rolePrompt\":\"你是编排读数助手\"}");
    assertThat(response.statusCode()).as("建应用：%s", response.body()).isEqualTo(200);
    String id = json.readTree(response.body()).path("data").path("id").asString();
    assertThat(id).as("建应用必须回 id：%s", response.body()).isNotNull();
    return UUID.fromString(id);
  }

  private HttpResponse<String> send(String token, String method, String path) throws Exception {
    return send(token, method, path, null);
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
    return json.readTree(response.body()).path("data").path("accessToken").asString();
  }

  private String base() {
    return "http://localhost:" + port;
  }

  /** 把一段 JSON 变成能塞进 JSON 字符串字面量里的内容（只处理引号与反斜杠，够用了）。 */
  private static String jsonStringLiteral(String json) {
    return json.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
