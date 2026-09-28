package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * 文档建档（第 5 周 · 0.2.0 批 2）—— 元数据必填 + ceiling 校验。
 *
 * <p>★ 断言都对着「会静默出错」的地方（与 {@code KnowledgeBaseManagementTest} 同一条纪律）：
 *
 * <ol>
 *   <li><strong>建档必填三项</strong>（文种 / 责任部门 / 密级，{@code 12 §5.1}）：缺任一 ⇒ 400 且说清缺的是哪一项 ——
 *       这三项分别决定召回前缀与安全过滤，<strong>不能有默认值</strong>
 *   <li><strong>ceiling 校验</strong>（{@code 13 §6}）：文档密级 <strong>高于</strong> 库的 {@code
 *       maxClassification} ⇒ 拒绝 + 可读原因（含两个密级值）；等于/低于 ⇒ 放行
 *   <li><strong>状态初值</strong> = {@code PENDING_PARSE}（未解析那一态）；失败原因列 <strong>可写可读</strong>（{@code
 *       DOC-I2}：失败必须留下可读原因，不许静默失败）
 *   <li><strong>可见性继承库</strong>：库不可见 ⇒ 其文档读/写都 <strong>404</strong>（不是 403）—— 与批 1 同一判定（{@code 08
 *       §4.4.3} 纪律 2）
 *   <li><strong>列表按库过滤 + total 与条数一致</strong>：{@code total} 必须是过滤之后的数
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentRegistrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  /** CREATOR 角色的固定 UUID（{@code V4__identity_seed_demo_accounts.sql}）。 */
  private static final UUID CREATOR_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000004");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  // ────────────────────────── 1 · 建档必填三项 ──────────────────────────

  /**
   * ★ 文种 / 责任部门 / 密级<strong>不能有默认值</strong>（{@code 12 §5.1} 纪律 1–2）： 默认值会让过滤形同虚设 ——
   * 密级默认「未知」要么全放行（泄密）要么全拦（不可用）。 缺失 ⇒ 400，且错误文案<strong>指出缺的是哪一项</strong>（调用方能照着改）。
   */
  @Test
  void 建档缺文种部门或密级任一返回400并指出缺哪项() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "必填项库", "INTERNAL");

    // 缺文种
    HttpResponse<String> noDocType =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("缺文种.pdf"), null, "采购部", "INTERNAL"),
            admin);
    assertThat(noDocType.statusCode()).isEqualTo(400);
    assertThat(noDocType.body()).as("错误文案要说清是「文种」缺失，而不是笼统的参数不合法").contains("文种");

    // 缺责任部门
    HttpResponse<String> noDepartment =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("缺部门.pdf"), "制度", null, "INTERNAL"),
            admin);
    assertThat(noDepartment.statusCode()).isEqualTo(400);
    assertThat(noDepartment.body()).as("错误文案要说清是「责任部门」缺失").contains("部门");

    // 缺密级
    HttpResponse<String> noClassification =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("缺密级.pdf"), "制度", "采购部", null),
            admin);
    assertThat(noClassification.statusCode()).isEqualTo(400);
    assertThat(noClassification.body()).as("错误文案要说清是「密级」缺失").contains("密级");
  }

  // ────────────────────────── 2 · ceiling 校验 ──────────────────────────

  /**
   * ★ 文档密级 <strong>高于</strong> 库 ceiling ⇒ 拒绝（{@code 13 §6}）：
   * 等于/低于放行。原因必须<strong>可读且含两个密级值</strong> —— 让用户知道「差在哪一档、下一步能做什么」，而不是「校验失败」。
   */
  @Test
  void 文档密级高于库ceiling拒绝_原因含两个密级_等于与低于放行() throws Exception {
    String admin = accessToken("admin", "admin");
    // 库 ceiling = 秘密（SECRET）
    String kbId = createKb(admin, "ceiling库", "SECRET");

    // 高于：机密（CONFIDENTIAL）> 秘密（SECRET）⇒ 400
    HttpResponse<String> tooHigh =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("超密级.docx"), "合同", "法务部", "CONFIDENTIAL"),
            admin);
    assertThat(tooHigh.statusCode()).isEqualTo(400);
    assertThat(tooHigh.body())
        .as("原因要可读，并同时出现文档密级与库上限两个值")
        .contains("机密")
        .contains("秘密")
        .contains("高于");

    // 等于：秘密 = 秘密 ⇒ 放行
    HttpResponse<String> equal =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("同密级.docx"), "合同", "法务部", "SECRET"),
            admin);
    assertThat(equal.statusCode()).as("等于 ceiling 必须放行").isEqualTo(200);

    // 低于：内部（INTERNAL）< 秘密（SECRET）⇒ 放行
    HttpResponse<String> lower =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("低密级.docx"), "制度", "人事部", "INTERNAL"),
            admin);
    assertThat(lower.statusCode()).as("低于 ceiling 必须放行").isEqualTo(200);
  }

  // ────────────────────────── 3 · 状态初值 · 失败原因可写可读 ──────────────────────────

  /**
   * ★ 初值必须是「未解析」那一态（{@code PENDING_PARSE}），而不是「成功」—— 否则界面会把还没加工的文档显示成可检索。
   *
   * <p>★ 失败原因列<strong>可写可读</strong>（{@code DOC-I2}）： 原项目的失败原因字段一直存在却从不展示，属于典型静默失效。
   * 这里先钉住「列能写进去、接口能读出来」，解析段写入原因时才不会白写。
   */
  @Test
  void 文档状态初值为未解析_失败原因列可写可读() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "状态库", "INTERNAL");

    HttpResponse<String> created =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("待解析.md"), "制度", "采购部", "INTERNAL"),
            admin);
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.body())
        .as("状态初值必须是未解析那一态 PENDING_PARSE")
        .contains("\"status\":\"PENDING_PARSE\"");
    String docId = jsonField(created.body(), "id");
    assertThat(docId).isNotBlank();

    // 失败原因列可写（模拟解析段写入 DOC-I2），再从列表接口读出来
    jdbc.update(
        "update kb_document set status = 'SEGMENT_FAILED', fail_reason = ?, fail_hint = ? where id"
            + " = ?",
        "该 PDF 为扫描件，未检测到文本层，无法提取内容。",
        "建议：转为可检索 PDF 或改用 Markdown 格式后重新上传。",
        UUID.fromString(docId));

    HttpResponse<String> list = get("/api/v1/knowledge-bases/" + kbId + "/documents", admin);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body())
        .as("failReason / failHint 必须能读出来 —— 静默失败是原项目最贵的坑之一")
        .contains("未检测到文本层")
        .contains("转为可检索 PDF");
  }

  // ────────────────────────── 4 · 可见性继承库 ──────────────────────────

  /**
   * ★ 文档的可见性<strong>继承库</strong>：库不可见 ⇒ 其文档读/写都 404。 与批 1 的 {@code requireVisible} 同一判定，不另写一套 ——
   * 两套判定迟早分叉，而分叉点不会报错。 ★ 403 会确认「这个 kbId 存在」，是可被枚举的信息泄漏。
   */
  @Test
  void 库不可见时其文档读与写都返回404而不是403() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("doc_inherit_user");

    // 管理员建私有库（owner=admin）；creator 看不见
    String foreignKb = createKb(admin, "别人的库", "INTERNAL");

    // 读：列表 ⇒ 404（不是 403、更不是 200）
    HttpResponse<String> list = get("/api/v1/knowledge-bases/" + foreignKb + "/documents", creator);
    assertThat(list.statusCode()).as("返 403 等于确认'这个 id 存在但你没权限' —— 可被枚举").isEqualTo(404);
    assertThat(list.body()).contains("\"code\":404");

    // 写：建档 ⇒ 404
    HttpResponse<String> write =
        post(
            "/api/v1/knowledge-bases/" + foreignKb + "/documents",
            registerBody(List.of("越权.docx"), "制度", "采购部", "INTERNAL"),
            creator);
    assertThat(write.statusCode()).as("写也必须 404，不是 403").isEqualTo(404);
    assertThat(write.body()).contains("\"code\":404");

    // 顺带确认：管理员自己看得见（判定不是「一律 404」）
    assertThat(get("/api/v1/knowledge-bases/" + foreignKb + "/documents", admin).statusCode())
        .isEqualTo(200);
  }

  // ────────────────────────── 5 · 列表按库过滤 + 分页 total ──────────────────────────

  /**
   * ★ 列表<strong>按库过滤</strong>：A 库的文档不能出现在 B 库的列表里。
   *
   * <p>★ {@code total} 必须是过滤之后的数（{@code 08 §4.4.3} 纪律 1）—— 若先分页查、再按库筛， 用户会看到「共 100 条」却只翻得出 3 条。
   */
  @Test
  void 文档列表按库过滤_分页total与可见条数一致() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbA = createKb(admin, "库甲", "INTERNAL");
    String kbB = createKb(admin, "库乙", "INTERNAL");

    for (int i = 1; i <= 3; i++) {
      HttpResponse<String> res =
          post(
              "/api/v1/knowledge-bases/" + kbA + "/documents",
              registerBody(List.of("甲库文档" + i + ".md"), "制度", "采购部", "INTERNAL"),
              admin);
      assertThat(res.statusCode()).isEqualTo(200);
    }
    for (int i = 1; i <= 2; i++) {
      post(
          "/api/v1/knowledge-bases/" + kbB + "/documents",
          registerBody(List.of("乙库文档" + i + ".md"), "合同", "法务部", "INTERNAL"),
          admin);
    }

    HttpResponse<String> page1 =
        get("/api/v1/knowledge-bases/" + kbA + "/documents?page=1&size=2", admin);
    assertThat(page1.statusCode()).isEqualTo(200);
    long total = totalOf(page1.body());
    List<String> ids = idsOf(page1.body());
    assertThat(total).as("total 必须是本库过滤后的数，不能把别的库算进去").isEqualTo(3);
    assertThat(ids).as("第一页 size=2").hasSize(2);
    assertThat(page1.body()).as("乙库的文档不能漏进甲库列表").doesNotContain("乙库文档");

    HttpResponse<String> page2 =
        get("/api/v1/knowledge-bases/" + kbA + "/documents?page=2&size=2", admin);
    assertThat(totalOf(page2.body())).as("翻页后 total 不变").isEqualTo(3);
    assertThat(idsOf(page2.body())).hasSize(1);
  }

  // ────────────────────────── 6 · 契约形状差禁止静默忽略 ──────────────────────────

  /**
   * ★ 契约两套形状共存时，后端<strong>不许静默忽略</strong>： 只传 {@code uploadIds}（暂存导入形态）而不传 {@code names}（轻量建档形态）⇒
   * 400 且原因可读 —— 「收下但不用」会让调用方以为暂存导入已生效。
   *
   * <p>只传 {@code names} ⇒ 200（当前唯一落地形态）。 同时给时以 {@code names} 为准（不因 {@code uploadIds} 被拒）。
   */
  @Test
  void 只传uploadIds不传names返回400且原因可读_只传names建档放行() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "形状差库", "INTERNAL");

    // 只传 uploadIds（暂存导入形态）不传 names ⇒ 400 + 可读原因（不能「收下但不用」）
    HttpResponse<String> stagedOnly =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            """
            {"uploadIds":["up_1"],"docType":"制度","department":"采购部","classification":"INTERNAL"}\
            """,
            admin);
    assertThat(stagedOnly.statusCode()).as("只传 uploadIds 不传 names 不能静默忽略").isEqualTo(400);
    assertThat(stagedOnly.body())
        .as("原因要指路：暂存上传尚未支持，请用 names 提供文档名")
        .contains("暂存")
        .contains("names");

    // 只传 segmentationPlan 同理 —— 同属暂存导入形态，后端尚未消费
    HttpResponse<String> planOnly =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            """
            {"segmentationPlan":[{"ordinal":1,"content":"x"}],"docType":"制度","department":"采购部","classification":"INTERNAL"}\
            """,
            admin);
    assertThat(planOnly.statusCode()).isEqualTo(400);
    assertThat(planOnly.body()).contains("暂存").contains("names");

    // 只传 names ⇒ 200（轻量建档，当前唯一落地形态）
    HttpResponse<String> namesOnly =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            registerBody(List.of("轻量建档.md"), "制度", "采购部", "INTERNAL"),
            admin);
    assertThat(namesOnly.statusCode()).as("names 形态必须放行").isEqualTo(200);

    // 同时给时以 names 为准：不 400，且建出来的是 names 里的文档
    HttpResponse<String> both =
        post(
            "/api/v1/knowledge-bases/" + kbId + "/documents",
            """
            {"names":["以names为准.md"],"uploadIds":["up_x"],"docType":"制度","department":"采购部","classification":"INTERNAL"}\
            """,
            admin);
    assertThat(both.statusCode()).as("同时给时以 names 为准，不因 uploadIds 被拒").isEqualTo(200);
    assertThat(both.body()).contains("以names为准.md");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /** 建档请求体：批次级三项标签 + 文件名清单（最小建档形态，见 {@code DocumentService} 类注释）。 */
  private static String registerBody(
      List<String> names, String docType, String department, String classification) {
    StringBuilder namesJson = new StringBuilder("[");
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) {
        namesJson.append(',');
      }
      namesJson.append('"').append(names.get(i)).append('"');
    }
    namesJson.append(']');
    String dt = docType == null ? "null" : "\"" + docType + "\"";
    String dept = department == null ? "null" : "\"" + department + "\"";
    String cl = classification == null ? "null" : "\"" + classification + "\"";
    return """
    {"names":%s,"docType":%s,"department":%s,"classification":%s}\
    """
        .formatted(namesJson, dt, dept, cl);
  }

  private String createKb(String adminToken, String name, String maxClassification)
      throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"建档测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"%s"}\
        """
            .formatted(name, UUID.randomUUID(), maxClassification);
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, adminToken);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    String id = jsonField(res.body(), "id");
    assertThat(id).isNotBlank();
    return id;
  }

  private String createCreatorUser(String username) throws Exception {
    String admin = accessToken("admin", "admin");
    String body =
        """
        {"username":"%s","displayName":"%s","roleId":"%s","password":"Creator@123456"}\
        """
            .formatted(username, username, CREATOR_ROLE_ID);
    HttpResponse<String> res = post("/api/v1/users", body, admin);
    assertThat(res.statusCode()).as("建 CREATOR 用户失败：%s", res.body()).isEqualTo(200);
    return accessToken(username, "Creator@123456");
  }

  private static long totalOf(String json) {
    Matcher matcher = Pattern.compile("\"total\":(\\d+)").matcher(json);
    if (!matcher.find()) {
      throw new IllegalStateException("响应里没有 total：" + json);
    }
    return Long.parseLong(matcher.group(1));
  }

  private static List<String> idsOf(String json) {
    List<String> ids = new ArrayList<>();
    Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return ids;
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

  private static String jsonField(String jwtOrJson, String key) {
    if (jwtOrJson == null) {
      return null;
    }
    String source = jwtOrJson;
    if (jwtOrJson.startsWith("eyJ") && jwtOrJson.contains(".")) {
      String payload = jwtOrJson.split("\\.")[1];
      payload += "=".repeat((4 - payload.length() % 4) % 4);
      source = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    }
    Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(source);
    return matcher.find() ? matcher.group(1) : null;
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send("GET", path, null, bearer);
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30));
      if ("GET".equals(method)) {
        builder.GET();
      } else if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      if (bearer != null) {
        builder.header("Authorization", "Bearer " + bearer);
      }
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("请求失败：" + method + " " + path, e);
    }
  }
}
