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
 *   <li><strong>契约有、后端没有</strong> ⇒ 必须<strong>逐条</strong>登记在 {@link #KNOWN_MISSING}（method + path
 *       的欠账台账）里，否则失败。 前端已经跑在后端前面（{@code ROADMAP §3.1}），契约上百个端点、后端目前只落了身份域 —— 不能要求后端立刻把它们都实现；
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
   * 未实现清单：契约有、后端还没有的<strong>逐条</strong>端点（method + path 的欠账台账）。
   *
   * <p>★ 逐条登记（{@link MissingEndpoint}：method + path + issue + week），<strong>不是按域名前缀</strong>。
   * 按前缀登记的旧形状有个致命漏洞：前缀下<strong>新增</strong>一条缺失端点时，它仍落在已有前缀下，闸门<strong>不会红</strong> —— 欠账无声地长大。
   * 逐条登记后，"契约有、后端没有、又不在台账里"的每一条都会被规则 2 当场抓住。
   *
   * <p>★ 每条都要能回答两件事：<strong>欠的是哪个 issue</strong>（{@code issue}）与<strong>计划落在哪一周</strong>（{@code
   * week}）。 这两栏是欠账台账的账目，<strong>不允许留空白</strong> —— 留白的欠账等于没人认领的欠账，最终会变成永久黑洞（那正是这条规则要防的）。
   *
   * <p>★ 理由写在这里，而不是散落在失败信息里：前端已经跑在后端前面（{@code ROADMAP §3.1}）—— 后端还没落的端点不能要求它立刻实现，
   * 但<strong>每一项都要能回答"欠哪个 issue、排在哪一周"</strong>（见 {@code docs/ROADMAP.md §4.7.3}）。
   * 台账一旦失去可审阅性，它就变成永久黑洞 —— 那正是这条规则要防的。
   */
  private static final List<MissingEndpoint> KNOWN_MISSING =
      List.of(
          // ★ assistants（9）—— 第 15 周 0.4.3（§3.1 记 M4）：应用配置只剩编排 9 条（#198）
          //   ★ 收藏两条（POST/DELETE /assistants/{*}/favorite）已随 #195 的收藏子项落地，故从欠账里摘掉
          //   ★ GET /assistants/tags 已随 #195 的标签子项落地（2026-10-24），同样摘掉
          //   ★ PUT /assistants/{*}/grants 与 PATCH /assistants/{*}/visibility 已随 #195 的共享写子项落地
          //   ★ POST /assistants/import 与 /import/check 已随导入子项落地（2026-10-24）
          //   ★ GET/POST /assistants/{*}/api-keys 与 PATCH …/enabled 已随 API Key 子项落地（2026-10-24）
          missing("POST", "/assistants/{*}/workflow/debug", "#198", "15–18 周"),
          missing("GET", "/assistants/{*}/workflow/draft", "#198", "15–18 周"),
          missing("PUT", "/assistants/{*}/workflow/draft", "#198", "15–18 周"),
          missing("POST", "/assistants/{*}/workflow/publish", "#198", "15–18 周"),
          missing("GET", "/assistants/{*}/workflow/records", "#198", "15–18 周"),
          missing("POST", "/assistants/{*}/workflow/validate", "#198", "15–18 周"),
          missing("GET", "/assistants/{*}/workflow/versions", "#198", "15–18 周"),
          missing("GET", "/assistants/{*}/workflow/versions/{*}", "#198", "15–18 周"),
          missing("POST", "/assistants/{*}/workflow/versions/{*}/rollback", "#198", "15–18 周"),
          // ★ conversations（0）—— GET/PATCH/DELETE /conversations/{id} 已随 #187 落地，
          //   会话分享三条也已在 2026-10-25 落地（V53 + 公开面 /public/shares/{token}）
          // ★ feedbacks（6）—— 第 18 周 0.5.0：赞踩 + 反馈闭环（§3.1 记 M5，反馈处理后端 M5 实现）
          // ★ feedbacks（6）—— #194 已落：列表/统计/详情/办理/批量/重开
          // ★ files（1）—— 第 17 周随问答链路：附件上传（multipart）
          missing("POST", "/files", "#187", "17 周"),
          // ★ knowledge-bases（1）—— 建档/文档已落地；余下暂存上传一条，属 P1
          // ★ workflow（1）—— 第 15 周 0.4.3 / M5.5：编排画布接契约的节点类型查询
          missing("GET", "/workflow/node-types", "#198", "15–16 周"));

  /**
   * 未实现清单的一条：欠账台账的<strong>逐条</strong>登记项。
   *
   * <p>★ 四栏都要有值：{@code method} + {@code path} 定位端点，{@code issue} 记欠账挂的 issue，{@code week} 记计划周次。
   * 后两栏是欠账的账目，<strong>不允许留空白</strong>。
   *
   * @param method HTTP 方法（统一大写）
   * @param path 归一化后的契约路径（必须以 {@code /} 开头，占位符统一为 {@code {*}})
   * @param issue 欠账挂的 issue（如 {@code #160}）
   * @param week 计划落在哪一周（如 {@code "17 周"} / {@code "P1（未排周次）"}）
   */
  private record MissingEndpoint(String method, String path, String issue, String week) {
    /** 紧凑构造器：四栏非空、path 以 {@code /} 开头、method 统一大写。 */
    MissingEndpoint {
      if (method == null || method.isBlank()) {
        throw new IllegalArgumentException("MissingEndpoint.method 不允许为空");
      }
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("MissingEndpoint.path 不允许为空");
      }
      if (issue == null || issue.isBlank()) {
        throw new IllegalArgumentException("MissingEndpoint.issue 不允许为空（欠账台账必须写清挂哪个 issue）");
      }
      if (week == null || week.isBlank()) {
        throw new IllegalArgumentException("MissingEndpoint.week 不允许为空（欠账台账必须写清计划周次）");
      }
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("MissingEndpoint.path 必须以 / 开头：" + path);
      }
      method = method.toUpperCase(Locale.ROOT);
    }
  }

  /** 登记一条未实现端点（{@link MissingEndpoint} 的工厂方法）。 */
  private static MissingEndpoint missing(String method, String path, String issue, String week) {
    return new MissingEndpoint(method, path, issue, week);
  }

  /**
   * 已知例外：双向都不比对的<strong>精确路径</strong>，每项必须写清理由。
   *
   * <p>★ 它不是"放过一切"的垃圾桶：只放"形态特殊 / 已被裁决不实现"的少数路径， 且每项都要能被审阅者独立验证。
   *
   * <p>★★ 每一条都必须写清<strong>三件事</strong>：为什么它是例外 · 它的真源在哪 · ★ 什么条件下这一条必须被删掉。 第三件是防"例外变黑洞"的那一件 —— 由
   * {@link #例外清单不得变成黑洞()} 强制（见它的说明）。
   */
  private static final Set<String> KNOWN_EXCEPTIONS =
      Set.of(
          // ★ 后端有、api/*.ts 没有 —— 形态特殊：它被 lib/http.ts 的 401 拦截器直接调用
          //   （不走 request() 封装），所以不会出现在 api/*.ts 的 url: 字面量里。
          //   ROADMAP §3.4 裁决「补进契约文件」，在补登记之前先在这里挂账，避免规则 1 误伤。
          //   真源 = frontend/src/lib/http.ts:60 的 `${API_PREFIX}/auth/refresh`。
          //   删除条件：该端点被写进 api/*.ts 的 request({url}) 字面量之后（那时双向对账自己就能管住它）。
          "/auth/refresh",
          // ★ 问答 SSE 端点（第 17 周 · 0.4.5）—— 后端有、api/** 没有，而它<strong>只能</strong>这样。
          //
          //   ① 为什么是例外：SSE 走不了 api/** 那条链。契约里的 request() 是 axios 封装
          //      （lib/http.ts：拦截器 + 统一包络解包 + JSON 解析），而流式响应要的是"逐帧增量"——
          //      axios 会把响应体整段缓冲完再交给调用方，于是"边生成边到"（逐字效果、首字延迟 B4）
          //      全都消失，且不会报错。所以前端这一条走 fetch() 流的独立通道。
          //
          //   ② 真源在哪：frontend/src/lib/sse.ts:166 的 `fetch(`${API_PREFIX}/conversations/chat`,
          // {method:'POST'})`；
          //      它的请求体（ChatStreamOptions）与事件形状（六种事件 + 心跳注释帧）在
          //      frontend/src/api/stream-types.ts —— ★ 形状那一半<strong>在契约里</strong>，只有 url/method
          // 字面量不在。
          //      后端侧的真源 = conversation/web/ChatController 的 @PostMapping("/chat")（类级
          // /api/v1/conversations）。
          //
          //   ③ ★ 触发条件（防黑洞）：一旦有人把 /conversations/chat 也写进 api/** 的 request({...}) 字面量，
          //      <strong>这一条必须从这里删掉</strong> —— 那时它两侧都有，主闸门的双向对账（含方法维度）本来就能管住它；
          //      而留着它等于给这条路径开一个"永远不必再对账"的后门（例外吸收掉规则 1 的漂移）。
          //      ★ 这一条由 例外清单不得变成黑洞() 强制，不靠人记得。
          "/conversations/chat",
          // ★ mock 专用端点（ROADMAP §3.4 已裁决后端**不实现**：它们是"故意违法样例图"的载体，
          //   只服务于校验演示）。目前只在 mock/handlers/workflow.ts 里，不在 api/*.ts 契约中；
          //   若将来误入契约，靠此例外明确"后端永不实现"，而不是被未实现清单吸收成永久欠账。
          //   删除条件：它们被写进 api/*.ts 之后（那时"后端不实现"由**逐条登记的 KNOWN_MISSING**
          //   表达，而"双向都不比对"这一条就只剩噪音了）。
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

    assertNoDrift(backend, contract, KNOWN_MISSING, KNOWN_EXCEPTIONS);
  }

  /**
   * ★ 未实现台账不得变成黑洞：基线里的每一条端点必须<strong>仍然</strong>是欠账。
   *
   * <p>否则台账会慢慢堆积"曾经欠过、现在早已实现"的项，审阅者再也分不清哪些是真欠账。
   *
   * <p>★ 逐条登记后，防黑洞由 {@link #assertNoDrift} 的反黑洞两条承担（基线端点已实现 / 已不在契约都要红）， 本测试只需把真实数据跑一遍同一套判据。
   */
  @Test
  void 未实现台账里的端点必须仍然有欠账() {
    Map<String, Set<String>> backend = backendEndpoints();
    Map<String, Set<String>> contract = contractEndpoints();

    // 真实数据下跑一遍 assertNoDrift：反黑洞两条会把"台账里的端点已实现 / 已不在契约"判红
    assertNoDrift(backend, contract, KNOWN_MISSING, KNOWN_EXCEPTIONS);
  }

  /**
   * ★ 已知例外清单不得变成黑洞：某条例外一旦<strong>进了契约</strong>，它就不再是例外，必须从清单里删掉。
   *
   * <h3>★★ 为什么必须有这一条（它与 {@link #未实现台账里的端点必须仍然有欠账()} 是同一条纪律）</h3>
   *
   * <p>例外清单的语义是"这条路径<strong>双向都不比对</strong>"。于是它天然有一个危险方向： 一旦某条路径两侧都有了（例如有人把 SSE 端点补登记进了 {@code
   * api/**}）， <strong>主闸门永远不会再检查它</strong> —— 后端后来把方法从 {@code POST} 改成 {@code GET}、
   * 或把路径挪走，全都不会红。例外就成了一个"这条路径不必再对账"的后门。
   *
   * <p>★ 与未实现清单的"防黑洞"是同一件事的两种形态：那边防"台账里留着早已实现的"， 这边防"例外里留着早已进契约的"。两者都在维护"清单还能不能被审阅者信任"。
   */
  @Test
  void 例外清单不得变成黑洞() {
    assertExceptionsAreStillStale(contractEndpoints(), KNOWN_EXCEPTIONS);
  }

  // ────────────────────────── 金丝雀 ──────────────────────────

  /**
   * 金丝雀（规则 2）：契约里有、后端没有、又不在未实现台账里的路径，必须被抓住。
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
                    // 契约：多出一条后端没有的 /ghost —— 且未实现台账/例外都是空的
                    endpoints("GET", "/users", "GET", "/users/{*}", "GET", "/ghost"),
                    List.of(),
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
                    List.of(),
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
                    List.of(),
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
                    List.of(),
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
                    List.of(),
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
        List.of(),
        Set.of());
  }

  /**
   * 金丝雀（例外清单防黑洞）：某条路径既在例外清单里、又出现在契约里 ⇒ 必须被抓到。
   *
   * <p>★ 没有这一条，{@link #例外清单不得变成黑洞()} 就是一条"永远为真"的规则：真实契约当前确实不含那几条例外路径，
   * 所以它绿得毫无信息量。这里的坏样本把"例外进了契约"这个形状<strong>造出来</strong>， 于是"规则写反了 / 集合取错了 / 断言方向颠倒了"都会让金丝雀先红。
   */
  @Test
  void 金丝雀_例外清单里的端点进了契约必须被抓到() {
    assertThatThrownBy(
            () ->
                assertExceptionsAreStillStale(
                    // 契约里真的出现了 /conversations/chat（模拟"有人把它补登记进 api/**"）
                    endpoints("POST", "/conversations/chat", "POST", "/auth/refresh"),
                    Set.of("/conversations/chat")))
        .as("坏样本没被拦下 ⇒ 例外清单的防黑洞规则已经失效，主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 金丝雀（反黑洞 1）：基线里的端点<strong>已经实现</strong>、却还留在 {@link #KNOWN_MISSING} 里 ⇒ 必须被抓到。
   *
   * <p>★ 这条防的是"台账里留着早已实现的"：留着它就成了"曾经欠过、现在早已实现"的噪音， 久而久之台账没人再看。
   */
  @Test
  void 金丝雀_基线端点已实现却没删必须被抓到() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 后端已实现 GET /users
                    endpoints("GET", "/users"),
                    // 契约也有 GET /users
                    endpoints("GET", "/users"),
                    // 台账里却还留着它
                    List.of(missing("GET", "/users", "#0", "占位")),
                    Set.of()))
        .as("坏样本没被拦下 ⇒ 反黑洞 1 已经失效，台账里的旧欠账不会被清理")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 金丝雀（反黑洞 2）：基线里的端点<strong>已不在契约</strong>、却还留在 {@link #KNOWN_MISSING} 里 ⇒ 必须被抓到。
   *
   * <p>★ 这条防的是"台账里留着契约已经不要的"：契约撤了这条端点，台账还挂着账，同样是黑洞。
   */
  @Test
  void 金丝雀_基线端点已不在契约必须被抓到() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    // 后端只有 GET /users
                    endpoints("GET", "/users"),
                    // 契约也只有 GET /users —— 没有 /ghost
                    endpoints("GET", "/users"),
                    // 台账里却挂着 /ghost
                    List.of(missing("GET", "/ghost", "#0", "占位")),
                    Set.of()))
        .as("坏样本没被拦下 ⇒ 反黑洞 2 已经失效，台账里的失效欠账不会被清理")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 反金丝雀（基线可豁免单方法缺失）：同路径上契约多一个方法、且该方法在 {@link #KNOWN_MISSING} 里 ⇒ 必须通过。
   *
   * <p>★ 基线允许"单方法缺失"：路径两边都有、方法集合不一致时，落在台账里的那个方法不算漂移。 这是逐条登记（method + path）相对按前缀登记的额外能力 ——
   * 按前缀时无法表达"这条路径的 GET 缺、POST 在"。
   */
  @Test
  void 金丝雀_基线可豁免单方法缺失() {
    // 不抛异常即通过：后端只有 POST /users，契约 GET+POST /users，台账里登记了 GET /users
    assertNoDrift(
        endpoints("POST", "/users"),
        endpoints("GET", "/users", "POST", "/users"),
        List.of(missing("GET", "/users", "#0", "占位")),
        Set.of());
  }

  /**
   * 金丝雀（从台账删掉一条仍缺失的端点）：有登记时绿，删掉登记立即红。
   *
   * <p>★ 这是 issue #188 判据 2 的直译：删掉一条基线、而它仍然 404 ⇒ 测试立刻红。 没有这一条，"逐条登记"可能被读成"只要 KNOWN_MISSING 非空就行"。
   */
  @Test
  void 金丝雀_基线删掉仍缺失必须被抓到() {
    // 先证明有登记时这条缺失是合法的
    assertNoDrift(
        endpoints("GET", "/users"),
        endpoints("GET", "/users", "GET", "/ghost"),
        List.of(missing("GET", "/ghost", "#0", "占位")),
        Set.of());

    // 再把登记删掉：同一条 /ghost 仍缺失，必须红
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    endpoints("GET", "/users"),
                    endpoints("GET", "/users", "GET", "/ghost"),
                    List.of(),
                    Set.of()))
        .as("删掉基线后仍缺失的端点没被拦下 ⇒ 规则 2 已经失效")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 反证（真实台账）：从真实 {@link #KNOWN_MISSING} 删掉任意一条、而它仍然缺失 ⇒ 主闸门必须红。
   *
   * <p>★ 与上一条假样本互补：上一条证明规则 2 本身活着，这一条证明<strong>当前这份真实台账</strong>确实在承担拦截职责， 不是一份"删了也不会有人发现"的装饰。
   */
  @Test
  void 删掉真实基线的一条仍缺失必须红() {
    MissingEndpoint victim = KNOWN_MISSING.getFirst();
    List<MissingEndpoint> withoutVictim =
        KNOWN_MISSING.stream().filter(entry -> !entry.equals(victim)).toList();

    assertThatThrownBy(
            () ->
                assertNoDrift(
                    backendEndpoints(), contractEndpoints(), withoutVictim, KNOWN_EXCEPTIONS))
        .as("从真实台账删掉 %s 后，闸门仍然绿 ⇒ 这份台账没有被规则 2 消费", victim)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(victim.method() + " " + victim.path());
  }

  /**
   * 金丝雀（台账去重）：{@link #KNOWN_MISSING} 里出现两条相同的 method + path ⇒ 必须失败。
   *
   * <p>★ 重复的基线项本身就是台账噪音 —— 同一条欠账登记两次，审阅者会以为是两笔账。 由 {@link #assertNoDrift} 的重复 key 检查直接抛 {@link
   * IllegalArgumentException}。
   */
  @Test
  void 金丝雀_重复基线必须失败() {
    assertThatThrownBy(
            () ->
                assertNoDrift(
                    endpoints("GET", "/users"),
                    endpoints("GET", "/users"),
                    // 两条相同的 GET /users
                    List.of(
                        missing("GET", "/users", "#0", "占位"), missing("GET", "/users", "#1", "占位")),
                    Set.of()))
        .as("坏样本没被拦下 ⇒ 台账的重复 key 检查已经失效")
        .isInstanceOf(RuntimeException.class);
  }

  // ────────────────────────── 规则本体 ──────────────────────────

  /**
   * ★ 例外清单的"防黑洞"规则：同一规则跑两组数据 —— 真实契约必须过、坏样本必须不过。
   *
   * <p>★ 与 {@link #assertNoDrift} 同构（本项目所有闸门的写法）：判据必须能被<strong>一次故意的坏样本</strong>证明它还活着，
   * 否则"主代码是绿的"说明不了任何事。
   *
   * @param contractEndpoints 契约端点（归一化路径 → 方法集合）
   * @param knownExceptions 已知例外（精确路径）
   */
  private static void assertExceptionsAreStillStale(
      Map<String, Set<String>> contractEndpoints, Set<String> knownExceptions) {
    for (String path : knownExceptions) {
      assertThat(contractEndpoints)
          .as(
              "已知例外 %s 已经出现在契约（frontend/src/api/**）里 ⇒ 它不再是例外："
                  + "请把它从 KNOWN_EXCEPTIONS 删掉 —— 两侧都有时主闸门的双向对账（含方法维度）本来就能管住它，"
                  + "而留着它等于给这条路径开一个'永远不必再对账'的后门（例外吸收掉规则 1 的漂移）",
              path)
          .doesNotContainKey(path);
    }
  }

  /**
   * 双向对账规则。同一规则跑两组数据：真实契约必须过、金丝雀样本必须不过。
   *
   * <p>路径维度沿用既有两条规则；方法维度是叠加约束（路径两边都有时，方法集合必须一致）。
   *
   * @param backendEndpoints 后端端点：归一化路径 → 方法集合（空集 = 任意方法）
   * @param contractEndpoints 契约端点：归一化路径 → 方法集合（省略 method 时默认 GET）
   * @param knownMissing 未实现台账（逐条 method + path；契约有、后端没有时必须逐条落在这里）
   * @param knownExceptions 已知例外（精确路径，双向都不比对）
   */
  private static void assertNoDrift(
      Map<String, Set<String>> backendEndpoints,
      Map<String, Set<String>> contractEndpoints,
      List<MissingEndpoint> knownMissing,
      Set<String> knownExceptions) {

    // 台账索引：key = method + " " + path；重复 key 直接抛（重复的基线项是台账噪音）
    Map<String, MissingEndpoint> missingByKey = new TreeMap<>();
    for (MissingEndpoint entry : knownMissing) {
      String key = entry.method() + " " + entry.path();
      if (missingByKey.put(key, entry) != null) {
        throw new IllegalArgumentException("KNOWN_MISSING 出现重复条目：" + key);
      }
    }

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

    // ── 规则 2：契约有、后端没有 ⇒ 必须逐条登记在 KNOWN_MISSING 里 ──
    // ★ 逐条判（method + path），不是按前缀：前缀下新增一条缺失端点不会红，逐条登记才会。
    Set<String> unregistered = new TreeSet<>();
    for (Map.Entry<String, Set<String>> contractEntry : contractEndpoints.entrySet()) {
      String path = contractEntry.getKey();
      for (String method : contractEntry.getValue()) {
        // 后端覆盖该 method（后端路径存在且方法集为空 = ANY，或方法集含该 method）⇒ 不缺，跳过
        Set<String> backendMethods = backendEndpoints.get(path);
        if (backendMethods != null
            && (backendMethods.isEmpty() || backendMethods.contains(method))) {
          continue;
        }
        // 已知例外（双向跳过）⇒ 跳过
        if (knownExceptions.contains(path)) {
          continue;
        }
        // 台账里有该 method + path ⇒ 豁免，跳过
        if (missingByKey.containsKey(method + " " + path)) {
          continue;
        }
        unregistered.add(method + " " + path);
      }
    }
    assertThat(unregistered)
        .as(
            "契约有、后端没有、又不在 KNOWN_MISSING 里的端点（规则 2）⇒ 要么实现它，"
                + "要么把它的 method + path 逐条加进 KNOWN_MISSING 并注明 issue 与周次。"
                + "台账必须显式可审阅，否则会变成永久黑洞")
        .isEmpty();

    // ── 反黑洞 1：台账里的端点，后端已覆盖 ⇒ 基线该删 ──
    Set<String> alreadyImplemented = new TreeSet<>();
    for (MissingEndpoint entry : knownMissing) {
      Set<String> backendMethods = backendEndpoints.get(entry.path());
      if (backendMethods != null
          && (backendMethods.isEmpty() || backendMethods.contains(entry.method()))) {
        alreadyImplemented.add(entry.method() + " " + entry.path());
      }
    }
    assertThat(alreadyImplemented)
        .as("基线端点已实现，请从 KNOWN_MISSING 删除 —— 留着它就成了'曾经欠过、现在早已实现'的噪音，" + "台账会慢慢没人再看")
        .isEmpty();

    // ── 反黑洞 2：台账里的端点，契约已没有该 path/method ⇒ 基线该删 ──
    Set<String> goneFromContract = new TreeSet<>();
    for (MissingEndpoint entry : knownMissing) {
      Set<String> contractMethods = contractEndpoints.get(entry.path());
      if (contractMethods == null || !contractMethods.contains(entry.method())) {
        goneFromContract.add(entry.method() + " " + entry.path());
      }
    }
    assertThat(goneFromContract)
        .as("基线端点已不在契约，请从 KNOWN_MISSING 删除 —— 契约撤了这条端点，台账还挂着账，同样是黑洞")
        .isEmpty();

    // ── 方法维度（叠加约束）：路径两边都有时，方法集合必须一致 ──
    // ★ 路径对得上、方法对不上，是只对路径的闸门看不见的漂移（listBaseModels 的 GET→POST 真漏过）。
    // ★ 台账只豁免"该 method + path 没实现"这一条；路径已在后端落了却方法不对，必须红。
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
      // ★ 契约多的方法：排除"该 method + path 在 KNOWN_MISSING 里"的项（基线允许单方法缺失），其余仍失败。
      Set<String> contractOnlyMethods =
          contractMethods.stream()
              .filter(m -> !backendMethods.contains(m))
              .filter(m -> !missingByKey.containsKey(m + " " + path))
              .collect(Collectors.toCollection(TreeSet::new));
      assertThat(contractOnlyMethods)
          .as(
              "同路径 %s 上，契约有、后端没有的 HTTP 方法（规则 2 的方法维度）⇒ "
                  + "要么把后端方法改对，要么改契约里的 method，"
                  + "要么把该 method + path 逐条加进 KNOWN_MISSING（基线允许单方法缺失）。"
                  + "只对路径不对方法时，同路径的方法单边变更会静默漂移",
              path)
          .isEmpty();
    }
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
    // ★★ 带**量词**的路径约束：{id:[0-9a-fA-F]{8}-…} → {*}
    //   必须排在下面那条通用归一**之前**：通用那条是 `\\{[^}]+\\}`，一遇到 `}` 就停，
    //   而约束里的 `{8}` / `{4}` 正是 `}` ⇒ 它会只吃掉半截，剩下的原样留在路径里。
    //   ★ 实测（加 UUID 形状约束之后）：`Expecting empty but was:
    //   ["/assistants/{*}-[0-9a-fA-F]{*}-[0-9a-fA-F]{*}-…}"]` —— 规则 1 因此误报"后端有、契约没有"。
    //   ★ 允许**一层**嵌套花括号就够：量词都在同一层。
    //   ★ 守门人 = 本文件之外那条真实约束（`AssistantController` 的 `{id:…}`）；
    //     去掉这条归一，它立刻红成上面那行逐字。
    p = p.replaceAll("\\{[A-Za-z][A-Za-z0-9]*:[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", "{*}");
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
