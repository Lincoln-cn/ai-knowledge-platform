package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.application.UserService;
import cn.lincoln.aiplatform.identity.infrastructure.PermissionCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * 用户与角色管理（第 3 周第二段）。
 *
 * <p>★ 挑的断言都对着"会静默出错"的地方：
 *
 * <ol>
 *   <li><strong>权限</strong>：非管理员必须 403 —— 漏了 {@code @PreAuthorize} 的端点 不会报错，只会对所有人开放
 *   <li><strong>不存在 ⇒ 404</strong>（而不是 403）：返 403 等于确认"这个 id 存在"
 *   <li>★ <strong>改角色权限点后重新登录要生效</strong> —— 这条验的是"权限点的真源在哪"。 若鉴权直接读代码常量（{@code
 *       Permissions.of(code)}），那么管理员在界面上勾选 会<strong>完全不生效</strong>，而界面上看起来一切正常
 *   <li>★ <strong>重启不冲掉界面改动</strong> —— 启动同步若按代码映射差分，界面上改的授权 会被静默改回去。这条用"改完再调 sync()"把它钉住
 *   <li><strong>软删 ⇒ 看不见</strong>（列表没有、详情 404、同名可重建、刷新令牌失效）
 *   <li><strong>不可恢复状态</strong>：删内置 admin、把自己降级、把 ADMIN 权限点削减 —— 全部 400
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class UserRoleManagementTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  private static final Pattern ROLE_ENTRY =
      Pattern.compile("\\{\"id\":\"([^\"]+)\",\"name\":\"[^\"]*\",\"code\":\"([^\"]+)\"");

  @LocalServerPort int port;

  /** 直接调同步，模拟"重启"——这条是「重启不冲掉界面改动」断言的关键道具。 */
  @Autowired PermissionCatalog permissionCatalog;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void 非管理员访问用户管理返回403() throws Exception {
    String chenjun = accessToken("chenjun", "123456");

    assertThat(get("/api/v1/users", chenjun).statusCode())
        .as("USER 只有 4 个权限点，没有 system:manage")
        .isEqualTo(403);
    assertThat(get("/api/v1/roles", chenjun).statusCode()).isEqualTo(403);
    assertThat(get("/api/v1/permissions", chenjun).statusCode()).isEqualTo(403);
  }

  @Test
  void 管理员能看用户列表且分页形状正确() throws Exception {
    HttpResponse<String> res = get("/api/v1/users", accessToken("admin", "admin"));

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"total\":").contains("\"page\":1").contains("\"size\":");
    // 契约里的 User 含 roleName（后端 join 出来，避免前端再查一次角色表）
    assertThat(res.body()).contains("\"roleName\":\"管理员\"");
    // ★ 视图里不能出现密码哈希
    assertThat(res.body()).doesNotContain("passwordHash").doesNotContain("$2a$");
  }

  /**
   * ★★ 真环境首屏必走的那条路径：**不带任何筛选**的用户列表。
   *
   * <p>旧实现（{@code @Query} HQL + {@code concat('%', :keyword, '%')}）在 keyword 为 null 时， Hibernate
   * 推不出绑定类型 ⇒ 回退 {@code JAVA_OBJECT}（PG 按 bytea 发）⇒ {@code lower(bytea)} 不存在 ⇒ 500。
   * 且该类型被缓存进查询计划，后续沿用。
   *
   * <p>⚠️ 这条断言在旧实现下是<strong>顺序偶然绿</strong>的：只要此前跑过一条带 keyword 的用例， 类型就被锁成 VARCHAR，null
   * 用例也跟着"净光"。所以它必须独立成条、 并且不能依赖其它用例的执行顺序 —— 现在改用 Specification 拼谓词后，空条件根本不进查询。
   */
  @Test
  void 不带任何筛选的用户列表必须可用() throws Exception {
    HttpResponse<String> res = get("/api/v1/users", accessToken("admin", "admin"));

    assertThat(res.statusCode()).as("真前端用户管理页首屏就是这个请求 —— 旧实现在冷启动后第一次必 500").isEqualTo(200);
    assertThat(res.body()).contains("\"username\":\"admin\"");
    assertThat(res.body()).as("total 至少含 seed 的 4 个演示账号").contains("\"total\":");
  }

  /** 空串 keyword（真前端会传 {@code ?keyword=&page=1&size=10}）等同于不筛选。 */
  @Test
  void 空串关键字等同于不筛选() throws Exception {
    String admin = accessToken("admin", "admin");
    HttpResponse<String> blank = get("/api/v1/users?keyword=&page=1&size=10", admin);
    HttpResponse<String> none = get("/api/v1/users?page=1&size=10", admin);

    assertThat(blank.statusCode()).isEqualTo(200);
    assertThat(none.statusCode()).isEqualTo(200);
    // ★ 空串要被当成"没筛"，而不是去 LIKE '%%' 漏掉/多算（total 是数字，别用字符串型的 jsonField）
    assertThat(totalOf(blank.body())).as("空串 keyword 与不筛选必须等价").isEqualTo(totalOf(none.body()));
    assertThat(blank.body()).contains("\"username\":\"admin\"");
  }

  @Test
  void 不存在的用户返回404而不是403() throws Exception {
    HttpResponse<String> res =
        get("/api/v1/users/00000000-0000-0000-0000-00000000dead", accessToken("admin", "admin"));

    assertThat(res.statusCode()).as("返 403 等于确认'这个 id 存在但你没权限' —— 那是可被枚举的信息泄漏").isEqualTo(404);
    assertThat(res.body()).contains("\"code\":404");
  }

  @Test
  void 非法UUID变成400而不是500() throws Exception {
    HttpResponse<String> res = get("/api/v1/users/not-a-uuid", accessToken("admin", "admin"));

    assertThat(res.statusCode())
        .as("调用方传错 id 是 400（他自己能修）；500 会让人来找后端。404 也不对 —— id 是格式不合法，不是不存在")
        .isEqualTo(400);
    assertThat(res.body()).contains("\"code\":400");
  }

  /**
   * ★ 与「非法 UUID ⇒ 500」同族：请求体不是合法 JSON 也是<strong>调用方传错了</strong>（400）， 落进兜底
   * {@code @ExceptionHandler(Exception.class)} 会变成 500「服务内部错误」（得来找后端翻堆栈）。 实测形态：{@code
   * {username:admin}} —— 字段名/字符串没加双引号。
   */
  @Test
  void 非法JSON请求体变成400而不是500() throws Exception {
    HttpResponse<String> res = post("/api/v1/auth/login", "{username:admin}", null);

    assertThat(res.statusCode()).as("请求体不是合法 JSON 是调用方能修的 4xx；500 会把人指到后端翻堆栈").isEqualTo(400);
    assertThat(res.body()).as("包络 code 与 HTTP 状态同值，前端按 code 分支").contains("\"code\":400");
  }

  @Test
  void 权限点清单来自代码目录() throws Exception {
    HttpResponse<String> res = get("/api/v1/permissions", accessToken("admin", "admin"));

    assertThat(res.statusCode()).isEqualTo(200);
    // 16 个权限点，且带分组用的 module
    assertThat(res.body()).contains("\"module\":\"知识\"").contains("system:manage");
    // ★ 数的是权限点项里的 code（带引号的字符串），不能把包络里的 "code":0 也数进去
    assertThat(countOccurrences(res.body(), "\"code\":\"")).isEqualTo(16);
  }

  /**
   * ★★ 这条是本次最重要的断言。
   *
   * <p>权限点有<strong>两个不同的东西</strong>，很容易被合成一个：
   *
   * <ul>
   *   <li><strong>清单</strong>（有哪些权限点）⇒ 代码常量（新增功能时它自己多一项）
   *   <li><strong>角色挂哪些</strong> ⇒ <em>数据</em>（管理员在界面上勾）
   * </ul>
   *
   * <p>如果鉴权直接读代码里的 {@code Permissions.of(roleCode)}，那么后者 <strong>永远不生效</strong> ——
   * 而且看不出问题：界面能存、能回显，只是授权判断不理会它。 所以这条测试逼着实现把"角色挂哪些"从库里读出来。
   */
  @Test
  void 改角色权限点后重新登录立即生效() throws Exception {
    String admin = accessToken("admin", "admin");
    String userRoleId = roleIdOf("USER", admin);

    List<String> before = permissionsOfUser("chenjun");
    assertThat(before).as("USER 初始应含 knowledge:view").contains("knowledge:view");

    try {
      HttpResponse<String> updated =
          put(
              "/api/v1/roles/" + userRoleId + "/permissions",
              "{\"permissions\":[\"assistant:view\",\"conversation:view\"]}",
              admin);
      assertThat(updated.statusCode()).isEqualTo(200);

      assertThat(permissionsOfUser("chenjun"))
          .as("★ 改库必须生效：权限点清单是代码常量，但'角色挂哪些'是数据")
          .containsExactlyInAnyOrder("assistant:view", "conversation:view");
    } finally {
      // 恢复成代码里的初始映射，避免把状态留给其它测试
      put(
          "/api/v1/roles/" + userRoleId + "/permissions",
          "{\"permissions\":[\"knowledge:view\",\"assistant:view\",\"conversation:view\",\"conversation:share\"]}",
          admin);
    }
  }

  /** 改数据范围后，令牌里的 {@code scope} 声明要跟着变（范围也是随令牌下发的）。 */
  @Test
  void 改数据范围后新令牌里的范围随之变化() throws Exception {
    String admin = accessToken("admin", "admin");
    String creatorRoleId = roleIdOf("CREATOR", admin);

    try {
      assertThat(signIn("admin", "admin").claims("scope")).as("ADMIN 初始是 all").isEqualTo("ALL");

      HttpResponse<String> updated =
          put(
              "/api/v1/roles/" + creatorRoleId + "/data-scope",
              "{\"dataScope\":\"SHARED\"}",
              admin);
      assertThat(updated.statusCode()).isEqualTo(200);
      assertThat(updated.body()).contains("\"dataScope\":\"SHARED\"");

      // 改的是 CREATOR，不影响 admin —— 这里顺带确认"改 A 不波及 B"
      assertThat(signIn("admin", "admin").claims("scope")).isEqualTo("ALL");
    } finally {
      put("/api/v1/roles/" + creatorRoleId + "/data-scope", "{\"dataScope\":\"OWN\"}", admin);
    }
  }

  @Test
  void 弱口令建用户被拒() throws Exception {
    String admin = accessToken("admin", "admin");
    String roleId = roleIdOf("USER", admin);

    HttpResponse<String> res =
        post(
            "/api/v1/users",
            "{\"username\":\"temp-user\",\"displayName\":\"临时\",\"roleId\":\""
                + roleId
                + "\",\"password\":\"123456\"}",
            admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("\"code\":1002").contains("至少 8 位");
  }

  // ────────────────────────── 第 3 周第二段 · 新增断言 ──────────────────────────

  /**
   * ★★ 本次最重要的一条：启动同步不得冲掉界面上改过的角色授权。
   *
   * <p>旧 {@code PermissionCatalog.sync()} 无条件按 {@code Permissions.of(code)} 差分， 管理员勾的授权会被重启静默改回去 ——
   * 而界面上看起来一切正常。这条用「改完 → 直接调 sync() 模拟重启」把它钉死。
   */
  @Test
  void 重启同步不冲掉界面上改过的角色授权() throws Exception {
    String admin = accessToken("admin", "admin");
    String userRoleId = roleIdOf("USER", admin);

    List<String> custom = List.of("assistant:view", "conversation:view");
    try {
      HttpResponse<String> updated =
          put(
              "/api/v1/roles/" + userRoleId + "/permissions",
              "{\"permissions\":[\"assistant:view\",\"conversation:view\"]}",
              admin);
      assertThat(updated.statusCode()).isEqualTo(200);

      // 直接注入并调用 sync()，模拟一次重启
      permissionCatalog.sync();

      assertThat(permissionsOfUser("chenjun"))
          .as("★ 重启后 USER 的授权必须仍是界面上设的那组，而不是被 seed 回代码映射")
          .containsExactlyInAnyOrderElementsOf(custom);
    } finally {
      put(
          "/api/v1/roles/" + userRoleId + "/permissions",
          "{\"permissions\":[\"knowledge:view\",\"assistant:view\",\"conversation:view\",\"conversation:share\"]}",
          admin);
    }
  }

  /** ★ 软删用户 ⇒ 列表看不见、详情 404、同名可重建（部分唯一索引 {@code where deleted_at is null}）。 */
  @Test
  void 软删用户后看不见且同名可重建() throws Exception {
    String admin = accessToken("admin", "admin");
    String roleId = roleIdOf("USER", admin);
    String username = "soft-del-" + System.nanoTime();

    HttpResponse<String> created =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\"软删样本\",\"roleId\":\""
                + roleId
                + "\"}",
            admin);
    assertThat(created.statusCode()).isEqualTo(200);
    String userId = jsonField(created.body(), "id");
    assertThat(userId).isNotBlank();

    // 删除前：列表里能找到
    assertThat(get("/api/v1/users?keyword=" + username, admin).body()).contains(username);

    HttpResponse<String> deleted = delete("/api/v1/users/" + userId, admin);
    assertThat(deleted.statusCode()).isEqualTo(200);

    // 删除后：列表找不到
    assertThat(get("/api/v1/users?keyword=" + username, admin).body())
        .as("软删后的用户必须从列表里消失")
        .doesNotContain("\"username\":\"" + username + "\"");

    // 详情 404（本周「看不见的资源返 404 而非 403」的当周实例）
    HttpResponse<String> detail = get("/api/v1/users/" + userId, admin);
    assertThat(detail.statusCode()).as("软删后的 id 查详情必须 404 —— 返 403 会确认「这个 id 存在」").isEqualTo(404);

    // 同名可重建（部分唯一索引只约束 deleted_at is null 的行）
    HttpResponse<String> recreated =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\"重建\",\"roleId\":\""
                + roleId
                + "\"}",
            admin);
    assertThat(recreated.statusCode()).as("软删后同名账号必须能重建 —— 部分唯一索引就是为这个场景准备的").isEqualTo(200);
    // 清理重建出来的，避免干扰后续
    delete("/api/v1/users/" + jsonField(recreated.body(), "id"), admin);
  }

  /** ★ 软删后刷新令牌必须失效（401），而不是 200。停用/删号要能掐断刷新。 */
  @Test
  void 软删用户后刷新令牌失效() throws Exception {
    String admin = accessToken("admin", "admin");
    String roleId = roleIdOf("USER", admin);
    String username = "refresh-kill-" + System.nanoTime();

    HttpResponse<String> created =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\"刷新掐断\",\"roleId\":\""
                + roleId
                + "\",\"password\":\"Str0ng@Pass9\"}",
            admin);
    assertThat(created.statusCode()).isEqualTo(200);
    String userId = jsonField(created.body(), "id");

    // 用新账号登录拿到 refresh token
    HttpResponse<String> login =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"Str0ng@Pass9\"}",
            null);
    assertThat(login.statusCode()).isEqualTo(200);
    String refreshToken = jsonField(login.body(), "refreshToken");
    assertThat(refreshToken).isNotBlank();

    assertThat(delete("/api/v1/users/" + userId, admin).statusCode()).isEqualTo(200);

    HttpResponse<String> refreshed =
        post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}", null);
    assertThat(refreshed.statusCode()).as("软删后的账号刷新必须 401 —— 否则被删的人还能继续续期").isEqualTo(401);
  }

  /** ADMIN 的权限点不能通过界面削减 ⇒ 400，文案与前端 RoleManage.vue 一字不差。 */
  @Test
  void 管理员角色的权限点不可削减() throws Exception {
    String admin = accessToken("admin", "admin");
    String adminRoleId = roleIdOf("ADMIN", admin);

    HttpResponse<String> res =
        put(
            "/api/v1/roles/" + adminRoleId + "/permissions",
            "{\"permissions\":[\"system:manage\"]}",
            admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body())
        .as("文案必须与前端 RoleManage.vue 的提示一字不差 —— 前端原文展示后端 message")
        .contains("管理员角色固定拥有全部权限点，不可裁剪");
  }

  /** ADMIN 的数据范围固定为全平台，不可修改（收窄属于自锁）。 */
  @Test
  void 管理员角色的数据范围不可修改() throws Exception {
    String admin = accessToken("admin", "admin");
    String adminRoleId = roleIdOf("ADMIN", admin);

    HttpResponse<String> res =
        put("/api/v1/roles/" + adminRoleId + "/data-scope", "{\"dataScope\":\"OWN\"}", admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("管理员角色的数据范围固定为全平台，不可修改");
  }

  /** 空权限列表 ⇒ 400。空列表与「从未初始化」在库里无法区分，会被启动 seed 静默塞回来。 */
  @Test
  void 空权限列表被拒() throws Exception {
    String admin = accessToken("admin", "admin");
    String userRoleId = roleIdOf("USER", admin);

    HttpResponse<String> res =
        put("/api/v1/roles/" + userRoleId + "/permissions", "{\"permissions\":[]}", admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("一个角色至少保留一个权限点");
  }

  /**
   * 不能删内置 admin。★ 前端拦得住按钮，但前端只是体验、不是安全边界。
   *
   * <p>同时覆盖两种违规：① 另一个管理员删 {@code admin} ⇒ 400；② 删自己 ⇒ 400（保证「不能删自己」在「不能删内置 admin」之前可被断言到）。
   */
  @Test
  void 不能删内置管理员也不能删自己() throws Exception {
    String admin = accessToken("admin", "admin");
    String adminRoleId = roleIdOf("ADMIN", admin);
    String operatorName = "op-" + System.nanoTime();

    // 建另一个管理员，用它去删内置 admin
    HttpResponse<String> created =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + operatorName
                + "\",\"displayName\":\"另一位管理员\",\"roleId\":\""
                + adminRoleId
                + "\",\"password\":\"Str0ng@Pass9\"}",
            admin);
    assertThat(created.statusCode()).isEqualTo(200);
    String operatorId = jsonField(created.body(), "id");
    String operatorToken = accessToken(operatorName, "Str0ng@Pass9");

    try {
      // ① 另一个管理员删内置 admin ⇒ 400
      HttpResponse<String> deleteBuiltin =
          delete("/api/v1/users/10000000-0000-0000-0000-000000000001", operatorToken);
      assertThat(deleteBuiltin.statusCode()).as("内置管理员账号是平台唯一的恢复入口，直连 API 也不能删").isEqualTo(400);
      assertThat(deleteBuiltin.body()).contains("内置管理员账号不可删除");

      // ② 删自己 ⇒ 400（「不能删自己」在「不能删内置 admin」之前，这条必须能被断言到）
      HttpResponse<String> deleteSelf = delete("/api/v1/users/" + operatorId, operatorToken);
      assertThat(deleteSelf.statusCode()).as("不能删自己 —— 这条也保证了「平台上永远至少有一个管理员」").isEqualTo(400);
      assertThat(deleteSelf.body()).contains("不能删除自己的账号");
    } finally {
      delete("/api/v1/users/" + operatorId, admin);
    }
  }

  /**
   * 建用户可以不带 password（走默认初始口令 + {@code mustChangePassword}）。
   *
   * <p>★ 断言三件事：① 200 且响应里<strong>不含密码哈希</strong>；② {@code initialPassword} 等于后端默认口令； ③
   * 该口令<strong>真的能登录</strong>，且 {@code mustChangePassword=true}。
   */
  @Test
  void 建用户不带口令返回初始口令且不含哈希() throws Exception {
    String admin = accessToken("admin", "admin");
    String roleId = roleIdOf("USER", admin);
    String username = "nopass-" + System.nanoTime();

    HttpResponse<String> created =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\"无口令\",\"roleId\":\""
                + roleId
                + "\"}",
            admin);

    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.body())
        .as("视图绝不能带密码哈希")
        .doesNotContain("passwordHash")
        .doesNotContain("$2a$");
    assertThat(created.body())
        .as("初始口令必须一次性回给创建者 —— 否则管理员只能把「123456」这种错误口径告诉新人")
        .contains("\"initialPassword\":\"" + UserService.DEFAULT_INITIAL_PASSWORD + "\"");

    // 列表与详情里 initialPassword 为 null（不能变成一个可随时查出的口令）
    assertThat(get("/api/v1/users?keyword=" + username, admin).body())
        .as("列表里不能出现 initialPassword —— 它不是可重查的口令")
        .doesNotContain("initialPassword");
    String userId = jsonField(created.body(), "id");
    assertThat(get("/api/v1/users/" + userId, admin).body())
        .as("详情里也不能出现 initialPassword")
        .doesNotContain("initialPassword");

    // 真的能用这个口令登录，且 mustChangePassword=true
    HttpResponse<String> login =
        post(
            "/api/v1/auth/login",
            "{\"username\":\""
                + username
                + "\",\"password\":\""
                + UserService.DEFAULT_INITIAL_PASSWORD
                + "\"}",
            null);
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(login.body()).contains("\"mustChangePassword\":true");

    // 用调用方自己传口令的创建：initialPassword 不该出现
    String username2 = "ownpass-" + System.nanoTime();
    HttpResponse<String> created2 =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username2
                + "\",\"displayName\":\"自带口令\",\"roleId\":\""
                + roleId
                + "\",\"password\":\"Str0ng@Pass9\"}",
            admin);
    assertThat(created2.statusCode()).isEqualTo(200);
    assertThat(created2.body())
        .as("调用方自己给了口令 ⇒ 不该再回 initialPassword")
        .doesNotContain("initialPassword");

    // 清理
    delete("/api/v1/users/" + userId, admin);
    delete("/api/v1/users/" + jsonField(created2.body(), "id"), admin);
  }

  /** ★ 账号名不可修改：发一个不一致的 username ⇒ 400（而不是「界面允许改、后端不改、且不报错」）。 */
  @Test
  void 账号名不可修改() throws Exception {
    String admin = accessToken("admin", "admin");
    String roleId = roleIdOf("USER", admin);
    String username = "immutable-" + System.nanoTime();

    HttpResponse<String> created =
        post(
            "/api/v1/users",
            "{\"username\":\""
                + username
                + "\",\"displayName\":\"原名\",\"roleId\":\""
                + roleId
                + "\"}",
            admin);
    assertThat(created.statusCode()).isEqualTo(200);
    String userId = jsonField(created.body(), "id");

    try {
      HttpResponse<String> renamed =
          put(
              "/api/v1/users/" + userId,
              "{\"username\":\""
                  + username
                  + "-changed\",\"displayName\":\"新名\",\"roleId\":\""
                  + roleId
                  + "\"}",
              admin);
      assertThat(renamed.statusCode())
          .as("username 与现账号不一致 ⇒ 400。静默忽略会变成「看起来改了、实际没改」")
          .isEqualTo(400);
      assertThat(renamed.body()).contains("账号名不可修改");

      // username 一致时正常通过（改显示名是合法操作）
      HttpResponse<String> sameName =
          put(
              "/api/v1/users/" + userId,
              "{\"username\":\""
                  + username
                  + "\",\"displayName\":\"新显示名\",\"roleId\":\""
                  + roleId
                  + "\"}",
              admin);
      assertThat(sameName.statusCode()).isEqualTo(200);
      assertThat(sameName.body()).contains("新显示名");
    } finally {
      delete("/api/v1/users/" + userId, admin);
    }
  }

  /** ★ 不能改自己的角色（自锁防护）；但改自己的显示名是合法操作。 */
  @Test
  void 不能修改自己的角色但可以改显示名() throws Exception {
    String admin = accessToken("admin", "admin");
    String adminRoleId = roleIdOf("ADMIN", admin);
    String userRoleId = roleIdOf("USER", admin);
    String adminId = jsonField(get("/api/v1/users?keyword=admin", admin).body(), "id");
    assertThat(adminId).isNotBlank();

    // 改自己的角色 ⇒ 400
    HttpResponse<String> demote =
        put(
            "/api/v1/users/" + adminId,
            "{\"username\":\"admin\",\"displayName\":\"管理员\",\"roleId\":\"" + userRoleId + "\"}",
            admin);
    assertThat(demote.statusCode()).as("直连 API 把唯一的管理员降级自己 ⇒ 平台永久没人能管账号（不可恢复）").isEqualTo(400);
    assertThat(demote.body()).contains("不能修改自己的角色");

    // 改自己的显示名 ⇒ 200（不能误伤）
    HttpResponse<String> rename =
        put(
            "/api/v1/users/" + adminId,
            "{\"username\":\"admin\",\"displayName\":\"管理员改名\",\"roleId\":\"" + adminRoleId + "\"}",
            admin);
    assertThat(rename.statusCode()).isEqualTo(200);
    assertThat(rename.body()).contains("管理员改名");

    // 恢复显示名
    put(
        "/api/v1/users/" + adminId,
        "{\"username\":\"admin\",\"displayName\":\"管理员\",\"roleId\":\"" + adminRoleId + "\"}",
        admin);
  }

  // ────────────────────────── 枚举新码钉死 ──────────────────────────

  /**
   * ★★ 库里存的就是新码（SCREAMING_SNAKE_CASE），不是中文、不是小写。
   *
   * <p>直接 JDBC 查原始值 —— 不经过 JPA/枚举转换，钉的是<strong>库存值本身</strong>。 避免有人加了 {@code @JsonValue} / {@code
   * AttributeConverter} 又把中文写回去，而单元测试里看起来一切正常。
   */
  @Test
  void 库里存的是新码不是中文() {
    // iam_user.status：4 个 seed 账号都应是 ACTIVE
    List<String> userStatuses =
        jdbc.queryForList(
            "select distinct status from iam_user where deleted_at is null", String.class);
    assertThat(userStatuses).as("用户状态列只能出现新码").containsExactly("ACTIVE");

    // iam_role.data_scope / max_classification
    List<String> scopes =
        jdbc.queryForList("select distinct data_scope from iam_role", String.class);
    assertThat(scopes)
        .as("数据范围列只能出现新码")
        .allMatch(s -> s.equals("OWN") || s.equals("SHARED") || s.equals("ALL"));

    List<String> classifications =
        jdbc.queryForList("select distinct max_classification from iam_role", String.class);
    assertThat(classifications)
        .as("密级列只能出现新码")
        .allMatch(
            c ->
                c.equals("PUBLIC")
                    || c.equals("INTERNAL")
                    || c.equals("SECRET")
                    || c.equals("CONFIDENTIAL"));

    // ★ ADMIN 的具体值也钉一下（V6 seed 后 V10 翻译的结果）
    String adminScope =
        jdbc.queryForObject("select data_scope from iam_role where code = 'ADMIN'", String.class);
    assertThat(adminScope).isEqualTo("ALL");
    String adminClearance =
        jdbc.queryForObject(
            "select max_classification from iam_role where code = 'ADMIN'", String.class);
    assertThat(adminClearance).isEqualTo("CONFIDENTIAL");
  }

  /**
   * ★ JSON 序列化值也是新码 —— 令牌里 scope/clearance 声明、接口响应里的 status 字段， 都应该是 SCREAMING_SNAKE_CASE。避免有人加了
   * {@code @JsonValue} 又把中文写回去。
   */
  @Test
  void JSON契约值是新码() throws Exception {
    // 登录后的令牌里 scope/clearance 是新码
    String admin = accessToken("admin", "admin");
    assertThat(signIn("admin", "admin").claims("scope")).isEqualTo("ALL");
    assertThat(signIn("admin", "admin").claims("clearance")).isEqualTo("CONFIDENTIAL");

    // 用户列表响应里的 status 字段是新码
    HttpResponse<String> res = get("/api/v1/users", admin);
    assertThat(res.body()).contains("\"status\":\"ACTIVE\"");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /** 从包络里取数字型 {@code total}（{@link #jsonField} 只匹配字符串值）。 */
  private static long totalOf(String json) {
    Matcher matcher = Pattern.compile("\"total\":(\\d+)").matcher(json);
    if (!matcher.find()) {
      throw new IllegalStateException("响应里没有 total：" + json);
    }
    return Long.parseLong(matcher.group(1));
  }

  private List<String> permissionsOfUser(String username) throws Exception {
    String body = get("/api/v1/users/me", accessToken(username, "123456")).body();
    Matcher matcher = Pattern.compile("\"permissions\":\\[([^\\]]*)\\]").matcher(body);
    if (!matcher.find()) {
      return List.of();
    }
    List<String> codes = new ArrayList<>();
    for (String raw : matcher.group(1).split(",")) {
      String trimmed = raw.trim().replace("\"", "");
      if (!trimmed.isEmpty()) {
        codes.add(trimmed);
      }
    }
    return codes;
  }

  private String roleIdOf(String code, String adminToken) throws Exception {
    String body = get("/api/v1/roles", adminToken).body();
    Matcher matcher = ROLE_ENTRY.matcher(body);
    while (matcher.find()) {
      if (code.equals(matcher.group(2))) {
        return matcher.group(1);
      }
    }
    throw new IllegalStateException("找不到角色 " + code + "：" + body);
  }

  private record SignIn(String accessToken) {
    String claims(String key) {
      return jsonField(accessToken, key);
    }
  }

  private SignIn signIn(String username, String password) throws Exception {
    return new SignIn(accessToken(username, password));
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

  /** 从 JWT payload 里读一个字段（不验签，只是"看一眼里面有什么"）。 */
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

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while ((from = haystack.indexOf(needle, from)) >= 0) {
      count++;
      from += needle.length();
    }
    return count;
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
        // DELETE 等无 body 的非 GET 请求
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
