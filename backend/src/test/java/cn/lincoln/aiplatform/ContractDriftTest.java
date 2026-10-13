package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * <p>★ 对账维度是<strong>方法 + 路径</strong>，不是只对路径：只对路径时，同路径 {@code GET→POST} 的单边变更
 * 两边都能"看起来对上"，闸门全程绿（{@code listBaseModels} 真漏过一次）。 路径维度的两条规则语义不变；HTTP 方法是<strong>叠加约束</strong> ——
 * 路径两边都有时，方法集合必须一致。
 *
 * <p>★ 任意方法不算漂移：后端 {@code @RequestMapping} 不写 {@code method} 时是任意方法，按"匹配所有"处理，
 * <strong>不与契约的具体方法逐一对账</strong> —— 否则会把合法的宽松映射误报成漂移。
 *
 * <p>★ 归一化（不做会被命名差异吵聋）：TS 的 {@code /users/:id} 与 Spring 的 {@code /users/{id}} 归一成 {@code
 * /users/{*}} 再比对 —— <strong>路径参数名字不同不算漂移</strong>。 查询参数不参与比对（只比路径）。后端的 {@code /api/v1} 前缀剥掉 （契约里的
 * {@code url} 不带它）。
 *
 * <p>★ 与 {@code AuthorizationDeclarationTest} / {@code ModuleBoundaryTest} 同构：
 * <strong>同一规则跑两组数据</strong> —— 真实契约必须过、金丝雀样本必须不过。 若金丝雀通过了，说明这条闸门已经失效，主代码的绿说明不了任何事。
 *
 * <p>★ 读法：后端侧用 {@link RequestMappingHandlerMapping} 的真实映射（{@code @RestController} 拼出的完整路径模板 +
 * {@code getMethodsCondition()} 的方法集合）， 前端侧<strong>只读 TS 源码里 {@code request({ method, url })}
 * 字面量</strong>（正则 + 花括号配对，不引 TS 解析器、不起前端、不跑 Node）。
 */
@SpringBootTest
@Testcontainers
// 契约对账不需要验证码开关；显式关掉以免与生产路径的开关状态耦合
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ContractDriftTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired ApplicationContext applicationContext;

  /**
   * 未实现清单：契约有、后端还没有的<strong>域名前缀</strong>。
   *
   * <p>★ 按域名前缀列（不是逐条 URL）：同一个域下的几十条端点会一起落地，逐条登记会把清单变成第二份契约。
   *
   * <p>★ 理由写在这里，而不是散落在失败信息里：前端已经跑在后端前面（{@code ROADMAP §3.1}）—— ★ 契约 **128** 个
   * 「方法 + 路径」端点（2026-10-13 实测；此前这里写"约 115"**是过期数字**）， 后端目前已落 70 个。
   * 不能要求后端立刻把它们都实现，但<strong>每一项都要能回答"计划落在哪一周"</strong>（见 {@code docs/ROADMAP.md §4.7.3}）。
   * 清单一旦失去可审阅性，它就变成永久黑洞 —— 那正是这条规则要防的。
   */
  private static final Set<String> UNIMPLEMENTED_PREFIXES =
      Set.of(
          // ★ 建档 7 个端点已于第 5 周 0.2.0 批 1 落地；documents 子资源的建档
          //   （POST/GET /knowledge-bases/{*}/documents）已于批 2 落地。
          //   本前缀下仍欠账两条（都在 api/ingestion.ts，属 P1）：
          //   · POST /knowledge-bases/{*}/uploads（暂存上传：向导已改成"上传即建档"，不再需要它）
          //   · GET  /knowledge-bases/{*}/upload-policy（上传前的大小/数量/扩展名策略，
          //     界面已有前端常量兜底，故至今没挡住任何流程）
          //   ★ /knowledge-bases/{*}/reembed 已于 0.3.1 落地（库级重新向量化），从本条注释撤下。
          "/knowledge-bases",
          // ★ /documents 已于 0.3.1 全部落地（改名 PATCH /{id}、启停 PATCH /{id}/enabled、
          //   删除 DELETE /{id}，加上原有的 embedding/retry/reembed）⇒ 从清单删除。
          //   删除是被"防黑洞"测试逼出来的：留着它就成了"曾经欠过、现在早已实现"的噪音。
          // ★ /segments 也于 0.3.1 全部落地（PATCH /{*}/enabled 启停、POST /{*}/embedding 逐段重向量、
          //   DELETE /batch 批量删、POST /batch-move 跨文档搬移）⇒ 一并从清单删除。
          // 第 15 周 0.4.3：应用配置 + 发布校验（§3.1 记 M4）；含 import/export/credentials/api-keys
          // ★ 0.4.3 已落地其中 7 个：GET /assistants（列表）· POST /assistants · GET/PUT /{id} ·
          //   GET /{id}/publish-check · PATCH /{id}/published · GET /{id}/export。★ DELETE /{id} 也在。
          // ★ 第 16 周 0.4.4 再落 4 个（公开凭证）：GET/POST /{appId}/credentials ·
          //   PATCH /{appId}/credentials/{id}/enabled · POST /{appId}/credentials/{id}/revoke。
          // ★ 前缀仍留在清单里：剩下的（市场筛选 / 收藏 / 标签 / API Key / 共享写 / 导入）
          //   确实还没实现，按"前缀登记"允许缺失 —— 而"曾经欠过、现在早已实现"的噪音要靠人在这行更新。
          //   ★ 2026-10-13 逐条核过一遍：这条注释此前把"列表与市场筛选"整体算作欠账，而 `GET /assistants`
          //     那时**已经落地**（`AssistantController` 的 `@GetMapping`）—— 一行注释的滞后果然会长成噪音。
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
          "/analytics"
          // ★ /retrieval 已于 0.3.2 落地（POST /retrieval/test 三模式 + RRF；两个参数端点
          //   挂在 /knowledge-bases/{*}/retrieval 下）⇒ 从清单删除。
          //   与上面 /documents、/segments 两次一样，删除是被这条"防黑洞"规则逼出来的：
          //   留着它就成了"曾经欠过、现在早已实现"的噪音。
          );

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

  /**
   * {@code request({ method: 'GET', url: '/x' })} 的选项对象起始 —— 锚定左花括号。
   *
   * <p>★ 只认 {@code request(...)} 的内联对象字面量：method 与 url 必须在<strong>同一个</strong>对象里成对取出，
   * 否则同文件里两条相邻调用会把 method 配错对。 泛型允许一层嵌套（{@code PageResult<ModelInfo>}）。
   */
  private static final Pattern REQUEST_OPTIONS_OPEN =
      Pattern.compile("\\brequest\\s*(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>)?\\s*\\(\\s*\\{");

  /** 选项对象里的 {@code method: 'GET'}（可小写）；省略时按 {@code lib/http.ts} 默认 GET。 */
  private static final Pattern METHOD_LITERAL =
      Pattern.compile("method\\s*:\\s*['\"]([A-Za-z]+)['\"]");

  /** 选项对象里的 {@code url: '/x/:id'} / {@code url: `/x/${id}`} / {@code url: "/x"} 字面量。 */
  private static final Pattern URL_LITERAL = Pattern.compile("url\\s*:\\s*(['\"`])([^'\"`]+)\\1");

  /** 后端 {@code /api/v1} 前缀 —— 契约里的 {@code url} 不带它，比对前要剥掉。 */
  private static final String API_PREFIX = "/api/v1";

  /**
   * 方法集合的空集哨兵：后端 {@code @RequestMapping} 不写 {@code method} 时 {@code getMethods()} 为空，
   * 表示<strong>任意方法</strong>（匹配所有）。
   */
  private static final Set<String> ANY_METHODS = Set.of();

  // ────────────────────────── 主闸门 ──────────────────────────

  @Test
  void 契约与后端必须双向对得上() {
    Map<String, Set<String>> backend = backendEndpoints();
    Map<String, Set<String>> contract = contractEndpoints();

    assertThat(backend).as("后端映射为空 ⇒ RequestMappingHandlerMapping 没拿到，这条闸门在空跑").isNotEmpty();
    assertThat(contract).as("契约解析为空 ⇒ request 选项对象解析失效或 api 目录读错，这条闸门在空跑").isNotEmpty();

    assertNoDrift(backend, contract, UNIMPLEMENTED_PREFIXES, KNOWN_EXCEPTIONS);
  }

  /**
   * ★ 未实现清单不得变成黑洞：某前缀已经<strong>全部</strong>落地时，必须从清单里删掉。
   *
   * <p>否则清单会慢慢堆积"曾经欠过、现在早已实现"的项，审阅者再也分不清哪些是真欠账。
   *
   * <p>★ 仍按<strong>路径</strong>维度查欠账（与清单语义一致）：方法差异由主闸门的方法约束单独盯。
   */
  @Test
  void 未实现清单里的域名必须仍然有欠账() {
    Map<String, Set<String>> backend = backendEndpoints();
    Map<String, Set<String>> contract = contractEndpoints();

    for (String prefix : UNIMPLEMENTED_PREFIXES) {
      Set<String> stillMissing =
          contract.keySet().stream()
              .filter(p -> underPrefix(p, prefix))
              .filter(p -> !backend.containsKey(p))
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
                    endpoints("GET", "/users", "GET", "/users/{*}"),
                    // 契约：多出一条后端没有的 /ghost —— 且未实现清单/例外都是空的
                    endpoints("GET", "/users", "GET", "/users/{*}", "GET", "/ghost"),
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
                    endpoints("GET", "/users", "POST", "/secret-endpoint"),
                    // 契约：只登记了 /users
                    endpoints("GET", "/users"),
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
                    endpoints("GET", "/users/{*}", "POST", "/auth/login"),
                    // 多一条 /ghost，且缺了 /auth/login
                    endpoints("GET", "/users/{*}", "GET", "/ghost"),
                    Set.of(),
                    Set.of()))
        .as("双向坏样本没被拦下 ⇒ 这条闸门已经失效")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 金丝雀（方法维度）：同路径上「契约 GET / 后端 POST」——以及反过来——都必须失败。
   *
   * <p>★ 这是 {@code listBaseModels} 真实漏过一次的形状：路径对得上、方法对不上，只对路径的闸门全程绿。 两个方向都要红：只写一边的话，单向失明会被另一向的红掩盖。
   */
  @Test
  void 金丝雀_同路径方法漂移必须被抓到() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 后端只有 POST /models/base-models
                    endpoints("POST", "/models/base-models"),
                    // 契约却声明 GET —— 路径相同、方法不同
                    endpoints("GET", "/models/base-models"),
                    Set.of(),
                    Set.of()))
        .as("契约 GET / 后端 POST 的单边变更没被拦下 ⇒ 方法维度的闸门已经失效")
        .isInstanceOf(AssertionError.class);

    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 反过来：后端只有 GET
                    endpoints("GET", "/models/base-models"),
                    // 契约却声明 POST
                    endpoints("POST", "/models/base-models"),
                    Set.of(),
                    Set.of()))
        .as("契约 POST / 后端 GET 的单边变更没被拦下 ⇒ 方法维度的闸门已经失效")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 反金丝雀：后端任意方法（{@code @RequestMapping} 不写 method）不得被误报成方法漂移。
   *
   * <p>★ 闸门升级到方法维度后，最容易犯的错是拿空方法集去和契约的具体方法逐一对账 —— 那会把所有宽松映射打成漂移。这里钉死"任意方法 = 匹配所有"的语义。
   */
  @Test
  void 任意方法的后端映射不得误报方法漂移() {
    // 不抛异常即通过 —— 任意方法吸收契约上的任意具体方法
    assertNoDrift(
        Map.of("/models/base-models", ANY_METHODS),
        endpoints("GET", "/models/base-models", "POST", "/models/base-models"),
        Set.of(),
        Set.of());
  }

  // ────────────────────────── 规则本体 ──────────────────────────

  /**
   * 双向对账规则。同一规则跑两组数据：真实契约必须过、金丝雀样本必须不过。
   *
   * <p>路径维度沿用既有两条规则；方法维度是叠加约束（路径两边都有时，方法集合必须一致）。
   *
   * @param backendEndpoints 后端端点：归一化路径 → 方法集合（空集 = 任意方法）
   * @param contractEndpoints 契约端点：归一化路径 → 方法集合（省略 method 时默认 GET）
   * @param unimplementedPrefixes 未实现清单（域名前缀；契约有、后端没有时必须落在这里）
   * @param knownExceptions 已知例外（精确路径，双向都不比对）
   */
  private static void assertNoDrift(
      Map<String, Set<String>> backendEndpoints,
      Map<String, Set<String>> contractEndpoints,
      Set<String> unimplementedPrefixes,
      Set<String> knownExceptions) {

    Set<String> backend = new TreeSet<>(backendEndpoints.keySet());
    Set<String> contract = new TreeSet<>(contractEndpoints.keySet());

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

    // ── 方法维度（叠加约束）：路径两边都有时，方法集合必须一致 ──
    // ★ 路径对得上、方法对不上，是只对路径的闸门看不见的漂移（listBaseModels 的 GET→POST 真漏过）。
    // ★ 未实现清单只豁免"整条路径后端还没有"；路径已在后端落了却方法不对，必须红。
    // ★ 已知例外双向都不比对 —— 连同方法一起跳过。
    for (String path : backend) {
      if (!contract.contains(path) || knownExceptions.contains(path)) {
        continue;
      }
      Set<String> backendMethods = backendEndpoints.get(path);
      Set<String> contractMethods = contractEndpoints.get(path);
      if (backendMethods.isEmpty()) {
        // 后端任意方法（@RequestMapping 不写 method）⇒ 匹配所有，不与契约逐一对账
        continue;
      }
      Set<String> backendOnlyMethods =
          backendMethods.stream()
              .filter(m -> !contractMethods.contains(m))
              .collect(Collectors.toCollection(TreeSet::new));
      assertThat(backendOnlyMethods)
          .as(
              "同路径 %s 上，后端有、契约没有的 HTTP 方法（规则 1 的方法维度）⇒ "
                  + "必须在 frontend/src/api/*.ts 里登记对应 method。"
                  + "只对路径不对方法时，同路径的方法单边变更会静默漂移",
              path)
          .isEmpty();
      Set<String> contractOnlyMethods =
          contractMethods.stream()
              .filter(m -> !backendMethods.contains(m))
              .collect(Collectors.toCollection(TreeSet::new));
      assertThat(contractOnlyMethods)
          .as(
              "同路径 %s 上，契约有、后端没有的 HTTP 方法（规则 2 的方法维度）⇒ "
                  + "要么把后端方法改对，要么改契约里的 method。"
                  + "只对路径不对方法时，同路径的方法单边变更会静默漂移",
              path)
          .isEmpty();
    }
  }

  /** 路径是否落在某个域名前缀下：{@code /knowledge-bases/{*}/documents} 落在 {@code /knowledge-bases} 下。 */
  private static boolean underPrefix(String normalizedPath, String prefix) {
    return normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/");
  }

  /**
   * 金丝雀样本构造：把 {@code method, path, method, path...} 收成「路径 → 方法集合」。
   *
   * <p>★ 样本里写死方法，是为了让方法维度的金丝雀能精确表达「同路径、不同方法」的坏形状。
   */
  private static Map<String, Set<String>> endpoints(String... methodPathPairs) {
    if (methodPathPairs.length % 2 != 0) {
      throw new IllegalArgumentException("method/path 必须成对出现");
    }
    Map<String, Set<String>> map = new TreeMap<>();
    for (int i = 0; i < methodPathPairs.length; i += 2) {
      String method = methodPathPairs[i].toUpperCase(Locale.ROOT);
      map.computeIfAbsent(methodPathPairs[i + 1], k -> new TreeSet<>()).add(method);
    }
    return map;
  }

  // ────────────────────────── 后端侧 ──────────────────────────

  /**
   * 从 {@link RequestMappingHandlerMapping} 拼出真实映射：路径模板 + HTTP 方法集合。
   *
   * <p>★ 用真实映射而不是扫注解：注解扫描会漏掉组合注解、接口默认方法、以及将来通过 {@code WebMvcRegistrations} 注册的端点 ——
   * 而"前端调的路径"与"DispatcherServlet 真认识的路径"是否一致，恰恰是本测试要回答的问题。
   *
   * <p>★ {@code getMethodsCondition().getMethods()} 为空 ⇒ {@code @RequestMapping} 没写 {@code method}
   * ⇒ 任意方法。原样保留空集，比对时按"匹配所有"处理，不展开成具体方法清单（展开会制造误报）。
   */
  private Map<String, Set<String>> backendEndpoints() {
    RequestMappingHandlerMapping mapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

    Map<String, Set<String>> endpoints = new TreeMap<>();
    mapping
        .getHandlerMethods()
        .keySet()
        .forEach(
            info -> {
              Set<String> methods =
                  info.getMethodsCondition().getMethods().stream()
                      .map(Enum::name)
                      .collect(Collectors.toCollection(TreeSet::new));
              info.getPatternValues()
                  .forEach(pattern -> addBackendPattern(endpoints, pattern, methods));
            });
    return endpoints;
  }

  private static void addBackendPattern(
      Map<String, Set<String>> endpoints, String rawPattern, Set<String> methods) {
    // 只对账业务 API；actuator / error 等不参与（契约里也没有）
    if (!rawPattern.startsWith(API_PREFIX)) {
      return;
    }
    String withoutPrefix = rawPattern.substring(API_PREFIX.length());
    if (withoutPrefix.isEmpty()) {
      withoutPrefix = "/";
    }
    String path = normalize(withoutPrefix);
    endpoints.merge(
        path,
        new TreeSet<>(methods),
        (existing, incoming) -> {
          // 任意方法吸收一切：只要有一条映射不写 method，整条路径就按任意方法处理
          if (existing.isEmpty() || incoming.isEmpty()) {
            return ANY_METHODS;
          }
          Set<String> merged = new TreeSet<>(existing);
          merged.addAll(incoming);
          return merged;
        });
  }

  // ────────────────────────── 契约侧 ──────────────────────────

  /**
   * 解析 {@code frontend/src/api} 下所有 {@code .ts} 的 {@code request({ method, url })} 字面量。
   *
   * <p>★ 只读源码，不引 TS 解析器、不起前端、不跑 Node —— 这道闸门要能在任何构建机上跑。
   *
   * <p>★ method 与 url 从<strong>同一个</strong>选项对象里取出（花括号配对）：跨对象抓 method 会把相邻调用配错对。 method
   * 可小写、可省略（{@code lib/http.ts} 缺省 GET）。
   */
  private static Map<String, Set<String>> contractEndpoints() {
    Path apiDir = resolveFrontendApiDir();
    Map<String, Set<String>> endpoints = new TreeMap<>();
    try (Stream<Path> files = Files.walk(apiDir)) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".ts")).toList()) {
        collectEndpointsFromFile(file, endpoints);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取前端契约目录失败：" + apiDir, e);
    }
    return endpoints;
  }

  private static void collectEndpointsFromFile(Path file, Map<String, Set<String>> endpoints) {
    String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取前端契约文件失败：" + file, e);
    }
    Matcher call = REQUEST_OPTIONS_OPEN.matcher(source);
    while (call.find()) {
      // pattern 以 { 结尾，open 即选项对象的左花括号
      int open = call.end() - 1;
      int close = matchBrace(source, open, file);
      String body = source.substring(open + 1, close);

      Matcher urlMatcher = URL_LITERAL.matcher(body);
      if (!urlMatcher.find()) {
        // request({...}) 里没有 url 字面量（动态拼装等）—— 不是可对账的契约项，跳过
        continue;
      }
      String url = urlMatcher.group(2);
      // 查询参数不参与比对（只比路径）
      int query = url.indexOf('?');
      if (query >= 0) {
        url = url.substring(0, query);
      }
      if (!url.startsWith("/")) {
        throw new AssertionError(
            "契约里的 url 必须以 / 开头（相对路径会随 baseURL 漂移）：" + file + " → " + urlMatcher.group(0));
      }

      // method 可小写、可省略；省略时按 lib/http.ts 的默认 GET（const { method = 'GET', ... }）
      Matcher methodMatcher = METHOD_LITERAL.matcher(body);
      String method =
          methodMatcher.find() ? methodMatcher.group(1).toUpperCase(Locale.ROOT) : "GET";

      // 模板串 url: `/x/${id}` 与 :id 占位一起归一；${...} → {*} 在 normalize 里处理
      String path = normalize(url);
      endpoints.computeIfAbsent(path, k -> new TreeSet<>()).add(method);
    }
  }

  /**
   * 从 {@code open} 处的 {@code '{'} 起配对花括号，返回闭合 {@code '}'} 的下标。
   *
   * <p>★ 必须跳过字符串/模板串：{@code url: `/x/${id}`} 里的 <code>${}</code> 花括号若计入深度， 会把选项对象截断，后面的 method/url
   * 就丢了。
   */
  private static int matchBrace(String source, int open, Path file) {
    int depth = 0;
    char quote = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (quote != 0) {
        if (c == '\\' && i + 1 < source.length()) {
          i++; // 跳过转义字符
        } else if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        quote = c;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    throw new AssertionError("request 选项对象花括号不匹配：" + file);
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
