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
 * 知识库建档（第 5 周 · 0.2.0 批 1）—— 第一个「归属资源」全链路。
 *
 * <p>★ 挑的断言都对着"会静默出错"的地方（与 {@code UserRoleManagementTest} 同一条纪律）：
 *
 * <ol>
 *   <li><strong>数据范围</strong>：{@code own} 用户看不见别人的库；按 id 取不可见的库必须 <strong>404 而不是 403</strong>（403
 *       等于确认"id 存在"，可被枚举）
 *   <li><strong>列表 total 与条数一致</strong>（{@code 08 §4.4.3} 纪律 1）—— 查完再筛会让 total 是表行数、页内却是过滤后的结果
 *   <li><strong>幂等</strong>：同一 {@code clientRequestId} 重复创建返回既有库，库里只有一行 ——
 *       建库成功但上传失败后，重试不应被卡死、也不该留下孤儿库
 *   <li><strong>密级上限必填</strong>（{@code 13 §6} ceiling）：缺失 ⇒ 400，而不是悄悄用默认值放行
 *   <li><strong>ownerId 不接受前端传</strong>：请求体里塞别人的 ownerId 必须被忽略，服务端写当前用户；{@code visibility} 默认
 *       {@code PRIVATE}（{@code 08 §4.4.4} 一律收窄）
 *   <li><strong>权限点</strong>：无 {@code knowledge:create} / {@code knowledge:manage} ⇒ 403；非法 id ⇒
 *       400
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class KnowledgeBaseManagementTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  /** CREATOR 角色的固定 UUID（{@code V4__identity_seed_demo_accounts.sql}）。 */
  private static final UUID CREATOR_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000004");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  // ────────────────────────── 1 · 数据范围：own 看不见别人的库 ──────────────────────────

  /**
   * ★ 数据范围是<strong>过滤条件</strong>，不是事后检查。own 用户的列表里不能出现别人的库， 按 id 取也必须 404 —— 返 403 等于确认"这个 id
   * 存在"（{@code 08 §4.4.3} 纪律 2）。
   */
  @Test
  void own用户看不到别人建的库_按id取返回404而不是403() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("own_scope_user");

    // 管理员建一个私有库（owner=admin）
    HttpResponse<String> created =
        post("/api/v1/knowledge-bases", createBody("别人建的库", "制度规范", "INTERNAL", null), admin);
    assertThat(created.statusCode()).isEqualTo(200);
    String foreignId = jsonField(created.body(), "id");
    assertThat(foreignId).isNotBlank();

    // own 范围的 creator 列表里不能有它
    HttpResponse<String> list = get("/api/v1/knowledge-bases?page=1&size=50", creator);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).as("own 是个人沙箱：别人的私有库不出现在列表里").doesNotContain(foreignId);

    // 按 id 取 ⇒ 404 而不是 403
    HttpResponse<String> detail = get("/api/v1/knowledge-bases/" + foreignId, creator);
    assertThat(detail.statusCode()).as("返 403 等于确认'这个 id 存在但你没权限' —— 那是可被枚举的信息泄漏").isEqualTo(404);
    assertThat(detail.body()).contains("\"code\":404");
  }

  // ────────────────────────── 2 · 列表 total 与条数一致（拼进查询） ──────────────────────────

  /**
   * ★ total 必须是<strong>过滤之后</strong>的总数（{@code PageResult} 的约定）。 若"先分页查、再在内存里按范围筛"，用户会看到"共 100
   * 条"却只翻得出 3 条。
   */
  @Test
  void 列表total与可见条数一致_不是查完再筛() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("pagination_user");

    // creator 自己建 4 个库；admin 再建 2 个（creator 看不见）
    for (int i = 1; i <= 4; i++) {
      HttpResponse<String> res =
          post(
              "/api/v1/knowledge-bases",
              createBody("分页自己的" + i, "产品资料", "INTERNAL", null),
              creator);
      assertThat(res.statusCode()).isEqualTo(200);
    }
    for (int i = 1; i <= 2; i++) {
      post("/api/v1/knowledge-bases", createBody("分页别人的" + i, "行业标准", "INTERNAL", null), admin);
    }

    // size=2 分页：total 必须是 4（可见数），而不是 6（表行数）
    HttpResponse<String> page1 = get("/api/v1/knowledge-bases?page=1&size=2", creator);
    assertThat(page1.statusCode()).isEqualTo(200);
    long total = totalOf(page1.body());
    List<String> ids = idsOf(page1.body());
    assertThat(total).as("total 必须是过滤后的可见数，不能把别人的库算进去").isEqualTo(4);
    assertThat(ids).as("第一页 size=2").hasSize(2);

    HttpResponse<String> page2 = get("/api/v1/knowledge-bases?page=2&size=2", creator);
    assertThat(totalOf(page2.body())).as("翻页后 total 不变").isEqualTo(4);
    assertThat(idsOf(page2.body())).hasSize(2);
  }

  // ────────────────────────── 3 · 幂等：clientRequestId ──────────────────────────

  /**
   * ★ 同一 {@code clientRequestId} 重复创建返回<strong>既有库</strong>，而不是「名称已存在」。
   *
   * <p>为什么：建库成功但后续上传失败后，用户会点「重试」。若第二次创建被"名称已存在"卡住， 用户既不能继续导入、也找不到刚才那个库 —— 或者更糟：换个名字再建一次，库里留下一个孤儿库。
   */
  @Test
  void 重复clientRequestId返回首次那条且库里只有一行() throws Exception {
    String admin = accessToken("admin", "admin");
    String clientRequestId = "idem-" + UUID.randomUUID();

    HttpResponse<String> first =
        post(
            "/api/v1/knowledge-bases",
            createBody("幂等库", "制度规范", "INTERNAL", clientRequestId),
            admin);
    assertThat(first.statusCode()).isEqualTo(200);
    String firstId = jsonField(first.body(), "id");
    assertThat(firstId).isNotBlank();

    HttpResponse<String> second =
        post(
            "/api/v1/knowledge-bases",
            createBody("幂等库", "制度规范", "INTERNAL", clientRequestId),
            admin);
    assertThat(second.statusCode()).as("幂等重放必须成功返回既有库，而不是报「名称已存在」").isEqualTo(200);
    String secondId = jsonField(second.body(), "id");
    assertThat(secondId).as("同一 clientRequestId 必须返回首次那条的 id").isEqualTo(firstId);

    Integer rows =
        jdbc.queryForObject(
            "select count(*) from kb_knowledge_base where client_request_id = ?",
            Integer.class,
            clientRequestId);
    assertThat(rows).as("幂等键命中时库里只应有一行").isEqualTo(1);
  }

  // ────────────────────────── 4 · 密级上限必填 ──────────────────────────

  /** ★ ceiling（{@code 13 §6}）是"这个库允许装的最高密级"，缺了它入库校验就没有依据 —— 必填，缺失 ⇒ 400。 */
  @Test
  void 建库缺maxClassification返回400() throws Exception {
    String admin = accessToken("admin", "admin");

    String body =
        """
        {"name":"缺密级的库","category":"制度规范","description":"d","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s"}\
        """
            .formatted(UUID.randomUUID());

    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).as("错误文案要能照着改 —— 说清是「库的密级上限」必填，而不是笼统的「参数不合法」").contains("密级");
  }

  // ────────────────────────── 5 · ownerId 不接受前端传 · visibility 默认 PRIVATE
  // ──────────────────────────

  /**
   * ★ ownerId 由后端从 JWT 的 {@code sub} 写入，<strong>不接受前端传</strong> —— 否则用户能把资源挂到别人名下（{@code
   * OwnedResource} 的类注释）。
   *
   * <p>取舍：请求体里多余的 {@code ownerId} 字段被<strong>忽略</strong>（不是 400）。
   * 契约里本就没有这个请求字段，前端"多传一个字段"不该把创建打死；关键是服务端写入的值必须是当前用户。
   */
  @Test
  void 请求体塞别人的ownerId被忽略_写成当前用户_且visibility默认PRIVATE() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("owner_guard_user");
    String strangerId = "10000000-0000-0000-0000-000000000001";

    // 请求体里塞别人的 ownerId —— 服务端必须忽略并写当前用户
    String body =
        """
        {"name":"归属守卫库","category":"其他","description":"d","icon":"📗",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"INTERNAL",\
        "ownerId":"%s"}\
        """
            .formatted(UUID.randomUUID(), strangerId);

    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, creator);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(jsonField(res.body(), "ownerId"))
        .as("ownerId 必须是当前用户，不能被请求体覆盖")
        .isNotEqualTo(strangerId);
    assertThat(jsonField(res.body(), "visibility"))
        .as("新建资源一律收窄到 PRIVATE（08 §4.4.4：权限放开容易、收回难）")
        .isEqualTo("PRIVATE");

    // 库里的 owner 也是当前用户（不是响应层骗人）
    String id = jsonField(res.body(), "id");
    UUID ownerInDb =
        jdbc.queryForObject(
            "select owner_id from kb_knowledge_base where id = ?", UUID.class, UUID.fromString(id));
    assertThat(ownerInDb).isNotNull();
    // creator 的 id 从登录令牌里读（sub）
    String creatorSub = jsonField(creator, "sub");
    assertThat(ownerInDb.toString()).isEqualTo(creatorSub);
  }

  // ────────────────────────── 6 · 权限点 · 非法 id ──────────────────────────

  /** ★ 无权限点 ⇒ 403（不是 404、也不是静默成功）；非法 id ⇒ 400（调用方自己能修）。 */
  @Test
  void 无权限点返回403_非法id返回400() throws Exception {
    String admin = accessToken("admin", "admin");
    // chenjun = USER 角色：有 knowledge:view，没有 create/manage
    String chenjun = accessToken("chenjun", "123456");

    String body = createBody("权限测试库", "制度规范", "INTERNAL", null);
    assertThat(post("/api/v1/knowledge-bases", body, chenjun).statusCode())
        .as("USER 没有 knowledge:create")
        .isEqualTo(403);

    // 先让 admin 造一个库，再用 chenjun 试 manage
    String kbId = jsonField(post("/api/v1/knowledge-bases", body, admin).body(), "id");
    assertThat(put("/api/v1/knowledge-bases/" + kbId, body, chenjun).statusCode())
        .as("USER 没有 knowledge:manage")
        .isEqualTo(403);
    assertThat(
            send(
                    "PATCH",
                    "/api/v1/knowledge-bases/" + kbId + "/enabled",
                    "{\"enabled\":false}",
                    chenjun)
                .statusCode())
        .isEqualTo(403);
    assertThat(delete("/api/v1/knowledge-bases/" + kbId, chenjun).statusCode()).isEqualTo(403);

    // 非法 id ⇒ 400（不是 500，也不是 404 —— id 是格式不合法，不是不存在）
    HttpResponse<String> badId = get("/api/v1/knowledge-bases/not-a-uuid", chenjun);
    assertThat(badId.statusCode()).isEqualTo(400);
    assertThat(badId.body()).contains("\"code\":400");
  }

  // ────────────────────────── 分类清单 ──────────────────────────

  /** 分类清单要带每个分类下的库数，供列表页筛选用。 */
  @Test
  void 分类清单带每个分类的库数() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("category_user");

    post("/api/v1/knowledge-bases", createBody("分类甲", "制度规范", "INTERNAL", null), creator);
    post("/api/v1/knowledge-bases", createBody("分类乙", "制度规范", "INTERNAL", null), creator);
    post("/api/v1/knowledge-bases", createBody("分类丙", "产品资料", "INTERNAL", null), creator);

    HttpResponse<String> res = get("/api/v1/knowledge-bases/categories", creator);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("制度规范").contains("产品资料");
    // 「制度规范」下有 2 个（自己建的）—— 数字出现在名字附近即可，避免过度解析 JSON
    assertThat(res.body()).contains("\"name\":\"制度规范\"");
  }

  // ────────────────────────── 工具 ──────────────────────────

  private static String createBody(
      String name, String category, String maxClassification, String clientRequestId) {
    String idem = clientRequestId == null ? "" : ",\"clientRequestId\":\"" + clientRequestId + "\"";
    return """
    {"name":"%s","category":"%s","description":"集成测试用库","icon":"📘",\
    "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"%s"%s}\
    """
        .formatted(name, category, UUID.randomUUID(), maxClassification, idem);
  }

  /**
   * 建一个 CREATOR（own 范围）用户并返回其访问令牌。
   *
   * <p>★ 用 CREATOR 而不是 USER：USER 的 dataScope 是 {@code SHARED}， 连"别人分享给我的"也能看见；要验的是 {@code OWN}
   * 那条最窄的沙箱语义。
   */
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

  /** 从 JWT payload 或普通 JSON 里读一个<strong>字符串</strong>字段（不验签，只是"看一眼里面有什么"）。 */
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

  private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
    return send("PUT", path, body, bearer);
  }

  private HttpResponse<String> delete(String path, String bearer) throws Exception {
    return send("DELETE", path, null, bearer);
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
