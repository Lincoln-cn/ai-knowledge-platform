package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
 * 审计留痕（{@code #257}）—— 变更类动作必须落一条 {@code pf_audit_log}，且只追加。
 *
 * <h3>★ 这个类钉的三件事</h3>
 *
 * <ol>
 *   <li><b>动作 ⇒ 有记录</b>：改密级、删文档各落一条，{@code action} 是闭集里的稳定编码。
 *   <li><b>actor_name 是快照</b>：写的是 {@code display_name}（"管理员"），不是 {@code username}（"admin"）， 也不是外键
 *       —— 用户改名后旧记录仍要答得出"当时是谁"。
 *   <li><b>只追加</b>：库层面的 trigger 拒绝 update/delete（{@code 13 §7.2} 第 3 条）。
 * </ol>
 *
 * <p>★ 判据③ 的"能红"形状：把 {@code DocumentService} 里那行 {@code audit.record(...)} 删掉，本类第一条就会红 ——
 * 它断的是"这条资源在审计表里有一行"，不是"表存在"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AuditTrailTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void reclassifyWritesAnAuditRowWithTheActorNameSnapshot() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "审计-改密级库");
    String docId = createDocument(admin, kbId, "审计改密级.docx");

    HttpResponse<String> res = patchClassification(admin, docId, "SECRET");
    assertThat(res.statusCode()).as("改密级失败：%s", res.body()).isEqualTo(200);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_id, actor_name, action, resource_type, resource_id,"
                + " detail->>'from' as detail_from, detail->>'to' as detail_to,"
                + " detail->>'segments' as detail_segments"
                + " from pf_audit_log where resource_id = ? and action = 'document.reclassify'",
            docId);
    assertThat(row.get("action")).isEqualTo("document.reclassify");
    assertThat(row.get("resource_type")).isEqualTo("DOCUMENT");
    assertThat(row.get("actor_id"))
        .as("操作人：admin 的真实 id")
        .isEqualTo(
            jdbc.queryForObject("select id from iam_user where username = 'admin'", UUID.class));
    assertThat(row.get("actor_name"))
        .as("★ 快照的是 display_name（管理员），不是 username（admin）—— 审计要回答『当时是谁』")
        .isEqualTo("管理员");
    assertThat(row.get("detail_from")).as("detail 要能说清从哪一档改到哪一档（键名属契约）").isEqualTo("INTERNAL");
    assertThat(row.get("detail_to")).isEqualTo("SECRET");
    assertThat(String.valueOf(row.get("detail_segments")))
        .as("当前这份文档没有分段（新建后未向量化）⇒ 0 段跟着改了密级")
        .isEqualTo("0");
  }

  @Test
  void deleteWritesAnAuditRow() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "审计-删除库");
    String docId = createDocument(admin, kbId, "审计删除.docx");

    HttpResponse<String> res = delete(admin, docId);
    assertThat(res.statusCode()).as("删除失败：%s", res.body()).isEqualTo(200);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_name, detail->>'name' as detail_name, detail->>'kbId' as detail_kb"
                + " from pf_audit_log where resource_id = ? and action = 'document.delete'",
            docId);
    assertThat(row.get("actor_name")).isEqualTo("管理员");
    assertThat(row.get("detail_name"))
        .as("删掉的是哪一篇 —— 事后只有这一行能回答（文档本体已被软删，名字还在审计里）")
        .isEqualTo("审计删除.docx");
    assertThat(row.get("detail_kb")).as("原本属于哪个知识库").isEqualTo(kbId);
  }

  /**
   * ★ 只追加不是纪律而是保证：库里的 trigger 直接拒绝改写与删除。
   *
   * <p>为什么值得一条测试：{@code 13 §7.2} 写"不提供删除接口"，而"代码里没写 delete"是一种随时会被 后来者"顺手补上"的约定 —— trigger
   * 才是边界。这条测试红，通常意味着 trigger 被人从迁移里删掉了。
   */
  @Test
  void auditRowsCannotBeUpdatedOrDeleted() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "审计-只追加库");
    String docId = createDocument(admin, kbId, "审计只追加.docx");
    assertThat(patchClassification(admin, docId, "SECRET").statusCode()).isEqualTo(200);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update pf_audit_log set actor_name = '被篡改' where resource_id = ?", docId))
        .as("★ 审计记录不许改写（13 §7.2 第 3 条）")
        .hasMessageContaining("只允许插入");

    assertThatThrownBy(() -> jdbc.update("delete from pf_audit_log where resource_id = ?", docId))
        .as("★ 审计记录不许删除")
        .hasMessageContaining("只允许插入");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from pf_audit_log where resource_id = ?", Integer.class, docId))
        .as("两次被拒的操作都不许留下副作用")
        .isEqualTo(1);
  }

  /**
   * 授权变更也要留痕（`#257` 判据② 的第三类动作）。
   *
   * <p>★ 为什么这一条比文档那两条更值得写测试：改角色权限**没有可见的产物** —— 文档被删了还能查文档表，权限被改过之后
   * 库里只剩最终状态。审计是唯一能回答"是谁在什么时候把某个权限点给了这个角色"的地方。
   */
  @Test
  void grantChangeWritesAnAuditRow() throws Exception {
    String admin = accessToken("admin", "admin");
    String operatorRole = "00000000-0000-0000-0000-000000000002";

    HttpResponse<String> res =
        put(
            "/api/v1/roles/" + operatorRole + "/permissions",
            "{\"permissions\":[\"knowledge:view\",\"assistant:view\"]}",
            admin);
    assertThat(res.statusCode()).as("改权限点失败：%s", res.body()).isEqualTo(200);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_name, action, resource_type, detail->>'roleCode' as detail_role"
                + " from pf_audit_log where resource_id = ? and action = 'grant.change'",
            operatorRole);
    assertThat(row.get("action")).isEqualTo("grant.change");
    assertThat(row.get("resource_type")).isEqualTo("ROLE");
    assertThat(row.get("actor_name")).isEqualTo("管理员");
    assertThat(row.get("detail_role")).as("★ 记角色编码而不是名字：角色名可以改，编码是它的身份").isEqualTo("KB_OPERATOR");
  }

  /** 导出也要留痕（`#257` 判据② 的第四类动作）—— 数据外流是审计的第一等公民。 */
  @Test
  void exportWritesAnAuditRow() throws Exception {
    String admin = accessToken("admin", "admin");
    String chatModel = seedChatModel();
    HttpResponse<String> created =
        post(
            "/api/v1/assistants",
            "{\"name\":\"审计导出应用\",\"type\":\"SIMPLE\",\"modelId\":\"" + chatModel + "\"}",
            admin);
    assertThat(created.statusCode()).as("建应用失败：%s", created.body()).isEqualTo(200);
    String appId = jsonField(created.body(), "id");

    HttpResponse<String> res = get("/api/v1/assistants/" + appId + "/export", admin);
    assertThat(res.statusCode()).as("导出失败：%s", res.body()).isEqualTo(200);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_name, action, resource_type, detail->>'name' as detail_name"
                + " from pf_audit_log where resource_id = ? and action = 'data.export'",
            appId);
    assertThat(row.get("action")).isEqualTo("data.export");
    assertThat(row.get("resource_type")).isEqualTo("ASSISTANT");
    assertThat(row.get("actor_name")).isEqualTo("管理员");
    assertThat(row.get("detail_name")).as("导出的是哪个应用 —— 事后只有审计能回答").isEqualTo("审计导出应用");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 建一条可用的对话模型（简单应用必须绑定一个，否则 400「简单应用必须绑定一个对话模型」）。 */
  private String seedChatModel() {
    UUID modelId = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at)"
            + " values (?, '审计夹具对话模型', 'CHAT', 'LOCAL_INFERENCE', 'qwen3:8b',"
            + " 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        modelId);
    return modelId.toString();
  }

  private HttpResponse<String> put(String path, String body, String token) throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private HttpResponse<String> patchClassification(String token, String docId, String level)
      throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/documents/" + docId + "/classification"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString("{\"classification\":\"" + level + "\"}"))
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private HttpResponse<String> delete(String token, String docId) throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/documents/" + docId))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private String createKb(String adminToken, String name) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"审计测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(name, UUID.randomUUID());
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, adminToken);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private String createDocument(String adminToken, String kbId, String name) throws Exception {
    String orgId =
        jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, "LEGAL");
    String body =
        """
        {"names":["%s"],"docType":"制度","orgId":"%s","classification":"INTERNAL"}\
        """
            .formatted(name, orgId);
    HttpResponse<String> res =
        post("/api/v1/knowledge-bases/" + kbId + "/documents", body, adminToken);
    assertThat(res.statusCode()).as("建档失败：%s", res.body()).isEqualTo(200);
    return jdbc.queryForObject(
        "select id::text from kb_document where kb_id = ?::uuid and name = ?"
            + " order by created_at desc limit 1",
        String.class,
        kbId,
        name);
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    String token = jsonField(res.body(), "accessToken");
    if (token == null) {
      throw new IllegalStateException("登录失败：" + res.body());
    }
    return token;
  }

  private HttpResponse<String> post(String path, String body, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return send(builder.build());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String jsonField(String body, String key) {
    if (body == null) {
      return null;
    }
    var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }
}
