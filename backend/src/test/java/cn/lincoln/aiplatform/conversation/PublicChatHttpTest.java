package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.assistant.application.AccessCredentialService;
import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 第 17 周「公开面 {@code /public/**} 接管」的<strong>真 HTTP + 真库 + 假模型</strong>读数。
 *
 * <h3>★★ 为什么必须是真 HTTP + 真库（而不是 MockMvc / 领域层）</h3>
 *
 * <ul>
 *   <li><strong>免登录必须有真过滤器链</strong>：本类的每一条请求<strong>都不带 {@code Authorization}</strong>
 *       （除了"登录用户不放大"那一条故意带上）。"匿名能走通"这件事只有真容器 + 真 {@code SecurityFilterChain} 才回答得了 —— MockMvc
 *       不过滤器链，一个"忘了放行 {@code /api/v1/public/**}"的实现在那里照样绿；
 *   <li><strong>四态码要真落库</strong>：认不出 / 已撤销 / 已过期 / 已停用四种状态由 {@code as_access_credential} 的真实行决定（撤销
 *       / 过期是通过 SQL 造出来的），而不是靠假替身；
 *   <li><strong>撤销 ⇒ 同一请求周期内失效</strong>要真缓存：{@code PublicCredentialResolver} 按 token 缓存解析结果，
 *       而"第一次访问真的写进缓存"这件事只有真栈能确认（假替身根本没有那个缓存）；
 *   <li><strong>停用账号 ⇒ 不出内容</strong>要真 {@code iam_user.status} + 真 {@code SubjectIdentityPort} → 真
 *       {@code AccessScopeAdapter.asSubject} → 真检索谓词。这条判据横跨四个模块，任何一段用假的都读不到。
 * </ul>
 *
 * <h3>★★ 公开面的两个端点各一条"匿名走通"</h3>
 *
 * <table>
 *   <tr><th>端点</th><th>用例</th><th>读什么</th></tr>
 *   <tr><td>{@code GET /api/v1/public/{token}}</td><td>{@link #anonymousVisitorResolvesTheContextAndTheFrozenClearance()}</td>
 *       <td>上下文六个字段 + <strong>必填</strong>的 {@code maxClassification} = 凭证冻结值</td></tr>
 *   <tr><td>{@code POST /api/v1/public/{token}/messages}</td><td>{@link #anonymousVisitorStreamsMetaRefsDeltasDone()}</td>
 *       <td>{@code meta → refs → delta* → done}，且引用里只有冻结密级之内的内容</td></tr>
 * </table>
 *
 * <h3>★ 夹具：迁移种子账号 + 裸 SQL 建库</h3>
 *
 * <p>归属人用种子账号 {@code zhangyun}（{@code KB_OPERATOR}：ALL / SECRET / 技术部），调用者用 {@code chenjun} （{@code
 * USER}：SHARED / PUBLIC / 市场部）—— 两者都由迁移 {@code V4} / {@code V9} / {@code V28} 给定，
 * 于是"这条链在真实迁移数据上跑得通"本身是一条读数。知识侧走裸 SQL，理由与 {@code AccessCredentialFilteringTest}
 * 相同：本类要验的是<strong>可见性</strong>，不是建档链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      // 与其余真栈类同一条理由：本类验的是公开面链路，登录只是"拿一个低密级调用者"的手段
      "app.security.captcha.enabled=false",
      // 时间压缩（契约默认 20s / 60s 由 ChatStreamTimingTest 单独钉住，这里要的是"该发的时候真的发了"）
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=1200",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class PublicChatHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** {@code V4} 的种子账号：归属人（KB_OPERATOR / ALL / SECRET，技术部）。 */
  private static final UUID ZHANGYUN = UUID.fromString("10000000-0000-0000-0000-000000000002");

  private static final String ZHANGYUN_PASSWORD = "123456";

  /** {@code V4} 的种子账号：低密级调用者（USER / SHARED / PUBLIC，市场部）。 */
  private static final String CHENJUN = "chenjun";

  private static final String CHENJUN_PASSWORD = "123456";

  /** {@code V25} 种子写死的 ROOT 部门（{@code kb_document.org_id} 是外键，见 Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  /** 开流填充注释帧的载荷前缀 —— 与 {@code ChatStreamAssembler} 里那个常量同源（填充帧不计入业务事件）。 */
  private static final String OPEN_PADDING_MARKER = "open: ";

  /**
   * ★★ 免登录问答流的真实 URL（第 17 周 · Lead 裁决 ①）—— 逐字照前端 {@code lib/sse.ts}： {@code
   * fetch(`${API_PREFIX}/conversations/chat`)}，凭据走 {@code X-Access-Credential} 头。
   *
   * <p>★ 它<strong>不是</strong> {@code /public/{token}/messages}：那条是本批一度自造的路径， 前端永不调用 ——
   * 真源在客户端那一侧（{@code lib/sse.ts:161} 与公开页的 {@code credentialToken}）。
   */
  private static final String CHAT_PATH = "/api/v1/conversations/chat";

  /**
   * 四个分段的<strong>命中词</strong>与密级。
   *
   * <p>★★ 四条正文<strong>都含同一个查询词</strong>（{@code 合同违约金}），只有密级不同 —— 这是刻意的夹具设计：
   * 于是"这一轮拿到了几条引用"<strong>只</strong>由密级过滤决定，不会被分词器的运气干扰。 ★ 为什么不用"差旅 / 预算"这类词：{@code chinese_zh}
   * 会把相邻的字合成更长的词元 （实测："差旅与预算口径"整串分词后 <strong>一条都对不上</strong>含"差旅""预算"的正文），而那种空结果看起来像"密级过滤把内容滤光了"——
   * 两条完全不同的原因会长得一模一样。 用现成的 {@code FullTextSearchTest} 验证过的词元（{@code 合同} / {@code 违约金}）就不会撞上这个坑。
   */
  private static final String QUERY_TERM = "合同违约金";

  private static final String PUBLIC_TOPIC = "违约金按日计收，公开档标准";

  private static final String INTERNAL_TOPIC = "违约金按季结算，内部档口径";

  private static final String SECRET_TOPIC = "违约金涉及秘密并购条款";

  private static final String CONFIDENTIAL_TOPIC = "违约金见机密董事会决议";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /** 每个用例自己装一个假模型（见 {@link FakeChatStreamPorts}）。 */
  @Autowired AtomicReference<ChatStreamPort> fakeModel;

  /** 撤销那一条用例要**走真实撤销路径**（服务层 → {@code forget}），而不是直接改库。 */
  @Autowired AccessCredentialService credentialService;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private Fixture fixture;

  /** 本类夹具的名字前缀 —— ★ 真库容器是<strong>跨测试类共享</strong>的，用固定名字会与别的类撞车。 */
  private static final String FIXTURE_PREFIX =
      "公开面夹具-" + UUID.randomUUID().toString().substring(0, 8);

  /**
   * ★ 不装任何认证上下文 —— 本类的主体是"免登录访客"。
   *
   * <p>{@code SecurityContextHolder} 是 {@code ThreadLocal}，但本类走的是<strong>真 HTTP</strong>， 服务端线程由容器给
   * ⇒ 这里清一次是为了让"测试进程里也没有身份"这件事显式（与 {@code PublicSubjectDelegationTest} 同一条纪律）。
   */
  @BeforeEach
  void anonymousByDefault() {
    fakeModel.set(FakeChatStreamPorts.streaming(0, 0, 12));
    fixture = seedFixture();
  }

  @AfterEach
  void cleanUp() {
    fakeModel.set(null);
    if (fixture != null) {
      // ★ 顺序必须"从被引用者到引用者"：
      //   ① 凭证先删 —— 否则 PublicCredentialResolver 里按 token 的解析缓存会留着一条**指向已删应用**的项，
      //      而下一条用例复用它 ⇒ 404「应用不存在」（夹具明明是好的）。这正是本类实测踩到的那个坑。
      //   ② 应用再删 —— on delete cascade 会带走 as_assistant_kb（kb_knowledge_base 的引用者）
      jdbc.update("delete from as_access_credential where id = ?", fixture.credentialId());
      jdbc.update("delete from as_assistant where id = ?", fixture.appId());
      jdbc.update("delete from ai_model where name like ?", FIXTURE_PREFIX + "%");
      //   ③ 最后删知识侧（kb_segment → kb_document → kb_knowledge_base），按**本类的名字前缀**
      jdbc.update(
          "delete from kb_segment where document_id in"
              + " (select id from kb_document where name like ?)",
          FIXTURE_PREFIX + "%");
      jdbc.update(
          "delete from kb_document where kb_id in (select id from kb_knowledge_base where name like"
              + " ?)",
          FIXTURE_PREFIX + "%");
      jdbc.update("delete from kb_knowledge_base where name like ?", FIXTURE_PREFIX + "%");
      fixture = null;
    }
    jdbc.update(
        "delete from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?)",
        ZHANGYUN);
    jdbc.update("delete from cv_conversation where user_id = ?", ZHANGYUN);
    // ★ 停用账号 / 停用凭证都是用例改过的行 —— 复原，否则下一条用例会读到"上一轮的状态"
    jdbc.update("update iam_user set status = 'ACTIVE' where id = ?", ZHANGYUN);
  }

  // ────────────────────────── ① 匿名走通：GET 上下文 ──────────────────────────

  /**
   * ★★ 匿名（<strong>不带 {@code Authorization}</strong>）+ 有效凭证 ⇒ 拿到应用上下文， 且 {@code maxClassification}
   * <strong>必返</strong>并与凭证冻结值一致（判据 1 + 判据 6）。
   *
   * <p>★ 这条用例同时是"安全配置放行的是哪一条路径"的读数：它<strong>不带</strong>任何令牌却得到 200，
   * 而同一个类里的四态用例也<strong>不带</strong>令牌却得到包络错误 —— 两者一起说明放行发生在 {@code /api/v1/public/**}
   * 这一层，而不是"这个应用恰好不需要权限"。
   */
  @Test
  void anonymousVisitorResolvesTheContextAndTheFrozenClearance() throws Exception {
    HttpResponse<String> response = get("/api/v1/public/" + fixture.token(), null);

    assertThat(response.statusCode())
        .as("★★ 判据①：不带 Authorization 的公开上下文请求必须成功（放行规则 = /api/v1/public/**）")
        .isEqualTo(200);
    assertThat(response.body()).contains("\"code\":0");
    // ★ 契约 PublicContext 的六个字段 + 必填的 maxClassification，逐字断言（多一个少一个前端都不报错）
    assertThat(response.body()).contains("\"appId\":\"" + fixture.appId() + "\"");
    assertThat(response.body()).contains("\"appName\":\"" + FIXTURE_PREFIX + "应用\"");
    assertThat(response.body()).contains("\"icon\":\"🤖\"");
    assertThat(response.body()).contains("\"description\":\"公开面夹具应用描述\"");
    assertThat(response.body()).contains("\"prologue\":\"你好，我能回答制度类问题\"");
    assertThat(response.body()).contains("\"quickQuestions\":[\"差旅标准是多少\"]");
    assertThat(response.body())
        .as("★★ 判据⑥：maxClassification 必返，且与凭证冻结值同源（13 §5.2 第①级「创建时冻结」）")
        .contains("\"maxClassification\":\"INTERNAL\"");
  }

  // ────────────────────────── ② 匿名走通：SSE ──────────────────────────

  /**
   * ★★ 匿名 + 有效凭证 ⇒ 走通 {@code meta → refs → delta*} → {@code done}（判据 1）。
   *
   * <p>★★ 这条用例同时读出<strong>判据 6 的另一半</strong>：引用里只有{@strong 冻结密级 {@code INTERNAL} 之内}的内容 —— 库里另有
   * SECRET 与 CONFIDENTIAL 两条（都命中同一个查询词）， 它们<strong>不</strong>出现在 refs 里，也不出现在提示词带出的正文里。
   * 冻结值没有生效的症状是"链接读到了它创建时不该读的东西"，而它不会报错。
   */
  @Test
  void anonymousVisitorStreamsMetaRefsDeltasDone() throws Exception {
    Response response = postMessagesStreaming(fixture.token(), body(question()), null);

    assertThat(response.status()).as("SSE 端点的响应码必须是 200").isEqualTo(200);
    assertThat(response.contentType())
        .as("★ 契约要求 text/event-stream —— 不是它，前端的流式解析器根本不会启动")
        .startsWith("text/event-stream");

    List<String> events = response.events();
    assertThat(events)
        .as("★★ 判据①：匿名访客的事件序列逐字照契约（meta 最前、refs 在 delta 之前、done 恰好一条）")
        .startsWith("meta", "refs")
        .endsWith("done")
        .contains("delta");
    assertThat(events.indexOf("refs"))
        .as("★ refs 必须先于第一个 delta（检索先于生成，前端才能边看答案边点引用）")
        .isLessThan(events.indexOf("delta"));
    assertThat(events.stream().filter("done"::equals).count()).as("★ 恰好一次 done").isEqualTo(1);
    assertThat(response.dataOf("done")).contains("\"status\":\"GENERATED\"");

    String refs = response.dataOf("refs");
    assertThat(refs)
        .as("★★ 判据⑥：冻结 INTERNAL ⇒ 公开与内部两条可见（探针正文在 refs 里）")
        .contains(PUBLIC_TOPIC)
        .contains(INTERNAL_TOPIC);
    assertThat(response.raw())
        .as("★★ 判据⑥ 的反面：SECRET / CONFIDENTIAL 两条分段不得出现在这一轮的任何一帧里")
        .doesNotContain(SECRET_TOPIC)
        .doesNotContain(CONFIDENTIAL_TOPIC);

    String messageId = stringField(response.dataOf("meta"), "messageId");
    assertThat(statusInDb(messageId))
        .as("★ 公开面与登录面共用同一套落库（ChatTurnWriter）：终态真的写进 cv_message")
        .isEqualTo("GENERATED");
    assertThat(ownerOfConversationOf(messageId))
        .as("★ 公开面没有「当前用户」这一维：会话归属人 = 链接的归属人")
        .isEqualTo(ZHANGYUN);
  }

  // ────────────────────────── ③ 四态：码与可读原因 ──────────────────────────

  /** ★★ 判据②：认不出的 token ⇒ {@code 40401}，且文案说清"链接本身不对、重试无意义"。 */
  @Test
  void unknownTokenIsRejectedWith40401() throws Exception {
    HttpResponse<String> response = get("/api/v1/public/pk_live_totally-unknown-token-value", null);

    assertThat(response.statusCode()).as("公开面四态一律 404（HTTP 状态与业务码分开，见控制器注释）").isEqualTo(404);
    assertThat(response.body()).as("★★ 判据②：第①态 = 认不出（40401）").contains("\"code\":40401");
    assertThat(response.body())
        .as("★ 文案要对得上「认不出」：说 '已撤销' 会让访客去等一个永远不会恢复的东西（四态静默退化的界面版）")
        .contains("访问凭证不存在")
        .doesNotContain("已撤销")
        .doesNotContain("已过期")
        .doesNotContain("已停用");
  }

  /** ★★ 判据②：已撤销 ⇒ {@code 40402}，且文案说清"撤销是终态"。 */
  @Test
  void revokedTokenIsRejectedWith40402() throws Exception {
    // ★ 先成功访问一次：把"解析结果"真的写进 PublicCredentialResolver 的缓存 ——
    //   否则下面那条读到的可能只是"缓存里本来就没有"，而不是"撤销把缓存清了"
    assertThat(get("/api/v1/public/" + fixture.token(), null).statusCode()).isEqualTo(200);
    jdbc.update(
        "update as_access_credential set revoked_at = now(), enabled = false where id = ?",
        fixture.credentialId());

    HttpResponse<String> response = get("/api/v1/public/" + fixture.token(), null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).as("★★ 判据②：第②态 = 已撤销（40402）").contains("\"code\":40402");
    assertThat(response.body()).contains("访问凭证已撤销").contains("终态");
  }

  /**
   * ★★ 判据② + <strong>判据③（撤销 ⇒ 同一请求周期内失效）</strong>。
   *
   * <p>两次访问在<strong>同一个测试方法内、同一个 JVM 内、同一个缓存实例上</strong>发生：第一次成功 ⇒ 解析结果已进缓存；随后撤销（走服务层的 {@code
   * revoke}，它会 {@code forget}）⇒ 第二次必须被拒。
   *
   * <p>★ 这条断言的<strong>反面</strong>才是它的价值：若撤销不清缓存，第二次会命中缓存、照常返回 200 —— 而"撤销了还能用"不会有任何报错。
   */
  @Test
  void revokeInvalidatesTheSameTokenWithinTheSameRequestCycle() throws Exception {
    assertThat(get("/api/v1/public/" + fixture.token(), null).statusCode())
        .as("前置：撤销之前必须先用通一次（否则下面读到的可能只是'一直被拒'）")
        .isEqualTo(200);

    // ★ 走服务层的那条路（= 真实撤销路径）：AccessCredentialService.revoke → PublicCredentialResolver.forget
    revokeThroughService();

    HttpResponse<String> response = get("/api/v1/public/" + fixture.token(), null);
    assertThat(response.statusCode()).as("★★ 判据③：撤销后**同一个请求周期内**再访问即失效（缓存必须当场清掉）").isEqualTo(404);
    assertThat(response.body()).contains("\"code\":40402");

    Response stream = postMessages(fixture.token(), body(question()), null);
    assertThat(stream.status()).as("★ SSE 那条也走同一个解析入口 ⇒ 同样当场失效（不是只有 GET 那条被拒）").isEqualTo(404);
    assertThat(stream.body()).contains("\"code\":40402");
  }

  /** ★★ 判据②：已过期 ⇒ {@code 40403}（时间自己走过去，没有任何一次写操作会触发清缓存）。 */
  @Test
  void expiredTokenIsRejectedWith40403() throws Exception {
    jdbc.update(
        "update as_access_credential set expires_at = ? where id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
        fixture.credentialId());

    HttpResponse<String> response = get("/api/v1/public/" + fixture.token(), null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).as("★★ 判据②：第③态 = 已过期（40403）").contains("\"code\":40403");
    assertThat(response.body()).contains("访问凭证已过期");
  }

  /** ★★ 判据②：已停用 ⇒ {@code 40404}，且文案说清"停用是可恢复的"（与撤销分开）。 */
  @Test
  void disabledTokenIsRejectedWith40404() throws Exception {
    jdbc.update(
        "update as_access_credential set enabled = false where id = ?", fixture.credentialId());

    HttpResponse<String> response = get("/api/v1/public/" + fixture.token(), null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).as("★★ 判据②：第④态 = 已停用（40404）").contains("\"code\":40404");
    assertThat(response.body()).contains("访问凭证已停用").contains("可恢复");
  }

  // ────────────────────────── ④ 停用账号 ⇒ 不出内容 ──────────────────────────

  /**
   * ★★ 判据④（{@code 08 §4.4.4} 末尾那条产品口径）：<strong>归属人账号被停用 ⇒ 它发出的链接不再返回任何内容</strong>。
   *
   * <p>★ 与四态的<strong>区别</strong>是有意的、也是这条用例断言的重点：链接<strong>打得开</strong>（凭证有效、200），
   * 但检索<strong>一条都拿不到</strong>（{@code SubjectIdentityPort} 载入不出 ⇒ {@code
   * AccessScopeAdapter.asSubject} 装 {@code nobody()}）。 若实现回落到"照抄当前请求者"，匿名访客会得到 {@code
   * nobody()}（看起来也对），但<strong>登录用户</strong>会拿到自己的范围 —— 而那正是"停用不起作用"。
   *
   * <p>★★ 口径（第 17 周 · Lead 裁决 ②）：<strong>保持 fail-closed（404），但文案不许撒谎</strong> ——
   * 应用是存在的，是<strong>发链接的那个账号</strong>当前不可用。所以两个端点都必须给
   * <strong>"这条链接当前不可用"</strong>（而不是"应用不存在"，也不是四态里的任何一个码）。
   */
  @Test
  void disabledOwnerAccountYieldsNoContent() throws Exception {
    assertThat(get("/api/v1/public/" + fixture.token(), null).statusCode())
        .as("前置：停用之前链接是能用的")
        .isEqualTo(200);

    jdbc.update("update iam_user set status = 'DISABLED' where id = ?", ZHANGYUN);

    HttpResponse<String> context = get("/api/v1/public/" + fixture.token(), null);
    assertThat(context.statusCode()).as("★ 主体载入不出 ⇒ 应用展示面也读不出来（同一次委派，不是第二处判定）").isEqualTo(404);
    assertThat(context.body())
        .as("★★ 裁决②：文案必须是「这条链接当前不可用」—— 说「应用不存在」是撒谎（应用在，是账号不可用）")
        .contains("这条链接当前不可用")
        .contains("请联系分享者")
        .doesNotContain("应用不存在")
        .doesNotContain("访问凭证不存在");
    assertThat(context.body())
        .as("★ 且**不**借用四态里的任何一个码（那四个码各有自己的界面文案与下一步）")
        .doesNotContain("40401")
        .doesNotContain("40402")
        .doesNotContain("40403")
        .doesNotContain("40404");

    // ★ 问答流那条走的是同一条判定、同一句话（ChatController 的凭据头分支 → PublicChatService.startTurn）
    Response stream = postMessages(fixture.token(), body(question()), null);
    assertThat(stream.status()).as("★ 流还没开始 ⇒ 仍然走统一包络（不是先 200 再在流里报错）").isEqualTo(404);
    assertThat(stream.body())
        .as("★★ 两个端点对同一种失效必须给**同一句话**")
        .contains("这条链接当前不可用")
        .doesNotContain("应用不存在")
        .doesNotContain(PUBLIC_TOPIC)
        .doesNotContain(INTERNAL_TOPIC)
        .doesNotContain(SECRET_TOPIC)
        .doesNotContain(CONFIDENTIAL_TOPIC);
  }

  // ────────────────────────── ⑤ 登录用户拿同一链接不放大 ──────────────────────────

  /**
   * ★★ 判据⑤（{@code 08 §4.4.4} 判据②）：clearance 更低的<strong>登录用户</strong>拿同一条链接 ⇒
   * <strong>拿不到</strong>超出他自身范围的内容。
   *
   * <p>夹具：归属人 {@code zhangyun}（SECRET / ALL），链接冻结 {@code INTERNAL}； 调用者 {@code chenjun}（PUBLIC /
   * SHARED / 市场部）。生效范围 = 归属人 ⊓ 冻结 ⊓ 调用者 = PUBLIC ⇒ 只看得见 PUBLIC 那一条，连 INTERNAL 都拿不到 ——
   * 而"不放大"的实现若被绕过（例如把 {@code asSubject} 换成"直接跑"），他会拿到 PUBLIC+INTERNAL（他自己的范围与冻结值取更低失败），也不报错。
   */
  @Test
  void loggedInCallerCannotReachBeyondHisOwnClearanceThroughTheSameLink() throws Exception {
    String callerToken = login(CHENJUN, CHENJUN_PASSWORD);

    Response response = postMessagesStreaming(fixture.token(), body(question()), callerToken);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.dataOf("refs"))
        .as("★★ 判据⑤：调用者只有 PUBLIC ⇒ 连链接冻结值之内的 INTERNAL 也拿不到")
        .contains(PUBLIC_TOPIC)
        .doesNotContain(INTERNAL_TOPIC);
    assertThat(response.raw())
        .as("★ 三条更高的密级都不得出现")
        .doesNotContain(INTERNAL_TOPIC)
        .doesNotContain(SECRET_TOPIC)
        .doesNotContain(CONFIDENTIAL_TOPIC);
  }

  // ────────────────────────── HTTP 与解析工具 ──────────────────────────

  /** 一帧 SSE。 */
  private record Frame(String event, String data, boolean heartbeat) {}

  /** 一次流式请求的读数（非 200 时 {@code frames} 为空、{@code body} 是包络文本）。 */
  private record Response(int status, String contentType, List<Frame> frames, String body) {

    List<String> events() {
      return frames.stream().filter(frame -> !frame.heartbeat()).map(Frame::event).toList();
    }

    /** 某个事件名的<strong>第一条</strong> data。 */
    String dataOf(String event) {
      return frames.stream()
          .filter(frame -> event.equals(frame.event()))
          .map(Frame::data)
          .findFirst()
          .orElseThrow(() -> new AssertionError("响应里没有 event: " + event + " 的帧"));
    }

    /** 帧名 + data 拼出来的粗形状（用于"哪一条正文都没漏出去"这类断言）。 */
    String raw() {
      StringBuilder text = new StringBuilder();
      for (Frame frame : frames) {
        text.append(
                frame.heartbeat() ? ": keep-alive" : "event: " + frame.event() + " " + frame.data())
            .append('\n');
      }
      return text.toString();
    }
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30)).GET();
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * 发一次公开问答流，把响应体当成文本读（包络用例用它 —— SSE 端点的"流还没开始"错误是 JSON）。
   *
   * @param bearer {@code null} = <strong>匿名</strong>（本类绝大多数用例走这条）
   */
  private Response postMessages(String token, String body, String bearer) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + CHAT_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            // ★★ 逐字照前端 lib/sse.ts:161：免登录场景把 Authorization 换成这个头
            .header("X-Access-Credential", token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Response(
        response.statusCode(),
        response.headers().firstValue("Content-Type").orElse(""),
        List.of(),
        response.body());
  }

  /** 发一次公开问答流，期望 200 —— 返回逐帧读数。 */
  private Response postMessagesStreaming(String token, String body, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + CHAT_PATH))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Accept", "text/event-stream")
            // ★★ 逐字照前端 lib/sse.ts:161：免登录场景把 Authorization 换成这个头
            .header("X-Access-Credential", token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    HttpResponse<InputStream> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      try (InputStream stream = response.body()) {
        throw new AssertionError(
            "SSE 端点返回了 "
                + response.statusCode()
                + "（期望 200）；Content-Type="
                + response.headers().firstValue("Content-Type").orElse("")
                + "；请求体字节数="
                + body.getBytes(StandardCharsets.UTF_8).length
                + "；body="
                + new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    try (InputStream stream = response.body()) {
      return new Response(
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(""),
          readFrames(stream),
          "");
    }
  }

  /** 读 SSE 流到结尾；注释帧（开流填充 / 心跳）只统计为 {@code heartbeat}，不计入业务事件序列。 */
  private static List<Frame> readFrames(InputStream stream) throws Exception {
    List<Frame> frames = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    StringBuilder frame = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty()) {
        Frame parsed = parseFrame(frame.toString());
        frame.setLength(0);
        if (parsed != null) {
          frames.add(parsed);
        }
        continue;
      }
      frame.append(line).append('\n');
    }
    Frame last = parseFrame(frame.toString());
    if (last != null) {
      frames.add(last);
    }
    return frames;
  }

  private static Frame parseFrame(String block) {
    if (block.isBlank()) {
      return null;
    }
    String event = "message";
    String data = null;
    String comment = null;
    for (String line : block.split("\n")) {
      if (line.startsWith(":")) {
        comment = line.substring(1).trim();
      } else if (line.startsWith("event:")) {
        event = line.substring("event:".length()).trim();
      } else if (line.startsWith("data:")) {
        data = line.substring("data:".length()).trim();
      }
    }
    if (comment != null) {
      // ★ 开流填充帧（`: open: …`）与心跳都是注释帧：前者证明"响应头提交了"，后者证明"连接还活着"。
      //   判据谈的是**业务帧**，所以两类都不进 events()。
      return new Frame(event, comment, true);
    }
    return data == null ? null : new Frame(event, data, false);
  }

  private String login(String username, String password) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("种子账号必须能登录").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private static String question() {
    // ★ 与夹具正文共用词元（见 QUERY_TERM 的说明）：空引用的两种原因必须分得开
    return QUERY_TERM;
  }

  private String body(String query) {
    return "{\"question\":\"" + query + "\",\"attachmentIds\":[],\"thinking\":false}";
  }

  private static String stringField(String jsonText, String field) {
    int at = jsonText.indexOf("\"" + field + "\"");
    if (at < 0) {
      throw new AssertionError("JSON 里没有字段 " + field + "：" + jsonText);
    }
    int colon = jsonText.indexOf(':', at);
    int open = jsonText.indexOf('"', colon + 1);
    int close = jsonText.indexOf('"', open + 1);
    return jsonText.substring(open + 1, close);
  }

  private String statusInDb(String messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, messageId);
  }

  private UUID ownerOfConversationOf(String messageId) {
    return jdbc.queryForObject(
        "select c.user_id from cv_conversation c join cv_message m on m.conversation_id = c.id"
            + " where m.id = ?::uuid",
        UUID.class,
        messageId);
  }

  /**
   * 走服务层撤销（= 真实路径：{@code AccessCredentialService.revoke} → {@code
   * PublicCredentialResolver.forget}）。
   */
  private void revokeThroughService() {
    SecurityContexts.asUser(
        ZHANGYUN,
        ClassificationLevel.SECRET,
        () -> {
          credentialService.revoke(fixture.appId(), fixture.credentialId(), ZHANGYUN);
          return null;
        });
  }

  private String base() {
    return "http://localhost:" + port;
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(
      UUID appId,
      UUID kbId,
      UUID credentialId,
      String token,
      UUID chatModelId,
      UUID embeddingModelId) {}

  private Fixture seedFixture() {
    UUID chatModelId = seedChatModel();
    UUID embeddingModelId = seedEmbeddingModel();
    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, 'PRIVATE', now(), now())",
        kbId,
        FIXTURE_PREFIX + "库",
        embeddingModelId,
        ZHANGYUN);
    UUID appId = UUID.randomUUID();
    jdbc.update(
        "insert into as_assistant (id, name, icon, description, type, model_id, owner_id,"
            + " visibility, published, prologue, quick_questions, created_at, updated_at)"
            + " values (?, ?, '🤖', '公开面夹具应用描述', 'SIMPLE', ?, ?, 'PRIVATE',"
            + " true, '你好，我能回答制度类问题', array['差旅标准是多少'], now(), now())",
        appId,
        FIXTURE_PREFIX + "应用",
        chatModelId,
        ZHANGYUN);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);
    seedDocumentAndSegment(kbId, FIXTURE_PREFIX + "公开文档", ClassificationLevel.PUBLIC, PUBLIC_TOPIC);
    seedDocumentAndSegment(
        kbId, FIXTURE_PREFIX + "内部文档", ClassificationLevel.INTERNAL, INTERNAL_TOPIC);
    seedDocumentAndSegment(kbId, FIXTURE_PREFIX + "秘密文档", ClassificationLevel.SECRET, SECRET_TOPIC);
    seedDocumentAndSegment(
        kbId, FIXTURE_PREFIX + "机密文档", ClassificationLevel.CONFIDENTIAL, CONFIDENTIAL_TOPIC);

    // ★ 凭证：冻结 INTERNAL（= 创建者当时的 clearance 这一口径的夹具值），长期有效、启用中
    UUID credentialId = UUID.randomUUID();
    String token = "pk_live_" + randomToken();
    jdbc.update(
        "insert into as_access_credential (id, assistant_id, token, enabled, max_classification,"
            + " access_count, created_at, created_by, updated_at, version)"
            + " values (?, ?, ?, true, 'INTERNAL', 0, now(), ?, now(), 0)",
        credentialId,
        appId,
        token,
        ZHANGYUN);
    return new Fixture(appId, kbId, credentialId, token, chatModelId, embeddingModelId);
  }

  private static String randomToken() {
    byte[] entropy = new byte[32];
    new java.security.SecureRandom().nextBytes(entropy);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }

  private UUID seedChatModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, ?, 'CHAT', 'LOCAL_INFERENCE',"
            + " 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        id,
        FIXTURE_PREFIX + "对话模型");
    return id;
  }

  private UUID seedEmbeddingModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at) values (?, ?, 'EMBEDDING',"
            + " 'LOCAL_INFERENCE', 'bge-m3', 'http://localhost:11434/v1', 768, 'LOADED', true,"
            + " now(), now())",
        id,
        FIXTURE_PREFIX + "向量模型");
    return id;
  }

  private void seedDocumentAndSegment(
      UUID kbId, String docName, ClassificationLevel level, String text) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    UUID docId = DocumentFixture.insertSucceeded(jdbc, kbId.toString(), docName, level.name());
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            // ★ 密级**从文档继承**（不进参数）：写死一个就会把被测的谓词换成另一条
            // ★★ `title` 必须给：`SourceRef` 的紧凑构造器把 title/docName/kbName 都当**必填**
            //    （`引用的必填字段 title 不能为空`），而 `ChatService.retrieve` 的 catch 是**整批**的 ——
            //    一条没有标题的分段会让**这一轮全部引用**变成空。
            //    ★ 本批实测到的 refs 为空的第二个原因就是这个（逐字读数见交付报告）。
            + " values (?, ?, ?, 'KB', ?, 0, ?, '第一章', ?, ?, ?, ?, false, false, false, true,"
            + " 'PENDING_EMBEDDING', now(), now(),"
            + " (select classification from kb_document where id = ?))",
        UUID.randomUUID(),
        docId,
        kbId,
        kbId,
        docName,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
  }

  /**
   * ★ 假模型：只在测试里（不起任何长驻服务），吐一段确定性的答案。
   *
   * <p>★ 用它而不是 {@code tools/openai_stub.py}：本类要的是"事件序列与引用帧的形状"，
   * 那需要<strong>可控的吐字节拍</strong>；真模型给不了确定性（见 {@code FakeChatStreamPorts}）。
   */
  @TestConfiguration
  static class FakeModelConfig {

    @Bean
    AtomicReference<ChatStreamPort> fakeModel() {
      return new AtomicReference<>();
    }

    @Bean
    @Primary
    ChatStreamPort fakeChatStreamPort(AtomicReference<ChatStreamPort> fakeModel) {
      // ★ 兜底实现（未装假模型时）：一次吐完、没有停顿
      return (modelId, prompt, timeout, callback) -> {
        ChatStreamPort actual = fakeModel.get();
        if (actual != null) {
          actual.stream(modelId, prompt, timeout, callback);
          return;
        }
        callback.onChunk(FakeChatStreamPorts.ANSWER);
        callback.onEnd(ChatStreamPort.End.COMPLETE, null);
      };
    }
  }
}
