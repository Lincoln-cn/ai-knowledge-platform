package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * {@code #198} 第六块（版本回滚 → 写回草稿）的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>★ 先把这个端点的语义写清楚，再看下面的读数</h3>
 *
 * <p>回滚 = <strong>把某一版的图写回草稿</strong>，<strong>不发布、不切换线上</strong>。用户回滚完还要自己点一次「发布」
 * 才生效。所以这里每一条断言只看两样东西：草稿（{@code GET …/workflow/draft}）与版本表（{@code as_workflow_version}）——
 * 没有一条断言去问「线上跑的是哪一版」，因为那由<strong>发布记录</strong>决定，回滚不碰它。
 *
 * <h3>为什么必须走真库（而不是 mock 一个仓储）</h3>
 *
 * <p>三条判据只存在于数据库里：① 「草稿真的变成了 v1 的图」要与库里的快照逐字段比；② 「回滚失败不留半截副作用」
 * 只有在草稿<strong>真的有一行</strong>时才验得出来（假仓储里永远「没改」，这条断言会退化成恒绿）；③ 「回滚不新增版本行」 要数 {@code
 * as_workflow_version} 的行数。
 *
 * <h3>读数</h3>
 *
 * <ol>
 *   <li>发布 v1、v2，把草稿改成第三个样子 ⇒ 回滚到 v1：200，且草稿<strong>等于 v1 的图</strong>（不是 v2、不是第三稿）；
 *   <li>★ 回滚到<strong>别的应用</strong>的版本 ⇒ 404，且本应用的草稿<strong>一字未动</strong>（失败不留副作用）；
 *   <li>回滚不存在的版本号（{@code 99}、以及既非版本号也非 UUID 的 {@code abc}） ⇒ 404，草稿同样未动；
 *   <li>★ 回滚不新增版本行：回滚前后列表长度、库里的行数、各版 id 全一样（「回滚只回草稿」）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class WorkflowRollbackHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 两个节点：开始 → AI 对话。 */
  private static final String TWO_NODE_GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"}]}";

  /** 三个节点：开始 → AI 对话 → 回复（与 v1 的节点数不同，回滚到 v2 会被抓出来）。 */
  private static final String THREE_NODE_GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s1\",\"type\":\"START_NODE\",\"title\":\"开始\"},"
          + "{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"title\":\"AI 对话\"},"
          + "{\"id\":\"r1\",\"type\":\"REPLY_NODE\",\"title\":\"回复\"}"
          + "],\"edges\":[{\"id\":\"e1\",\"source\":\"s1\",\"target\":\"a1\"},"
          + "{\"id\":\"e2\",\"source\":\"a1\",\"target\":\"r1\"}]}";

  /**
   * 第三个样子：开始 → 回复，<strong>节点 id 与 v1/v2 全不同</strong>。
   *
   * <p>★ 为什么非要与 v1、v2 都不同：只比「节点个数」的话，第三稿同样可以是 2 个节点，而「回滚把草稿改回了 v1」 与「回滚什么都没做」在个数上看不出区别 ——
   * 那时第二条以外的断言会变成恒绿。所以第三稿的<strong>内容</strong>必须可辨认。
   */
  private static final String THIRD_GRAPH =
      "{\"nodes\":["
          + "{\"id\":\"s9\",\"type\":\"START_NODE\",\"title\":\"第三稿开始\"},"
          + "{\"id\":\"r9\",\"type\":\"REPLY_NODE\",\"title\":\"第三稿回复\"}"
          + "],\"edges\":[{\"id\":\"e9\",\"source\":\"s9\",\"target\":\"r9\"}]}";

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Test
  void rollbackWritesThatVersionsGraphIntoTheDraft() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "回滚写回");

    publish(admin, app, TWO_NODE_GRAPH, "第一版");
    publish(admin, app, THREE_NODE_GRAPH, "第二版");
    HttpResponse<String> saved = saveDraft(admin, app, THIRD_GRAPH);
    assertThat(saved.statusCode()).as("把草稿改成第三稿：%s", saved.body()).isEqualTo(200);
    assertThat(draftGraph(admin, app).toString()).as("前提：草稿确实是第三稿").contains("第三稿");

    HttpResponse<String> rolled = rollback(admin, app, "1");
    assertThat(rolled.statusCode()).as("回滚到 v1：%s", rolled.body()).isEqualTo(200);
    JsonNode data = json.readTree(rolled.body()).path("data");
    assertThat(data.path("version").asInt())
        .as("★ 响应要带被回滚到的版本号（页面据此显示「已回滚到 v1」，不必再翻一次列表）")
        .isEqualTo(1);
    assertThat(data.path("updatedAt").asString())
        .as("★ 响应要带 updatedAt（页面据此显示「已保存」，不必用本地 Date.now() 猜）")
        .isNotBlank();
    assertThat(data.path("graph").isObject())
        .as("★ 响应里的 graph 是对象不是字符串：页面拿到就能画，别再多一层 JSON.parse：%s", rolled.body())
        .isTrue();
    assertThat(data.path("graph").path("nodes").size()).as("响应里的图是 v1 的两个节点").isEqualTo(2);

    JsonNode draft = draftGraph(admin, app);
    assertThat(draft.path("nodes").size()).as("★ 草稿回到 v1 的两节点（不是 v2 的三节点，也不是第三稿）").isEqualTo(2);
    assertThat(draft.toString()).as("★ 第三稿的标题必须从草稿里消失").doesNotContain("第三稿");
    assertThat(draft).as("★ 草稿的图要与 v1 的图快照逐字段相等").isEqualTo(versionGraph(admin, app, "1"));
    assertThat(draft).as("★ 也不能是 v2 的图").isNotEqualTo(versionGraph(admin, app, "2"));

    // 响应里的图与随后 GET 的草稿是同一份 —— 页面才敢直接拿返回值重画，不必再发一次 GET。
    assertThat(data.path("graph")).as("★ 回滚响应里的图 == 重新读到的草稿图").isEqualTo(draft);
    Instant rolledAt = Instant.parse(data.path("updatedAt").asString());
    Instant draftAt = Instant.parse(draftData(admin, app).path("updatedAt").asString());
    assertThat(Duration.between(rolledAt, draftAt).abs())
        .as("★ 响应里的 updatedAt 与草稿里记的是同一时刻（库是微秒精度，允许不足 1ms 的舍入差）")
        .isLessThan(Duration.ofMillis(2));
  }

  @Test
  void anotherAppsVersionIsNotRollbackableAndLeavesTheDraftUntouched() throws Exception {
    String admin = login("admin", "admin");
    UUID mine = createWorkflowApp(admin, "回滚本应用");
    UUID other = createWorkflowApp(admin, "回滚别的应用");

    // ★ 两边都发布过一版：否则"404"可能只是因为库里一行都没有，测出来的东西是假的。
    publish(admin, mine, TWO_NODE_GRAPH, "本应用第一版");
    JsonNode othersVersion = publish(admin, other, THREE_NODE_GRAPH, "别人家第一版");
    assertThat(othersVersion.path("version").asInt()).isEqualTo(1);

    HttpResponse<String> saved = saveDraft(admin, mine, THIRD_GRAPH);
    assertThat(saved.statusCode()).as("把草稿改成第三稿：%s", saved.body()).isEqualTo(200);
    JsonNode before = draftGraph(admin, mine);

    HttpResponse<String> crossed = rollback(admin, mine, othersVersion.path("id").asString());
    assertThat(crossed.statusCode())
        .as("★ 别的应用的版本 ⇒ 404（不是 403：存在性不该被泄露）：%s", crossed.body())
        .isEqualTo(404);
    assertThat(crossed.body()).as("★ 404 里不许带图（那本身就是泄露）").doesNotContain("nodes");
    assertThat(crossed.body()).as("文案要与读取侧同一句（同一处解析、同一句 404；两处各写一份迟早会分叉）").contains("版本不存在：");

    JsonNode after = draftGraph(admin, mine);
    assertThat(after).as("★ 失败不能留下半截副作用：草稿必须一字未动").isEqualTo(before);
    // ★ 不用「回复」这种字样当指纹：第三稿自己也带一个 REPLY_NODE（标题里就有「回复」），拿它判"是不是别人的图"
    //   会误判 —— 真正的指纹是别人那一版的**节点数**与**是否还是本应用的第三稿**。
    assertThat(after.path("nodes").size()).as("★ 尤其不能被写成别人那一版（三个节点）：%s", after).isEqualTo(2);
    assertThat(after.toString()).as("★ 本应用的第三稿还在（没有被别人那一版覆盖）").contains("第三稿开始");
    assertThat(versionList(admin, mine).size()).as("★ 失败也没有新增版本行").isEqualTo(1);
  }

  @Test
  void missingVersionIs404AndLeavesTheDraftUntouched() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "回滚缺版本");
    publish(admin, app, TWO_NODE_GRAPH, "唯一一版");

    HttpResponse<String> saved = saveDraft(admin, app, THIRD_GRAPH);
    assertThat(saved.statusCode()).as("把草稿改成第三稿：%s", saved.body()).isEqualTo(200);
    JsonNode before = draftGraph(admin, app);

    HttpResponse<String> tooHigh = rollback(admin, app, "99");
    assertThat(tooHigh.statusCode()).as("不存在的版本号 ⇒ 404：%s", tooHigh.body()).isEqualTo(404);
    assertThat(tooHigh.body()).as("文案要带上是哪一版").contains("版本不存在：99");

    HttpResponse<String> notAVersion = rollback(admin, app, "abc");
    assertThat(notAVersion.statusCode())
        .as("★ 既不是版本号也不是 UUID 的路径段也必须是 404（不是 500、不是 400）：%s", notAVersion.body())
        .isEqualTo(404);

    assertThat(draftGraph(admin, app)).as("★ 两次失败都不许碰草稿").isEqualTo(before);
  }

  @Test
  void rollbackDoesNotAppendAVersion() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "回滚不加版本");
    publish(admin, app, TWO_NODE_GRAPH, "第一版");
    publish(admin, app, THREE_NODE_GRAPH, "第二版");

    JsonNode before = versionList(admin, app);
    int rowsBefore = versionRowsInDb(app);
    assertThat(before.size()).as("前提：两版").isEqualTo(2);
    assertThat(rowsBefore).as("前提：库里两行").isEqualTo(2);

    HttpResponse<String> rolled = rollback(admin, app, "1");
    assertThat(rolled.statusCode()).as("回滚到 v1：%s", rolled.body()).isEqualTo(200);

    JsonNode after = versionList(admin, app);
    assertThat(after.size()).as("★ 回滚不发布 ⇒ 版本列表长度不变（没有凭空多出一行「回滚出来的 v3」）").isEqualTo(2);
    assertThat(after.path(0).path("version").asInt()).as("★ 最新仍是 v2：回滚没有「抢」一次版本号").isEqualTo(2);
    assertThat(after.path(0).path("id").asString()).isEqualTo(before.path(0).path("id").asString());
    assertThat(after.path(1).path("id").asString()).isEqualTo(before.path(1).path("id").asString());
    assertThat(after.path(1).path("nodeCount").asInt()).as("v1 的快照也没被改写").isEqualTo(2);
    assertThat(versionRowsInDb(app)).as("★ 库里的行数也不变（两个读数互证：接口说没加、库里也没有）").isEqualTo(rowsBefore);
  }

  // ────────────────────────── helpers ──────────────────────────

  /**
   * 回滚：{@code POST …/workflow/versions/{key}/rollback}。
   *
   * <p>★ 刻意<strong>不带请求体</strong>：版本号在路径里，图从版本行里读 —— 客户端给图就成了另一次"保存草稿"。
   */
  private HttpResponse<String> rollback(String token, UUID app, String key) throws Exception {
    return send(
        token, "POST", "/api/v1/assistants/" + app + "/workflow/versions/" + key + "/rollback");
  }

  /** 某一版的图（{@code GET …/versions/{key}}，响应体是裸图）。 */
  private JsonNode versionGraph(String token, UUID app, String key) throws Exception {
    HttpResponse<String> response =
        send(token, "GET", "/api/v1/assistants/" + app + "/workflow/versions/" + key);
    assertThat(response.statusCode()).as("版本图：%s", response.body()).isEqualTo(200);
    return json.readTree(response.body()).path("data");
  }

  /** 版本列表的 {@code data} 数组；顺手钉住 200。 */
  private JsonNode versionList(String token, UUID app) throws Exception {
    HttpResponse<String> list =
        send(token, "GET", "/api/v1/assistants/" + app + "/workflow/versions");
    assertThat(list.statusCode()).as("版本列表：%s", list.body()).isEqualTo(200);
    return json.readTree(list.body()).path("data");
  }

  /**
   * 草稿响应的 {@code data}（今天 {@code graph} 是<strong>字符串</strong>、{@code updatedAt} 是时刻）。
   *
   * <p>★ 这里断言 {@code graph} 是字符串，是把<strong>既有形状</strong>钉住：本块不顺手改草稿端点 —— 那会让另一个契约字段的形状在同一次提交里悄悄变掉。
   */
  private JsonNode draftData(String token, UUID app) throws Exception {
    HttpResponse<String> response =
        send(token, "GET", "/api/v1/assistants/" + app + "/workflow/draft");
    assertThat(response.statusCode()).as("读草稿：%s", response.body()).isEqualTo(200);
    JsonNode data = json.readTree(response.body()).path("data");
    assertThat(data.path("graph").isString())
        .as("★ 草稿的 graph 今天是 JSON 字符串（既有形状，本块不改）：%s", response.body())
        .isTrue();
    return data;
  }

  /** 草稿里存的图，解析成 JSON 对象（便于与版本快照做结构比较）。 */
  private JsonNode draftGraph(String token, UUID app) throws Exception {
    return json.readTree(draftData(token, app).path("graph").asString());
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

  private int versionRowsInDb(UUID app) {
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from as_workflow_version where assistant_id = ?::uuid",
            Integer.class,
            app.toString());
    return rows == null ? 0 : rows;
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
                + "\",\"icon\":\"🧩\",\"description\":\"回滚读数\","
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
