package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 契约漂移检测（{@code ROADMAP §4.7.3} 第 4 周测试项 ④ / {@code §7.2} 的"契约漂移检测"道）。
 *
 * <p>★ 它盯的是<strong>两类漂移</strong>，而且方向相反、危险程度也相反：
 *
 * <ol>
 *   <li><strong>后端有、契约没有</strong> ⇒ 直接失败。这是最危险的一类：后端偷偷加了端点没登记，前端永远不知道， 而它很可能是没配权限的洞（{@code
 *       AuthorizationDeclarationTest} 管"忘写注解"，这里管"忘登记"——两者都属于静默失效）。
 *   <li><strong>契约有、后端没有</strong> ⇒ 必须出现在 {@link #UNIMPLEMENTED_PREFIXES} 里，否则失败。 前端已经跑在后端前面（{@code
 *       ROADMAP §3.1}），契约上百个端点、后端目前只落了身份域 —— 不能要求后端立刻把它们都实现；
 *       但清单必须<strong>显式、可审阅</strong>，否则它会变成一个永久的黑洞。
 * </ol>
 *
 * <p>★ 归一化（不做会被命名差异吵聋）：TS 的 {@code /users/:id} 与 Spring 的 {@code /users/{id}} 归一成 {@code
 * /users/{*}} 再比对 —— <strong>路径参数名字不同不算漂移</strong>。 查询参数不参与比对（只比路径）。后端的 {@code /api/v1} 前缀剥掉 （契约里的
 * {@code url} 不带它）。
 *
 * <p>★ 与 {@code AuthorizationDeclarationTest} / {@code ModuleBoundaryTest} 同构：
 * <strong>同一规则跑两组数据</strong> —— 真实契约必须过、金丝雀样本必须不过。 若金丝雀通过了，说明这条闸门已经失效，主代码的绿说明不了任何事。
 *
 * <p>★ 读法：后端侧用 {@link RequestMappingHandlerMapping} 的真实映射（{@code @RestController} 拼出的完整路径模板），
 * 前端侧<strong>只读 TS 源码里的 {@code url: '...'} 字面量</strong>（正则解析，不引 TS 解析器、不起前端、不跑 Node）。
 */
@SpringBootTest
@Testcontainers
// 契约对账不需要验证码开关；显式关掉以免与生产路径的开关状态耦合
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ContractDriftTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  @Autowired ApplicationContext applicationContext;

  /**
   * 未实现清单：契约有、后端还没有的<strong>域名前缀</strong>。
   *
   * <p>★ 按域名前缀列（不是逐条 URL）：同一个域下的几十条端点会一起落地，逐条登记会把清单变成第二份契约。
   *
   * <p>★ 理由写在这里，而不是散落在失败信息里：前端已经跑在后端前面（{@code ROADMAP §3.1}）—— 契约约 115 个端点， 后端目前主要实现了身份域（模型域的 REST
   * 还在 {@code modelcatalog} 迁移/领域模型阶段）。 不能要求后端立刻把它们都实现，但<strong>每一项都要能回答"计划落在哪一周"</strong>（见 {@code
   * docs/ROADMAP.md §4.7.3}）。 清单一旦失去可审阅性，它就变成永久黑洞 —— 那正是这条规则要防的。
   */
  private static final Set<String> UNIMPLEMENTED_PREFIXES =
      Set.of(
          // 第 5 周 0.2.0：建档（密级必填 + ceiling）；上传/列表随 0.2.x–0.3.0
          "/knowledge-bases",
          // 第 5–8 周 0.2.x–0.3.0：文档上传、解析、入库（含 enabled/retry/embedding/reembed）
          "/documents",
          // 第 7 周 0.2.2：分块 + 结构化前缀 + 切分预览（segments CRUD / batch-move）
          "/segments",
          // 第 7 周 0.2.2：切分预览（预览与入库用同一个分块器）
          "/segmentation",
          // 第 4 周 0.1.3：模型注册 / 能力探测 / load。
          // ★ 与任务描述的差异见报告：modelcatalog 目前只有领域/配置，REST 控制器还没落
          "/models",
          // 第 15 周 0.4.3：应用配置 + 发布校验（§3.1 记 M4）；含 import/export/credentials/api-keys
          "/assistants",
          // §3.1 M4：提示词管理页（pages/assistants/prompts/）—— 前端已存在，后端随 assistant 域
          "/prompt-types",
          "/prompt-configs",
          // 第 15 周 0.4.3 / M5.5：编排画布接契约（workflow draft/validate/publish/versions）
          "/workflow",
          // 第 17 周 0.4.5：SSE 问答链路（会话 CRUD 与消息流）
          "/conversations",
          "/messages",
          // 第 16 周 0.4.4：公开凭证 + 分享冻结（/public/:token 链路）
          "/public",
          // 第 17 周随问答链路：附件上传（POST /files，multipart）
          "/files",
          // 第 18 周 0.5.0：赞踩 + 反馈闭环（§3.1 记 M5，反馈处理后端 M5 实现）
          "/feedbacks",
          // §3.1「工作台」：4 项指标来自 analytics 域（随 0.4.x 之后）
          "/analytics",
          // 第 10–11 周 0.3.2–0.3.3：三模式检索 + RRF + 密级 pre-filter
          "/retrieval");

  /**
   * 已知例外：双向都不比对的<strong>精确路径</strong>，每项必须写清理由。
   *
   * <p>★ 它不是"放过一切"的垃圾桶：只放"形态特殊 / 已被裁决不实现"的少数路径， 且每项都要能被审阅者独立验证。
   */
  private static final Set<String> KNOWN_EXCEPTIONS =
      Set.of(
          // ★ 后端有、api/*.ts 没有 —— 形态特殊：它被 lib/http.ts 的 401 拦截器直接调用
          //   （不走 request() 封装），所以不会出现在 api/*.ts 的 url: 字面量里。
          //   ROADMAP §3.4 裁决「补进契约文件」，在补登记之前先在这里挂账，避免规则 1 误伤。
          "/auth/refresh",
          // ★ mock 专用端点（ROADMAP §3.4 已裁决后端**不实现**：它们是"故意违法样例图"的载体，
          //   只服务于校验演示）。目前只在 mock/handlers/workflow.ts 里，不在 api/*.ts 契约中；
          //   若将来误入契约，靠此例外明确"后端永不实现"，而不是被未实现清单吸收成永久欠账。
          "/workflow/demo/invalid-graph",
          "/workflow/demo/valid-graph");

  /** TS 里 {@code url: '/x/:id'} 或 {@code url: `/x/${id}`} 或 {@code url: "/x"} 的字面量。 */
  private static final Pattern URL_LITERAL = Pattern.compile("url\\s*:\\s*(['\"`])([^'\"`]+)\\1");

  /** 后端 {@code /api/v1} 前缀 —— 契约里的 {@code url} 不带它，比对前要剥掉。 */
  private static final String API_PREFIX = "/api/v1";

  // ────────────────────────── 主闸门 ──────────────────────────

  @Test
  void 契约与后端必须双向对得上() {
    Set<String> backend = backendPaths();
    Set<String> contract = contractPaths();

    assertThat(backend).as("后端映射为空 ⇒ RequestMappingHandlerMapping 没拿到，这条闸门在空跑").isNotEmpty();
    assertThat(contract).as("契约解析为空 ⇒ url 字面量正则失效或 api 目录读错，这条闸门在空跑").isNotEmpty();

    assertNoDrift(backend, contract, UNIMPLEMENTED_PREFIXES, KNOWN_EXCEPTIONS);
  }

  /**
   * ★ 未实现清单不得变成黑洞：某前缀已经<strong>全部</strong>落地时，必须从清单里删掉。
   *
   * <p>否则清单会慢慢堆积"曾经欠过、现在早已实现"的项，审阅者再也分不清哪些是真欠账。
   */
  @Test
  void 未实现清单里的域名必须仍然有欠账() {
    Set<String> backend = backendPaths();
    Set<String> contract = contractPaths();

    for (String prefix : UNIMPLEMENTED_PREFIXES) {
      Set<String> stillMissing =
          contract.stream()
              .filter(p -> underPrefix(p, prefix))
              .filter(p -> !backend.contains(p))
              .collect(Collectors.toCollection(TreeSet::new));
      assertThat(stillMissing)
          .as("未实现清单里的 %s 在契约中已无欠账（后端全实现了，或契约里根本没这个域）⇒ 请从清单删除，" + "否则它会变成没人再看的黑洞", prefix)
          .isNotEmpty();
    }
  }

  // ────────────────────────── 金丝雀 ──────────────────────────

  /**
   * 金丝雀（规则 2）：契约里有、后端没有、又不在未实现清单里的路径，必须被抓住。
   *
   * <p>★ 与 {@code AuthorizationDeclarationTest} 同构 —— 同一条 {@link #assertNoDrift} 跑两组数据：
   * 真实契约必须过、坏样本必须不过。
   */
  @Test
  void 金丝雀_契约多出的路径必须被抓到() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 后端：一条常规端点
                    Set.of("/users", "/users/{*}"),
                    // 契约：多出一条后端没有的 /ghost —— 且未实现清单/例外都是空的
                    Set.of("/users", "/users/{*}", "/ghost"),
                    Set.of(),
                    Set.of()))
        .as("坏样本没被拦下 ⇒ 这条闸门已经失效，主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 金丝雀（规则 1）：后端有、契约没有的路径，必须被抓住。
   *
   * <p>这是最危险的一类漂移（后端偷偷加端点），金丝雀必须分别证明两个方向都活着 —— 只写一条"双向"样本 的话，规则 1 坏掉而规则 2 还活着时，金丝雀照样红，掩盖了半边失明。
   */
  @Test
  void 金丝雀_后端多出的路径必须被抓到() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 后端：多出一条契约没有的 /secret-endpoint
                    Set.of("/users", "/secret-endpoint"),
                    // 契约：只登记了 /users
                    Set.of("/users"),
                    Set.of(),
                    Set.of()))
        .as("坏样本没被拦下 ⇒ 这条闸门已经失效，主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 金丝雀（任务书要求的组合样本）：假契约同时含"契约多一条"与"后端多一条"，必须失败。
   *
   * <p>与上面两条方向型金丝雀并列：组合样本证明"整道闸门至少会红"，方向型样本证明"两边各自都活着"。
   */
  @Test
  void 金丝雀_双向漂移样本必须失败() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    Set.of("/users/{*}", "/auth/login"),
                    // 多一条 /ghost，且缺了 /auth/login
                    Set.of("/users/{*}", "/ghost"),
                    Set.of(),
                    Set.of()))
        .as("双向坏样本没被拦下 ⇒ 这条闸门已经失效")
        .isInstanceOf(AssertionError.class);
  }

  // ────────────────────────── 规则本体 ──────────────────────────

  /**
   * 双向对账规则。同一规则跑两组数据：真实契约必须过、金丝雀样本必须不过。
   *
   * @param backendPaths 后端路径（已归一）
   * @param contractPaths 契约路径（已归一）
   * @param unimplementedPrefixes 未实现清单（域名前缀；契约有、后端没有时必须落在这里）
   * @param knownExceptions 已知例外（精确路径，双向都不比对）
   */
  private static void assertNoDrift(
      Collection<String> backendPaths,
      Collection<String> contractPaths,
      Set<String> unimplementedPrefixes,
      Set<String> knownExceptions) {

    Set<String> backend = new TreeSet<>(backendPaths);
    Set<String> contract = new TreeSet<>(contractPaths);

    // ── 规则 1：后端有、契约没有 ⇒ 直接失败（最危险：偷偷加的端点，前端永远不知道）──
    Set<String> backendOnly =
        backend.stream()
            .filter(p -> !contract.contains(p))
            .filter(p -> !knownExceptions.contains(p))
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(backendOnly)
        .as(
            "后端有、契约没有的端点（规则 1）⇒ 必须先登记进 frontend/src/api/*.ts，"
                + "或写进 KNOWN_EXCEPTIONS 并注明理由。未登记的端点前端永远发现不了，"
                + "还可能是没配权限的洞")
        .isEmpty();

    // ── 规则 2：契约有、后端没有 ⇒ 必须出现在未实现清单里 ──
    Set<String> contractOnly =
        contract.stream()
            .filter(p -> !backend.contains(p))
            .filter(p -> !knownExceptions.contains(p))
            .filter(p -> unimplementedPrefixes.stream().noneMatch(prefix -> underPrefix(p, prefix)))
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(contractOnly)
        .as(
            "契约有、后端没有、又不在未实现清单里的端点（规则 2）⇒ 要么实现它，"
                + "要么把它的域名前缀加进 UNIMPLEMENTED_PREFIXES 并注明计划周次。"
                + "清单必须显式可审阅，否则会变成永久黑洞")
        .isEmpty();
  }

  /** 路径是否落在某个域名前缀下：{@code /knowledge-bases/{*}/documents} 落在 {@code /knowledge-bases} 下。 */
  private static boolean underPrefix(String normalizedPath, String prefix) {
    return normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/");
  }

  // ────────────────────────── 后端侧 ──────────────────────────

  /**
   * 从 {@link RequestMappingHandlerMapping} 拼出真实映射，只保留 {@code /api/v1/**}，剥前缀后归一。
   *
   * <p>★ 用真实映射而不是扫注解：注解扫描会漏掉组合注解、接口默认方法、以及将来通过 {@code WebMvcRegistrations} 注册的端点 ——
   * 而"前端调的路径"与"DispatcherServlet 真认识的路径"是否一致，恰恰是本测试要回答的问题。
   */
  private Set<String> backendPaths() {
    RequestMappingHandlerMapping mapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

    Set<String> paths = new TreeSet<>();
    mapping
        .getHandlerMethods()
        .keySet()
        .forEach(
            info -> info.getPatternValues().forEach(pattern -> addBackendPattern(paths, pattern)));
    return paths;
  }

  private static void addBackendPattern(Set<String> paths, String rawPattern) {
    // 只对账业务 API；actuator / error 等不参与（契约里也没有）
    if (!rawPattern.startsWith(API_PREFIX)) {
      return;
    }
    String withoutPrefix = rawPattern.substring(API_PREFIX.length());
    if (withoutPrefix.isEmpty()) {
      withoutPrefix = "/";
    }
    paths.add(normalize(withoutPrefix));
  }

  // ────────────────────────── 契约侧 ──────────────────────────

  /**
   * 解析 {@code frontend/src/api} 下所有 {@code .ts} 的 {@code url: '...'} 字面量。
   *
   * <p>★ 只读源码，不引 TS 解析器、不起前端、不跑 Node —— 这道闸门要能在任何构建机上跑。
   */
  private static Set<String> contractPaths() {
    Path apiDir = resolveFrontendApiDir();
    Set<String> paths = new TreeSet<>();
    try (Stream<Path> files = Files.walk(apiDir)) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".ts")).toList()) {
        collectUrlsFromFile(file, paths);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取前端契约目录失败：" + apiDir, e);
    }
    return paths;
  }

  private static void collectUrlsFromFile(Path file, Set<String> paths) {
    String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取前端契约文件失败：" + file, e);
    }
    Matcher m = URL_LITERAL.matcher(source);
    while (m.find()) {
      String url = m.group(2);
      // 查询参数不参与比对（只比路径）
      int query = url.indexOf('?');
      if (query >= 0) {
        url = url.substring(0, query);
      }
      if (!url.startsWith("/")) {
        throw new AssertionError(
            "契约里的 url 必须以 / 开头（相对路径会随 baseURL 漂移）：" + file + " → " + m.group(0));
      }
      // 模板串 url: `/x/${id}` 与 :id 占位一起归一；${...} → {*} 在 normalize 里处理
      paths.add(normalize(url));
    }
  }

  /**
   * 定位 {@code frontend/src/api}。
   *
   * <p>★ Surefire 的工作目录是 {@code backend/}，但本地直接跑 / IDE 里可能是项目根。 两个候选都试； <strong>找不到必须失败</strong> ——
   * 静默跳过会让这条闸门永远绿而什么都不查。
   */
  private static Path resolveFrontendApiDir() {
    Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(
            // Surefire 工作目录 = backend/
            userDir.resolve("../frontend/src/api"),
            // 从项目根跑（mvn -f backend/pom.xml 时工作目录仍是 backend/，此条兜底）
            userDir.resolve("frontend/src/api"),
            // 更深的工作目录兜底
            userDir.resolve("../../frontend/src/api"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized)) {
        return normalized;
      }
    }
    throw new AssertionError(
        "找不到前端契约目录 frontend/src/api。试过："
            + candidates.stream().map(p -> p.normalize().toString()).toList()
            + "；user.dir="
            + userDir
            + "。这道闸门不能静默跳过 —— 找不到目录必须失败并说清路径。");
  }

  // ────────────────────────── 归一化 ──────────────────────────

  /**
   * 路径归一：占位符名字抹平、尾斜杠抹平。
   *
   * <p>★ 参数名不同不算漂移 —— {@code /users/:userId}（TS）与 {@code /users/{id}}（Spring） 归一后都是 {@code
   * /users/{*}}。 前端写 {@code path: { userId }}、后端叫 {@code @PathVariable String id} 是常态，拿名字对账只会制造噪音。
   */
  private static String normalize(String path) {
    String p = path.trim();
    // 模板串插值：`/x/${id}` → /x/{*}
    p = p.replaceAll("\\$\\{[^}]+\\}", "{*}");
    // Spring 占位：{id}、{id:regex}、{*spring} → {*}
    p = p.replaceAll("\\{[^}]+\\}", "{*}");
    // TS 占位：:id → {*}（避免把 http:// 之类误伤 —— 契约 url 都是绝对路径，不含 scheme）
    p = p.replaceAll(":[A-Za-z_][A-Za-z0-9_]*", "{*}");
    // 尾斜杠抹平（根路径除外）
    if (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.isEmpty()) {
      p = "/";
    }
    return p;
  }
}
