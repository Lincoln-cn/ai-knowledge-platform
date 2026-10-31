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
 * {@code #198} 运行记录端点（{@code GET /assistants/{appId}/workflow/records}）的真 HTTP 读数（真 PostgreSQL，不
 * mock 仓储）。
 *
 * <h3>★★ 为什么这些行是"直接插表"插进去的</h3>
 *
 * <p>运行记录今天<strong>没有写入方</strong>：唯一的写入者是执行引擎（V1.0，{@code 08 §二} / {@code 09 §5.3}）， 而画布调试 {@code
 * POST …/workflow/debug} 刻意不写记录。所以这组用例只能直接往 {@code as_workflow_record} 里插行 ——
 * 而这恰好也验证了"读这一侧"与执行引擎<b>解耦</b>： 将来引擎落地、开始写记录时，这个端点一行都不用改。
 *
 * <p>★ 也正因为如此，本类<strong>不断言"跑一个流程然后记录出现一行"</strong>：那条路今天不存在，写成测试只会得到一个"永远绿的空壳"。
 * 真要验写入，属于执行引擎那一批（它的准入判据现在钉在 {@code caliber/WorkflowEngineAdmissionTest} 里）。
 *
 * <h3>读数</h3>
 *
 * <ol>
 *   <li>从没有过记录 ⇒ <b>200 + 空列表 + total 0</b>（不是 404，也不是 null）；
 *   <li>三行不同 {@code startedAt} / 不同 {@code status} ⇒ 按 {@code startedAt} <b>倒序</b>、契约字段齐全；
 *   <li>分页：{@code size=2} ⇒ 2 条 + {@code total=3}，第二页 1 条；
 *   <li>未知 {@code appId} ⇒ <b>404</b>「应用不存在」；
 *   <li>★ 应用 A 的记录<b>不出现</b>在应用 B 的列表里（隔离；{@code total} 也各算各的）；
 *   <li>可选筛选 {@code status} 生效；非法取值 ⇒ <b>400</b>（不是静默空列表）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class WorkflowRecordsHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 两节点轨迹（开始 → AI 对话），与 mock 夹具同形状。 */
  private static final String TWO_STEP_TRAIL =
      """
      [{"nodeId":"s1","type":"START_NODE","title":"开始","status":"SUCCEEDED",\
      "enteredAt":"2026-01-01T10:00:00Z","elapsedMs":12},\
      {"nodeId":"a1","type":"AI_CHAT_NODE","title":"AI 对话","status":"SUCCEEDED",\
      "enteredAt":"2026-01-01T10:00:00Z","elapsedMs":900}]\
      """;

  /** 一节点轨迹（在检索节点失败）。 */
  private static final String ONE_STEP_FAILED_TRAIL =
      """
      [{"nodeId":"s1","type":"START_NODE","title":"开始","status":"SUCCEEDED",\
      "enteredAt":"2026-01-01T11:00:00Z","elapsedMs":10},\
      {"nodeId":"q1","type":"SEARCH_DATASET_NODE","title":"检索合同制度","status":"FAILED",\
      "enteredAt":"2026-01-01T11:00:00Z","elapsedMs":90,"failReason":"向量服务超时"}]\
      """;

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Test
  void emptyHistoryIsEmptyListWithZeroTotalNot404() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "运行记录为空");

    HttpResponse<String> response = list(admin, app, "");
    assertThat(response.statusCode())
        .as("★ 从没跑过是正常状态 ⇒ 200，不是 404：%s", response.body())
        .isEqualTo(200);

    JsonNode body = json.readTree(response.body());
    assertThat(body.path("code").asInt()).as("成功包络 code=0：%s", response.body()).isZero();
    JsonNode data = body.path("data");
    assertThat(data.isObject()).as("data 必须是 PageResult 对象：%s", response.body()).isTrue();
    assertThat(data.path("list").isArray())
        .as("★ list 必须是空数组，不是 null：%s", response.body())
        .isTrue();
    assertThat(data.path("list").size()).as("★ 空列表").isZero();
    assertThat(data.path("total").asLong()).as("★ total = 0（不是 null、不是 1）").isZero();
    assertThat(data.path("page").asInt()).as("page 从 1 开始").isEqualTo(1);
    assertThat(data.path("size").asInt()).as("默认每页 20 条").isEqualTo(20);
  }

  @Test
  void rowsAreNewestFirstAndCarryEveryContractField() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "运行记录倒序");

    UUID first =
        insertRecord(app, "2026-01-01T10:00:00Z", "SUCCEEDED", "第一跑", TWO_STEP_TRAIL, null, 1000);
    UUID second =
        insertRecord(
            app,
            "2026-01-01T11:00:00Z",
            "FAILED",
            "第二跑",
            ONE_STEP_FAILED_TRAIL,
            "检索节点失败：向量服务超时",
            520);
    UUID third =
        insertRecord(app, "2026-01-01T12:00:00Z", "SUCCEEDED", "第三跑", TWO_STEP_TRAIL, null, 830);

    JsonNode data = data(list(admin, app, ""));
    JsonNode rows = data.path("list");
    assertThat(rows.size()).as("三行").isEqualTo(3);
    assertThat(data.path("total").asLong()).as("total 与条数同口径").isEqualTo(3);

    assertThat(rows.path(0).path("id").asString())
        .as("★ 新的在前（startedAt 倒序）：第一行是 12:00 那一跑")
        .isEqualTo(third.toString());
    assertThat(rows.path(1).path("id").asString()).isEqualTo(second.toString());
    assertThat(rows.path(2).path("id").asString()).isEqualTo(first.toString());

    assertThat(rows.path(0).path("startedAt").asString())
        .as("时间戳按 startedAt 原样给出")
        .startsWith("2026-01-01T12:00");

    // 契约字段齐全（frontend/src/api/workflow.ts 的 WorkflowRecord）
    JsonNode row = rows.path(0);
    assertThat(row.path("appId").asString()).as("appId").isEqualTo(app.toString());
    assertThat(row.has("conversationId")).as("★ conversationId 字段始终在（null 才是「不是来自会话」）").isTrue();
    assertThat(row.path("conversationId").isNull()).as("这次运行没有会话").isTrue();
    assertThat(row.path("input").asString()).as("input").isEqualTo("第三跑");
    assertThat(row.path("finalOutputPreview").asString())
        .as("finalOutputPreview")
        .isEqualTo("第三跑的回答摘要");
    assertThat(row.path("status").asString()).as("status").isEqualTo("SUCCEEDED");
    assertThat(row.path("failReason").isNull()).as("成功的那次 failReason 是 null").isTrue();
    assertThat(row.path("elapsedMs").asInt()).as("elapsedMs 是存下来的总耗时").isEqualTo(830);

    assertThat(row.path("nodeTrail").isArray())
        .as("★ nodeTrail 必须是 JSON 数组（存成对象时页面 for-of 会直接抛）：%s", row)
        .isTrue();
    assertThat(row.path("nodeTrail").size()).as("两节点轨迹").isEqualTo(2);
    assertThat(row.path("nodeTrail").path(0).path("nodeId").asString()).isEqualTo("s1");
    assertThat(row.path("nodeTrail").path(1).path("type").asString()).isEqualTo("AI_CHAT_NODE");

    // 失败那一条：failReason 给得出原因，轨迹里失败的节点保留执行当时的标题
    JsonNode failed = rows.path(1);
    assertThat(failed.path("status").asString()).isEqualTo("FAILED");
    assertThat(failed.path("failReason").asString()).isEqualTo("检索节点失败：向量服务超时");
    assertThat(failed.path("nodeTrail").path(1).path("status").asString()).isEqualTo("FAILED");
    assertThat(failed.path("nodeTrail").path(1).path("failReason").asString())
        .as("★ 轨迹里失败的节点名与原因是执行当时的快照")
        .isEqualTo("向量服务超时");
  }

  @Test
  void paginationKeepsTotalAndSecondPage() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "运行记录分页");
    for (int i = 1; i <= 3; i++) {
      insertRecord(
          app,
          "2026-01-01T1" + i + ":00:00Z",
          "SUCCEEDED",
          "第 " + i + " 跑",
          TWO_STEP_TRAIL,
          null,
          i * 100);
    }

    JsonNode firstPage = data(list(admin, app, "page=1&size=2"));
    assertThat(firstPage.path("list").size()).as("size=2 ⇒ 页内 2 条").isEqualTo(2);
    assertThat(firstPage.path("total").asLong()).as("★ total 是过滤后的总数 3，不是页内条数").isEqualTo(3);
    assertThat(firstPage.path("page").asInt()).isEqualTo(1);
    assertThat(firstPage.path("size").asInt()).isEqualTo(2);
    assertThat(firstPage.path("list").path(0).path("input").asString())
        .as("第一页第一条仍是最新那一跑")
        .isEqualTo("第 3 跑");

    JsonNode secondPage = data(list(admin, app, "page=2&size=2"));
    assertThat(secondPage.path("list").size()).as("第二页只剩 1 条").isEqualTo(1);
    assertThat(secondPage.path("list").path(0).path("input").asString())
        .as("★ 第二页是排序之后的那一条（先截断再排序会让它错位）")
        .isEqualTo("第 1 跑");
    assertThat(secondPage.path("total").asLong()).isEqualTo(3);
  }

  @Test
  void unknownAppIs404() throws Exception {
    String admin = login("admin", "admin");
    UUID ghost = UUID.randomUUID();

    HttpResponse<String> response = list(admin, ghost, "");
    assertThat(response.statusCode()).as("★ 未知应用 ⇒ 404：%s", response.body()).isEqualTo(404);
    JsonNode body = json.readTree(response.body());
    assertThat(body.path("message").asString())
        .as("文案与草稿/版本端点同一句（应用不存在），让人知道该查应用而不是查记录")
        .contains("应用不存在");
  }

  @Test
  void recordsOfAnotherAppNeverShowUp() throws Exception {
    String admin = login("admin", "admin");
    UUID appA = createWorkflowApp(admin, "运行记录隔离-A");
    UUID appB = createWorkflowApp(admin, "运行记录隔离-B");

    insertRecord(appA, "2026-01-01T10:00:00Z", "SUCCEEDED", "属于 A 的第一跑", TWO_STEP_TRAIL, null, 100);
    insertRecord(appA, "2026-01-01T11:00:00Z", "SUCCEEDED", "属于 A 的第二跑", TWO_STEP_TRAIL, null, 200);
    UUID onlyB =
        insertRecord(
            appB, "2026-01-01T12:00:00Z", "SUCCEEDED", "属于 B 的唯一一跑", TWO_STEP_TRAIL, null, 300);

    JsonNode listB = data(list(admin, appB, ""));
    assertThat(listB.path("total").asLong()).as("★ B 只数自己的记录").isEqualTo(1);
    assertThat(listB.path("list").size()).isEqualTo(1);
    assertThat(listB.path("list").path(0).path("id").asString()).isEqualTo(onlyB.toString());
    assertThat(listB.path("list").path(0).path("appId").asString()).isEqualTo(appB.toString());

    JsonNode listA = data(list(admin, appA, ""));
    assertThat(listA.path("total").asLong()).as("A 的两条不受 B 影响").isEqualTo(2);
    for (JsonNode row : listA.path("list")) {
      assertThat(row.path("appId").asString())
          .as("★ A 的列表里不许出现 B 的行（隔离）")
          .isEqualTo(appA.toString());
      assertThat(row.path("id").asString()).isNotEqualTo(onlyB.toString());
    }
  }

  @Test
  void statusFilterWorksAndUnknownStatusIsRejected() throws Exception {
    String admin = login("admin", "admin");
    UUID app = createWorkflowApp(admin, "运行记录筛选");
    insertRecord(app, "2026-01-01T10:00:00Z", "SUCCEEDED", "成功的一跑", TWO_STEP_TRAIL, null, 100);
    insertRecord(
        app, "2026-01-01T11:00:00Z", "FAILED", "失败的一跑", ONE_STEP_FAILED_TRAIL, "检索超时", 200);

    JsonNode failedOnly = data(list(admin, app, "status=FAILED"));
    assertThat(failedOnly.path("total").asLong()).as("★ total 也跟着筛选").isEqualTo(1);
    assertThat(failedOnly.path("list").path(0).path("status").asString()).isEqualTo("FAILED");

    HttpResponse<String> bad = list(admin, app, "status=RUNNING");
    assertThat(bad.statusCode())
        .as("★ 非法状态取值 ⇒ 400（静默空列表会被读成「没有记录」）：%s", bad.body())
        .isEqualTo(400);
    assertThat(bad.body()).as("400 要说清合法取值域").contains("SUCCEEDED");
  }

  // ────────────────────────── helpers ──────────────────────────

  /**
   * 往 {@code as_workflow_record} 直接插一行（今天没有写入方，见类注释）。
   *
   * <p>★ {@code final_output_preview} 用 "输入 + 的回答摘要" 造一个可辨认的值；失败那条给空串（契约里它是 {@code string}，不是
   * null）。
   */
  private UUID insertRecord(
      UUID app,
      String startedAt,
      String status,
      String input,
      String trail,
      String failReason,
      int elapsedMs) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into as_workflow_record
          (id, assistant_id, conversation_id, input, node_trail, final_output_preview,
           status, fail_reason, elapsed_ms, started_at)
        values (?::uuid, ?::uuid, null, ?, ?::jsonb, ?, ?, ?, ?, ?::timestamptz)
        """,
        id.toString(),
        app.toString(),
        input,
        trail,
        "SUCCEEDED".equals(status) ? input + "的回答摘要" : "",
        status,
        failReason,
        elapsedMs,
        startedAt);
    return id;
  }

  private HttpResponse<String> list(String token, UUID app, String query) throws Exception {
    String path = "/api/v1/assistants/" + app + "/workflow/records";
    return send(token, "GET", query == null || query.isEmpty() ? path : path + "?" + query);
  }

  /** 取 {@code data}（{@code PageResult}），顺手钉住 200 与成功包络。 */
  private JsonNode data(HttpResponse<String> response) {
    assertThat(response.statusCode()).as("列表：%s", response.body()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.path("code").asInt()).as("成功包络 code=0：%s", response.body()).isZero();
    return body.path("data");
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
                + "\",\"icon\":\"🧩\",\"description\":\"运行记录读数\","
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
}
