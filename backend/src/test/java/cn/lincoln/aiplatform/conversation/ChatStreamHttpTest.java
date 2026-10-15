package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ChatStopSignal;
import cn.lincoln.aiplatform.conversation.application.ChatStreamProperties;
import cn.lincoln.aiplatform.conversation.application.ChatStreamWriter;
import cn.lincoln.aiplatform.conversation.application.ChatTurnStore;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.conversation.support.AssistantGeneratedTextWait;
import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * 第 17 周「答给你听」的<strong>真 HTTP + 真库 + 假模型</strong>读数。
 *
 * <h3>★★ 为什么必须是这样一组（而不是领域层或 MockMvc）</h3>
 *
 * <ul>
 *   <li><strong>真 HTTP</strong>（{@code RANDOM_PORT} + {@code java.net.http.HttpClient}）：
 *       判据里有三条只有走真连接才成立 —— 事件序列是<strong>帧顺序</strong>、{@code text/event-stream}
 *       是<strong>响应头</strong>、{@code DISCONNECT} 是<strong>写连接失败</strong>。 MockMvc
 *       不经过真容器与真过滤器链，"前端真的能收到这些帧吗"这个问题它答不了；
 *   <li><strong>真库</strong>（Testcontainers PostgreSQL + 真 Flyway）：{@code A2}（刷新不重置）与 {@code
 *       A5}（停止保留内容）的判据是"<strong>重新查库</strong>读到的状态与正文"—— 内存断言证明不了"库里真有那一行"；
 *   <li><strong>假模型</strong>：判据要的是"吐字节拍可控"，真模型给不了确定性 （见 {@link FakeChatStreamPorts}）。★
 *       本类的假实现<strong>只在测试里</strong>，不启任何长驻服务。
 * </ul>
 *
 * <h3>★★ 四条终止路径各一条用例（判据②：恰好一次 done）</h3>
 *
 * <table>
 *   <tr><th>用例</th><th>路径</th><th>怎么造</th><th>终态</th></tr>
 *   <tr><td>{@link #normalPathEmitsTheContractSequenceAndPersistsTheAnswer()}</td><td>{@code COMPLETE}</td>
 *       <td>假模型正常吐完</td><td>{@code GENERATED}</td></tr>
 *   <tr><td>{@link #stopSignalEndsAsStoppedAndKeepsTheGeneratedText()}</td><td>{@code ABORT}</td>
 *       <td>★ 显式停止信号（{@link ChatStopSignal}）</td><td>{@code STOPPED}</td></tr>
 *   <tr><td>{@link #idleTimeoutEmitsErrorThenDoneAndPersistsFailed()}</td><td>{@code IDLE_TIMEOUT}</td>
 *       <td>假模型一块都不吐（长过一整个闲置超时）</td><td>{@code FAILED}</td></tr>
 *   <tr><td>{@link #clientDisconnectPersistsFailedWithoutLosingWhatWasGenerated()}</td><td>{@code DISCONNECT}</td>
 *       <td>客户端读一半掐断连接</td><td>{@code FAILED}</td></tr>
 * </table>
 *
 * <h3>★ 时间被压缩了，而后端的契约默认值没有被改动</h3>
 *
 * <p>{@code stream-types.ts} 的 20s / 60s 是契约值，本类的 {@code @TestPropertySource} 把它们压到 300ms /
 * 1200ms（<strong>仍然满足</strong> {@code heartbeat × 3 ≤ idleTimeout}）： 判据是"这条关系成立"与"该发的时候真的发了"，不是"等
 * 20 秒"。 契约常量本身由 {@code ChatStreamTimingTest} 单独断言（★ 它读的是契约文件，不是抄一遍 Java 常量）。
 *
 * <h3>★ 两处"分层读数"的分工（各读一半，合起来才是完整读数）</h3>
 *
 * <ul>
 *   <li><strong>本类</strong>读 HTTP 层：真连接上收到的帧序列、{@code
 *       text/event-stream}、以及<strong>重新查库</strong>读到的终态与正文；
 *   <li><strong>{@code ChatStreamEventRecordingTest}</strong>（{@code conversation.application}
 *       包）读事件层： {@code ChatService} 的四条路径各自产出哪几帧、顺序如何、落库终态是什么。
 * </ul>
 *
 * <p>★ 为什么 {@code DISCONNECT} 那条必须由后者补位：连接是写不出去的，{@code error}/{@code done} 两帧
 * <strong>到不了</strong>任何客户端 —— 于是"两帧的顺序与恰好一次"在真 HTTP 上原理性地读不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      // ★ 与 SecurityChainTest 同一条理由：本类验的是**问答链路**，登录只是拿令牌的手段。
      //   验证码开启时的行为由 ComplianceTest 覆盖（那边不关，跑的是生产路径）。
      "app.security.captcha.enabled=false",
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=1200",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class ChatStreamHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 种子账号（{@code V4}）—— 走真登录拿真令牌，于是走的也是真的鉴权链。 */
  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id（造"自己的会话/应用/库"用）。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** 另一个用户（造"别人的会话"用；★ 库里没有这个人也没关系 —— 归属判定只比 id）。 */
  private static final UUID SOMEBODY_ELSE = UUID.fromString("00000000-0000-4000-8000-00000000dead");

  /** {@code V25} 种子写死的 ROOT 部门（{@code kb_document.org_id} 是外键）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  /**
   * 这一行是不是开流填充注释帧的载荷（{@code : open: …}）。
   *
   * <p>★ 填充帧**不计入帧序列**：它的用途是"把 HTTP 响应头提交出去"（见 {@code ChatController}）， 而"事件序列 / 恰好一次
   * done"这些判据谈的是<strong>业务帧</strong>。 把它算成第一帧，会让"读 1 帧拿 meta"这类采样全部错位 —— 本类实测踩到过。
   */
  private static boolean isOpenPadding(String line) {
    return line.startsWith(":") && line.substring(1).trim().startsWith(OPEN_PADDING_MARKER);
  }

  /** 开流填充注释帧的载荷前缀 —— 与 {@code ChatController} 里那个常量同源（见 {@link #isOpenPadding}）。 */
  private static final String OPEN_PADDING_MARKER = "open: ";

  /**
   * 注入写失败的那一帧：服务端写第 {@code FAIL_AFTER_FRAMES + 1} 帧时抛"连接已断开"。
   *
   * <p>★ 取 10：它排在 {@code meta} / {@code refs} 与<strong>若干个 {@code delta}</strong> 之后 ——
   * 于是"断开不是清空"那条断言读到的正文<strong>非空</strong>（若在 {@code refs} 那一帧就断， 正文恒为空串，那条断言会退化成"空串也算保留"）。
   */
  private static final int FAIL_AFTER_FRAMES = 10;

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ChatStopSignal stopSignal;

  /** 每个用例自己装一个假模型（见 {@link FakeChatStreamPorts}）；{@code null} = 用兜底实现。 */
  @Autowired AtomicReference<ChatStreamPort> fakeModel;

  /**
   * 写失败的注入开关：{@code > 0} 时"写满这么多帧之后断一次"。
   *
   * <p>★ 默认 {@code 0} = <strong>不注入</strong>：于是其余用例走的是生产那条"照收不误"的路径， 而只有 {@code clientDisconnect…}
   * 那条用例显式打开它。反证的做法也正是"不打开它"。
   */
  @Autowired AtomicInteger failAfterFrames;

  /** 那一次写失败真的发生了（★ "注入生效了吗"本身要可断言）。 */
  @Autowired CountDownLatch writeFailureFired;

  /**
   * 服务端<strong>尝试</strong>写出去的事件名（含写失败那一次之后的重试）。
   *
   * <p>★ 它读的是"服务端试图写什么"，不是"客户端收到什么" —— {@code DISCONNECT} 那条路径上两者不同： 终态帧到不了客户端，但"服务端有没有尝试写它"是可读的。
   */
  @Autowired List<String> attemptedFrames;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final List<UUID> seededApps = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID appId : seededApps) {
      jdbc.update("delete from as_assistant_kb where assistant_id = ?::uuid", appId);
      jdbc.update("delete from as_assistant where id = ?::uuid", appId);
    }
    seededApps.clear();
    jdbc.update(
        "delete from kb_segment where kb_id in"
            + " (select id from kb_knowledge_base where name like 'SSE 夹具%')");
    jdbc.update(
        "delete from kb_document where kb_id in"
            + " (select id from kb_knowledge_base where name like 'SSE 夹具%')");
    jdbc.update("delete from kb_knowledge_base where name like 'SSE 夹具%'");
    jdbc.update("delete from ai_model where name like 'SSE 夹具%'");
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation"
            + " where user_id = ?::uuid)",
        ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", SOMEBODY_ELSE);
    fakeModel.set(null);
    // ★ 注入是"按用例"的一次性开关：不重置的话它会漏到下一条用例（而它正是"写失败⇒FAILED"的成因）
    resetWriteFailureInjection();
  }

  // ────────────────────────── ① 正常路径：序列 + 落库 ──────────────────────────

  /**
   * ★★ 判据 ①②③④ 的正常路径读数（一条用例里把四件事一起断言，因为它们是<strong>同一条流</strong>的四面）。
   *
   * <ol>
   *   <li>HTTP 上的<strong>事件序列</strong>逐字：{@code meta → refs → delta*}，收在 {@code done}；
   *   <li>{@code refs} <strong>先于</strong>首个 {@code delta}（★ 领域层已有一条，这里是 HTTP 层的独立读数）；
   *   <li>★ <strong>恰好一次 {@code done}</strong>，且它的 {@code status=GENERATED}、{@code tokens} 三个字段在；
   *   <li>★ <strong>重新查库</strong>（纯 SQL，不读内存）：{@code GENERATED} + 正文逐字 + 引用快照 + tokens。
   * </ol>
   */
  @Test
  void normalPathEmitsTheContractSequenceAndPersistsTheAnswer() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    // ★ 第二条好分段：让下面那条"index 连续递增"的逐字段核对不是平凡的（只剩一条时它恒真）
    UUID secondSegmentId =
        seedDocumentAndSegment(fixture.kbId(), "SSE 报销制度文档", "报销凭证章节", "差旅住宿标准按职级分档，住宿费上限见附表");
    // ★ 长答案 + 60ms/块：给下面那条"生成中"读数留出足够长的窗口（短答案会在采样前就写完）
    fakeModel.set(FakeChatStreamPorts.longAnswerStreaming(60, 12));

    Response response = chat(login(), body(fixture.appId(), null, "差旅住宿标准"));

    assertThat(response.status()).as("SSE 端点的响应码必须是 200").isEqualTo(200);
    assertThat(response.contentType())
        .as("★ 契约要求 text/event-stream —— 不是它，前端的流式解析器根本不会启动")
        .startsWith("text/event-stream");

    List<String> events = response.events();
    assertThat(events)
        .as("★★ 事件序列逐字照契约：meta 在最前，refs 在全部 delta 之前，done 恰好一条且在最后")
        .startsWith("meta", "refs")
        .endsWith("done")
        .contains("delta");
    assertThat(events.indexOf("refs"))
        .as("★★ 判据③：refs 必须先于第一个 delta（HTTP 层读数，不只是领域层那条）")
        .isLessThan(events.indexOf("delta"));
    assertThat(events.stream().filter("done"::equals).count()).as("★★ 判据②：恰好一次 done").isEqualTo(1);

    // ★ 帧里的字段名逐字照契约（多一个 / 少一个都不会让前端报错，所以必须在这里钉住）
    String metaData = response.dataOf("meta");
    assertThat(metaData).contains("\"messageId\"").contains("\"traceId\"").contains("\"model\"");
    String refsData = response.dataOf("refs");
    assertThat(refsData)
        .as(
            "★ refs 帧的 wire 形状是 {\"refs\":[...]}（契约里它是一个 SourceRef 数组）。"
                + "★ 元素级字段名（index/title/docName/kbName/score/segmentId）由既有"
                + " SourceRefSerializationTest / MessageContractCrossCheckTest 逐字钉住（本类不重复一份）")
        .startsWith("{\"refs\":[")
        .endsWith("]}");
    // ★★ 验内容（#123 的判据）：只验形状的断言在"引用整体为空"时照样绿 —— 第 17 周那个 P0
    //   （检索跑在模型线程上 ⇒ 身份 ThreadLocal 读不到 ⇒ 引用永远为空）正是从它眼皮底下过去的。
    //   所以这里必须读"这一轮到底给出了哪几条引用"，而不只是"这一帧长得对不对"。
    assertThat(refsData)
        .as("★★ 引用里必须含夹具正文的关键词 —— 检索真的跑了，而且返回的就是这两条分段（空引用时这条立刻红）")
        .contains("差旅住宿标准为每人每天500元")
        .contains("住宿费上限见附表");
    assertThat(refsData)
        .as("★ 逐字段核对：index 从 1 连续递增、title/docName/kbName 非空且是夹具里那几个值、深链 id 是那条分段")
        .contains("\"index\":1")
        .contains("\"index\":2")
        .doesNotContain("\"index\":3")
        .contains("\"title\":\"差旅住宿标准章节\"")
        .contains("\"docName\":\"SSE 差旅制度文档\"")
        .contains("\"docName\":\"SSE 报销制度文档\"")
        .contains("\"kbName\":\"SSE 夹具库\"")
        .contains("\"segmentId\":\"" + fixture.segmentId() + "\"");
    // ★★ 判据①：真栈上"知识库引用必带 kbId" —— 这一条就是让前端那条结构性判据成立的那条读数。
    //   判据用的是 SourceRef.kbId **缺失**（而不是某个字符串值），所以这里必须把真实库 id 钉在 wire 上。
    //   ★ 两条引用都要核（只核一条的话，"装配点只给第一条接了真实值"这种改法照样绿）。
    String documentId = documentIdInDb(fixture.segmentId());
    String secondDocumentId = documentIdInDb(secondSegmentId);
    assertThat(refsData)
        .as(
            "★★ 判据①（真栈）：知识库引用必须带 kbId —— 前端「引用卡片按来源类型切换标签」用的就是这条"
                + "结构性判据（kbId 缺失 = 会话域）。此前装配点恒传 null ⇒ 真栈上连库引用也没有 kbId ⇒"
                + " 每条库引用都被读成「来源：」")
        .contains("\"kbId\":\"" + fixture.kbId() + "\"")
        .doesNotContain("\"kbId\":null");
    // ★★ 判据③：documentId 非空 —— 同一条恒 null 的分量就是 #91（引用深链是静默 no-op）的根因：
    //   前端 Index.vue 用它拼深链的 query，而为 null 时它拼出空 query ⇒ 点了没反应也不报错。
    assertThat(refsData)
        .as(
            "★★ 判据③（真栈 · #91 的根因）：documentId 必须非空 —— 前端 Index.vue 用它拼深链的 query，"
                + "恒为 null 时点击是一次**静默 no-op**（不报错、也不跳转）")
        .contains("\"documentId\":\"" + documentId + "\"")
        .contains("\"documentId\":\"" + secondDocumentId + "\"")
        .doesNotContain("\"documentId\":null");
    assertThat(classificationRankInDb(fixture.segmentId()))
        .as(
            "★ 密级与预期一致：SourceRef 里**没有** classificationRank 这个字段（契约 stream-types.ts:105-120 冻结的"
                + " 10 个字段里没有它），密级跑在检索的 pre-filter 上、不进引用载荷 ⇒ 只能从库里那两列核"
                + "（生成列 classification_rank 与 Java 枚举的 ordinal 必须同口径）")
        .isEqualTo(ClassificationLevel.INTERNAL.ordinal());
    assertThat(response.dataOf("delta")).as("delta 的字段名是 text").contains("\"text\"");
    String doneData = response.dataOf("done");
    assertThat(doneData)
        .as("★ done 的形状：status + messageId + tokens{prompt,completion,total} + durationMs")
        .contains("\"status\":\"GENERATED\"")
        .contains("\"messageId\"")
        .contains("\"tokens\"")
        .contains("\"prompt\"")
        .contains("\"completion\"")
        .contains("\"total\"")
        .contains("\"durationMs\"");

    // ★★ 判据④：重新查库（不是读内存）
    String messageId = stringField(metaData, "messageId");
    assertThat(stringField(doneData, "messageId"))
        .as("★ done 里的 messageId 必须与 meta 里那个是同一个（否则终态会挂到另一条消息上）")
        .isEqualTo(messageId);
    assertThat(statusInDb(messageId)).as("★ A2：流结束后库里那条助手消息是 GENERATED").isEqualTo("GENERATED");
    assertThat(contentInDb(messageId))
        .as("★★ 正文逐字（假模型的答案与库里那一段必须一字不差）")
        .isEqualTo(FakeChatStreamPorts.LONG_ANSWER);
    String refsSnapshot = refsInDb(messageId);
    assertThat(refsSnapshot)
        .as("★★ A4 的载体：引用快照随消息落库，且内容是**那两个分段**（引用为空时这条立刻红）")
        .contains("差旅住宿标准为每人每天500元")
        .contains("住宿费上限见附表");
    assertThat(refsSnapshot)
        .as("★ '零命中'（[]）与'引用从没到过'（NULL）仍是两个不同的值：本轮的引用既非空数组、也不是 NULL")
        .isNotNull()
        .isNotEqualTo("[]");
    assertThat(refsSnapshot)
        .as(
            "★ 深链的两个分量也随引用快照落库 —— 库里的快照与 wire 上那一帧是同一批"
                + "（否则『点开历史会话里的引用』永远拼不出 query，而那是另一条静默 no-op）。"
                + "★ 注意库里那一列是 jsonb，它的 ::text 输出在冒号后**带一个空格**（wire 上那一帧是紧凑 JSON）")
        .contains("\"kbId\": \"" + fixture.kbId() + "\"")
        .contains("\"documentId\": \"" + documentId + "\"");
    assertThat(tokensInDb(messageId))
        .as("★ tokens 落库（本批假模型不报用量 ⇒ 两列都是 0，而不是 NULL —— 调用确实发生过）")
        .isNotNull();
  }

  /**
   * ★★ 判据④：{@code title} 为 {@code NULL} 的分段<strong>仍然出现</strong>，而且它的 {@code title} 是 {@code
   * docName} （读取侧兜底）。
   *
   * <p>★ 造法与前一条同源（{@code kb_segment.title} 在库里可空，{@code V15}），但期望<strong>相反</strong>：
   * 前一条钉"兜不住的那一类仍然逐条跳过"，这一条钉"兜得住的那一类不再被丢掉"。
   *
   * <p>★★ 为什么兜底值必须是 {@code docName} 而不是占位串：文档名是<strong>库里真实存在的字</strong>， 界面上「知识库 / 文档名」那一行本来就显示它
   * —— 换成"无标题"或分段 id，用户会在引用栏里看到一个 <strong>在任何地方都找不到的名字</strong>，而那正是引用可信度的反例。
   *
   * <p>★ 本条可证伪：把 {@code ChatService#titleOf} 的兜底去掉（直接 {@code card.title()}）⇒
   * 这一条立刻红（那一条引用会被逐条摘掉，{@code refs} 里只剩另一条）。
   */
  @Test
  void aSegmentWithNullTitleFallsBackToItsDocumentNameInsteadOfBeingDropped() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    UUID titlelessSegmentId =
        seedDocumentAndSegment(fixture.kbId(), "SSE 差旅补充文档", null, "差旅住宿标准为每人每天300元（补充口径）");
    fakeModel.set(FakeChatStreamPorts.streaming(0, 20, 12));

    ListAppender<ILoggingEvent> chatServiceLog = captureChatServiceLog();
    Response response;
    try {
      response = chat(login(), body(fixture.appId(), null, "差旅住宿标准"));
    } finally {
      releaseLogCapture(chatServiceLog);
    }

    String refsData = response.dataOf("refs");
    assertThat(refsData)
        .as(
            "★★ 判据④：title 为空**不再是**『这条引用被摘掉』的理由 —— 用 docName 兜底之后它照常出现在引用栏里"
                + "（它此前被丢掉，而丢掉之后的读数与『库里确实没有相关内容』完全一样）")
        .contains("差旅住宿标准为每人每天300元")
        .contains("\"segmentId\":\"" + titlelessSegmentId + "\"");
    assertThat(refsData)
        .as("★★ 判据④的另一面：兜底值取的是 docName（同一条引用的 docName 与 title 是同一个值）——" + "不是占位串、也不是分段 id")
        .contains("\"title\":\"SSE 差旅补充文档\"")
        .contains("\"docName\":\"SSE 差旅补充文档\"");
    assertThat(refsData)
        .as("★ 两条都装成了 ⇒ 编号仍从 1 连续递增（兜底不许把编号打乱）")
        .contains("\"index\":1")
        .contains("\"index\":2")
        .doesNotContain("\"index\":3");
    assertThat(logMessagesOf(chatServiceLog))
        .as("★ 兜底成功时**不是**一次降级：这一轮不许记『跳过 N 条』（否则日志会把合法数据读成脏数据）")
        .noneMatch(message -> message.contains("跳过"));

    String messageId = messageIdOf(response.frames());
    assertThat(refsInDb(messageId))
        .as("★ 落库的引用快照里也是兜底后的 title（库里的快照与 wire 上那一帧不许分叉）——" + "★ 库里那列是 jsonb，::text 输出在冒号后带一个空格")
        .contains("\"title\": \"SSE 差旅补充文档\"")
        .contains(titlelessSegmentId.toString());
  }

  /**
   * ★★ 判据①（{@code #123}）：单条坏数据 ⇒ <strong>摘掉这一条 + 计数</strong>，同一轮里其余引用照常返回。
   *
   * <p>造法：同一轮里放两条都能被全文检索命中的分段，其中一条的<strong>文档名为空串</strong> —— ★ 那是 {@code title}
   * 兜底的<strong>第二层防御</strong>要处理的那一类：{@code title} 为空时读取侧用 {@code docName} 顶上（见 {@link
   * #aSegmentWithNullTitleFallsBackToItsDocumentNameInsteadOfBeingDropped}），而 {@code docName}
   * 自己也为空时兜底就顶不住了 ⇒ 只能摘掉。
   *
   * <p>★ 库里 {@code kb_document.name} 只约束 {@code not null}、不约束非空串（{@code V13}）， 而 {@code SourceRef}
   * 把 {@code title} / {@code docName} 当必填 —— 所以这条路径今天就会被真实数据触发。
   *
   * <p>★★ 断言的四面（少一面这条用例就变成「看起来在验」）：
   *
   * <ol>
   *   <li>好的那条<strong>照常</strong>出现在 {@code refs} 帧与库里的引用快照里；
   *   <li>坏的那条<strong>不在</strong>引用里（连它的正文关键词都不许出现）；
   *   <li>活下来的那条 {@code index} 仍然是 <strong>1</strong> —— 跳过的空位不许占号，否则正文里的 {@code [N]} 会指向不存在的引用；
   *   <li>★ 日志里能读到 {@code 跳过 1 条} 且带可读原因（降级<strong>不许静默</strong>）。
   * </ol>
   *
   * <p>★★ <strong>本条可证伪</strong>（金丝雀 A）：把逐条保护改回"一个 {@code catch} 包整批" ⇒ 这一条立刻变红 （引用整体变空 ⇒ 上面第
   * 1/2/3/4 面一起红）。它不是"理论上会红"，本批实测红过一次并逐字记在交付报告里。
   */
  @Test
  void aSegmentWithBlankDocumentNameIsSkippedWhileTheOthersStillReachTheWire() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    UUID badSegmentId = seedDocumentAndSegment(fixture.kbId(), "", null, "差旅住宿标准为每人每天300元（补充口径）");
    fakeModel.set(FakeChatStreamPorts.streaming(0, 20, 12));

    ListAppender<ILoggingEvent> chatServiceLog = captureChatServiceLog();
    Response response;
    try {
      response = chat(login(), body(fixture.appId(), null, "差旅住宿标准"));
    } finally {
      releaseLogCapture(chatServiceLog);
    }

    String refsData = response.dataOf("refs");
    assertThat(refsData).as("★★ 判据①：一条分段装不成 ⇒ 其余引用照常返回（不是'整轮零引用'）").contains("差旅住宿标准为每人每天500元");
    assertThat(refsData)
        .as("★ 判据①：坏的那条（文档名为空 ⇒ title 兜底也顶不住）不许出现在引用里 —— 连它的正文关键词都不许有")
        .doesNotContain(badSegmentId.toString())
        .doesNotContain("300元");
    assertThat(refsData)
        .as("★ 判据①：活下来的那条 index 仍从 1 起（跳过的空位不占号，正文里的 [N] 才不会指向不存在的引用）")
        .contains("\"index\":1")
        .doesNotContain("\"index\":2");

    String messageId = messageIdOf(response.frames());
    assertThat(refsInDb(messageId))
        .as("★ 同一批引用也随消息落库 —— 库里的快照与 wire 上那一帧是同一批（不是'发出去有、存下来没有'）")
        .contains("差旅住宿标准为每人每天500元")
        .doesNotContain("300元");
    assertThat(logMessagesOf(chatServiceLog))
        .as("★★ 判据④：降级必须留痕 —— 日志里要能读到『跳过 1 条』，而且带上是哪个必填字段（不许静默：" + "静默时它与'库里确实没有相关内容'不可区分）")
        .anyMatch(message -> message.contains("跳过 1 条") && message.contains("不能为空"));
  }

  /**
   * ★ 心跳：无业务帧的间隙里必须发 <strong>注释帧</strong>（不是业务事件）。
   *
   * <p>造法：假模型第一块之前停 700ms（&gt; 心跳间隔 300ms 且 &lt; 闲置超时 1200ms）， 那段时间里既没有 delta 也没有 done ——
   * 唯一能被观测到的"还活着"的证据就是注释帧。
   *
   * <p>★ 同时断言它<strong>没有变成业务事件</strong>：心跳若发成 {@code event: heartbeat}，
   * 前端会多一个不认识的分支（不报错），而"心跳到底发没发"就不再能被同一套断言读到。
   */
  @Test
  void heartbeatIsAnSseCommentFrameNotABusinessEvent() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.streaming(700, 40, 12));

    Response response = chat(login(), body(fixture.appId(), null, "差旅住宿标准"));

    assertThat(response.heartbeats())
        .as("★★ 700ms 的无业务帧间隙里，按 300ms 的心跳间隔至少该有 1 条注释帧（契约①）")
        .isGreaterThanOrEqualTo(1);
    assertThat(response.raw())
        .as("★ 心跳的 wire 形状是注释帧（一整行以 : 开头），载荷是 keep-alive")
        .contains(": " + ChatStreamWriter.HEARTBEAT_PAYLOAD);
    assertThat(response.events())
        .as("★ 心跳不是业务事件：它不得出现在 event: 行里")
        .doesNotContain("keep-alive")
        .doesNotContain("heartbeat");
    assertThat(response.events()).startsWith("meta").endsWith("done");
  }

  // ────────────────────────── ② 停止路径（ABORT）──────────────────────────

  /**
   * ★★ 判据②（停止）：{@code ABORT ⇒ done(STOPPED)}，且<strong>不发 error</strong>、<strong>保留已生成内容</strong>。
   *
   * <p>造法：假模型吐得很慢（600 字 / 每块 120ms ≈ 6 秒），HTTP 请求跑在<strong>另一条线程</strong>上；
   * 主线程<strong>轮询数据库</strong>等到那条助手消息<strong>已经有正文</strong>（{@link
   * AssistantGeneratedTextWait#await}），再置上显式停止信号。
   *
   * <p>★★ <strong>观测点必须是"正文非空"，不能是"状态是 GENERATING"</strong>（{@code #125} 的修法）： 助手行在<strong>第一个
   * delta 之前</strong>就落库（{@code A2} 要的形状），所以"状态出现"发生在"生成开始"之<strong>前</strong> ——
   * 等状态会造出一个"停止信号先于第一个 chunk"的窗口，那时 {@code A5} 的正文必然为空。 它是 ~50% 偶发（实测 4
   * 次运行绿/红/红/绿）而不是恒红，因为窗口里谁先到取决于两条线程的调度； 本类 {@code stopSignalEndsAsStoppedAndKeepsTheGeneratedText}
   * 那一条的反证（{@code #125}）就是把等待条件换回状态。
   *
   * <p>★★ 为什么用"轮询数据库"而不是"从响应的流里读 {@code meta} 拿 messageId"（本批实测踩到过）：
   * 本机实测<strong>客户端在整条流结束之前读不到任何一帧</strong>（容器把响应头攒到响应结束才提交）， 于是"边读边停"这种写法要等到服务端已经写完才去置停止信号 ——
   * 那时停止已经无从生效。 而助手消息<strong>先于第一个字节就落库</strong>（这正是 {@code A2} 要的形状）， 所以从库里拿 messageId
   * 是<strong>更早、也更确定</strong>的观测点。
   *
   * <p>★★ <strong>为什么用"显式停止信号"而不是"掐断连接"</strong>：契约里 {@code ABORT} 与 {@code DISCONNECT}
   * 是<strong>两条</strong>路径、终态不同（{@code STOPPED} vs {@code FAILED}），而前端这一轮的 {@code abort()} 实现是"掐断
   * fetch" —— 服务端只看得到连接没了，分不出这两件事。 若把断开当停止，真正的断网会被显示成"已停止"（用户以为是自己停的，而答案其实没写完）。
   */
  @Test
  void stopSignalEndsAsStoppedAndKeepsTheGeneratedText() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.longAnswerStreaming(120, 12));
    String token = login();
    // ★ 本用例显式建一个会话：等待的那把（全仓唯一，见 AssistantGeneratedTextWait）按**会话**定位 ——
    //   "这个会话里那条助手消息的正文非空"才是这条用例真正依赖的观测点
    UUID conversationId = seedConversation(ADMIN_ID, "停止夹具会话");
    String requestBody = body(fixture.appId(), conversationId, "差旅住宿标准");

    Response response;
    String messageId;
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var running = pool.submit(() -> chat(token, requestBody));
      // ★★ 等的是**这条用例真正依赖的东西**：库里那条助手消息**已经有正文**（= 已经收到过至少 1 个 chunk）。
      //   ★ 不许改成等状态 GENERATING（#125）：助手行在**第一个 delta 之前**就落库（A2 要的形状），
      //     于是"看到 GENERATING"与"模型已经吐过第一个字"是两件事 —— 拿前者当后者，停止信号就可能
      //     赶在 Run#onChunk 第一次跑之前置上 ⇒ 一个 chunk 都没进装配器 ⇒ 终态 STOPPED 且正文是空串
      //     ⇒ 下面那条 A5「停止保留已生成内容」必然红（同一批代码实测 4 次运行绿/红/红/绿）。
      //   ★ 等待的实现在全仓只有一处（AssistantGeneratedTextWait）：两处各写一把时，下一个人就会照着抄
      //     第三把 —— #119 的新用例正是这么把 #125 的老洞原样抄了一遍，所以它现在由
      //     GeneratedTextWaitSingleSourceTest 守着。
      //   ★ 它按会话定位、返回那个 id 的 UUID 形式；本类那几处库读数用的是文本形式的 id，于是这里收成字符串
      messageId = AssistantGeneratedTextWait.await(jdbc, conversationId, 10_000).toString();
      requestStop(messageId);
      response = running.get(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    List<String> events = response.events();
    assertThat(events.stream().filter("done"::equals).count())
        .as("★ 判据②：停止路径也必须<strong>恰好一次</strong> done")
        .isEqualTo(1);
    assertThat(events).as("★ 契约：ABORT 不发 error（停止是一次正常的用户操作，不是失败）").doesNotContain("error");
    assertThat(response.dataOf("done"))
        .as("★ 终态是 STOPPED（一个一等终态，不是 FAILED 的变体）")
        .contains("\"status\":\"STOPPED\"");

    assertThat(statusInDb(messageId)).as("★ A5：库里那条是 STOPPED").isEqualTo("STOPPED");
    String content = contentInDb(messageId);
    assertThat(content)
        .as("★★ A5：停止<strong>保留已生成内容</strong>（清空的话用户看到的是一条空白回答，重生成也失去起点）")
        .isNotEmpty()
        .isNotEqualTo(FakeChatStreamPorts.LONG_ANSWER);
    assertThat(FakeChatStreamPorts.LONG_ANSWER)
        .as("★ 它确实是答案的前缀 —— 即停止发生在写完之前（否则这条断言在'其实写完了'时也会绿）")
        .startsWith(content);
  }

  // ────────────────────────── ③ 闲置超时（IDLE_TIMEOUT）──────────────────────────

  /**
   * ★★ 判据②（超时）：{@code IDLE_TIMEOUT ⇒ error + done(FAILED)}，且 error 之后<strong>仍然</strong>走 done。
   *
   * <p>造法：假模型一块都不吐（睡足 3000ms &gt; 闲置超时 1200ms）⇒ 收尾线程判超时。 此时连接<strong>还活着</strong>，所以 error 与 done
   * 两帧客户端都收得到 —— 这正是"超时"与"断开"的读数差别。
   */
  @Test
  void idleTimeoutEmitsErrorThenDoneAndPersistsFailed() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.streaming(3000, 0, 12));

    Response response = chat(login(), body(fixture.appId(), null, "差旅住宿标准"));

    List<String> events = response.events();
    assertThat(events).as("★ 超时路径：先 error 收原因，再 done 收终态").contains("error").contains("done");
    assertThat(events.indexOf("error"))
        .as("★ error 不是终态 —— 它必须排在 done 之前（契约原文：error 之后仍必须跟 done）")
        .isLessThan(events.indexOf("done"));
    assertThat(events.stream().filter("done"::equals).count())
        .as("★ 判据②：超时路径也恰好一次 done")
        .isEqualTo(1);
    assertThat(response.dataOf("done"))
        .as("★ 超时的终态是 FAILED（四条路径 → 三态的唯一映射）")
        .contains("\"status\":\"FAILED\"");
    assertThat(response.dataOf("error"))
        .as("★ 失败帧要给出可读原因与下一步（否则界面上只有一个空白提示）")
        .contains("\"reason\"")
        .contains("\"hint\"");

    String messageId = stringField(response.dataOf("meta"), "messageId");
    assertThat(statusInDb(messageId)).as("★ 库里那条必须是 FAILED").isEqualTo("FAILED");
    assertThat(failReasonInDb(messageId)).as("★ FAILED 必填原因（库层也有 CHECK 兜一道）").isNotBlank();
  }

  // ────────────────────────── ④ 连接断开（DISCONNECT）──────────────────────────

  /**
   * ★★ 判据②（断开）：<strong>写连接失败</strong> ⇒ 库里那条是 {@code FAILED}，且已经生成的正文不丢。
   *
   * <p>★★ <strong>为什么这条用例不断言"从响应流里读到 error 帧 + done 帧"</strong>（如实记）：
   * 连接是<strong>写不出去的</strong>，之后服务端写什么都到不了客户端 —— 那两帧在 wire 上不存在，
   * 任何"从响应流里读帧"的断言都只能读到断点之前的部分。所以这一条读的是：
   *
   * <ol>
   *   <li><strong>库里的终态</strong>（{@code FAILED} + 原因）—— 那才是"服务端确实收尾了"的证据；
   *   <li>库里的<strong>正文不是被清掉的</strong>（断开不清空）；
   *   <li>★ 服务端<strong>确实尝试过</strong>把 {@code error} 与 {@code done} 写出去（见 {@link #attemptedFrames}
   *       —— "写失败"只让那一次写失败，不代表服务端不再收尾）。
   * </ol>
   *
   * <p>★ 那两帧的顺序与"恰好一次"由 {@code ChatStreamEventRecordingTest} 用同一个 {@code ChatService}
   * 读（接管事件出口）：HTTP 层与事件层各读一半，合起来才是完整读数。
   *
   * <h3>★★ 造法：依赖替换 —— 注入一次写失败（而不是"掐客户端连接"）</h3>
   *
   * <p>★ 本批此前<strong>三次</strong>尝试在网络层制造 {@code DISCONNECT} 都读到 {@code GENERATED}（如实记在下面的"历史"里）。
   * 根因不是服务端缺分支，而是<strong>缺一个注射点</strong>：客户端 RST 之后，服务端那次写很可能<strong>并没有失败</strong> （容器缓冲吞得下），于是
   * {@code IOException} 从未发生。
   *
   * <p>⇒ 现在改成<strong>依赖替换</strong>：{@link WriteFailingChatService} 在测试里 {@code @Primary} 覆盖 {@code
   * ChatService}，把控制器给出的出口<strong>包一层</strong> —— 写满 {@link #FAIL_AFTER_FRAMES} 帧之后 抛一次 {@link
   * java.io.UncheckedIOException}。于是：
   *
   * <ul>
   *   <li><strong>生产代码一行没改</strong>（没有 {@code if (testMode)}）：写失败这件事经由既有那条 {@code EventSink →
   *       UncheckedIOException → DISCONNECT} 的路径发生；
   *   <li>它落到真库上（本类是真 Testcontainers）：断言读的是 {@code select status from cv_message}。
   * </ul>
   *
   * <p>★ 本批的三次网络层尝试（保留在案，免得下一个人再走一遍）：① {@code HttpClient} + 关闭响应体； ② 裸 socket + {@code SO_LINGER=0}
   * 发 RST；③ 先轮询库到 {@code GENERATING} 再 RST —— 三次都读到 {@code GENERATED}。
   *
   * <p>★ <strong>本条可证伪</strong>：把注入去掉（{@code failAfterFrames} 设成够不着的大值），终态就会是 {@code
   * GENERATED}，本用例判红（本批实测做过这一次反证）。
   */
  @Test
  void clientDisconnectPersistsFailedWithoutLosingWhatWasGenerated() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.longAnswerStreaming(30, 6));
    resetWriteFailureInjection();

    // ★ 注入：服务端写第 FAIL_AFTER_FRAMES + 1 帧时失败（"客户端那条连接写不出去了"）
    failAfterFrames.set(FAIL_AFTER_FRAMES);
    String messageId;
    Response response;
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var running = pool.submit(() -> chat(login(), body(fixture.appId(), null, "差旅住宿标准")));
      messageId = waitForAssistantInStatus("GENERATING", 10_000);
      assertThat(writeFailureFired.await(10, java.util.concurrent.TimeUnit.SECONDS))
          .as("★★ 前置：注入的那一次写失败真的发生了（没发生的话下面每一条都在验一条没被触发的路径）")
          .isTrue();
      response = running.get(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ★★ 判据②：先等收尾落到库里，再读它（收尾在服务端另一条线上）
    waitForStatus(messageId, "FAILED", 10_000);
    assertThat(statusInDb(messageId))
        .as("★★ 写失败 ⇒ FAILED（不是 GENERATED：写失败与「生成完了」必须可分）")
        .isEqualTo("FAILED");
    assertThat(failReasonInDb(messageId))
        .as("★ 写失败这一路没有上游 detail ⇒ 库里记「连接中断」（与 error 帧同源）")
        .isEqualTo("连接中断");
    assertThat(contentInDb(messageId)).as("★★ 断开不是清空：库里仍是断开那一刻已经生成的那一段（不能是被清掉的）").isNotEmpty();
    assertThat(FakeChatStreamPorts.LONG_ANSWER)
        .as("★ 它确实是答案的前缀 —— 即断开发生在写完之前（否则这条断言在「其实写完了」时也会绿）")
        .startsWith(contentInDb(messageId));

    // ★ 服务端确实尝试过终态那两帧（它们到不了客户端，但"有没有尝试写"是可读的）
    List<String> attempted = attemptedFrames();
    assertThat(attempted)
        .as("★★ 写失败之后服务端仍然收尾：error 与 done 都被尝试写出去了（顺序：error 在前）")
        .containsSubsequence("error", "done");
    assertThat(attempted.stream().filter("done"::equals).count())
        .as("★ 尝试写出去的 done 恰好一条")
        .isEqualTo(1L);
    assertThat(response.status()).as("★ 响应码仍是 200（流已经开始了，终态在流里，不是 HTTP 错误）").isEqualTo(200);
  }

  /** 从已经打开的读端读到某个事件名为止（含它）。 */
  private static List<Frame> readFramesUntilEvent(
      BufferedReader reader, String event, Duration timeout) throws IOException {
    List<Frame> frames = new ArrayList<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    StringBuilder frame = new StringBuilder();
    String line;
    while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
      if (line.isEmpty()) {
        if (!isOpenPadding(frame.toString())) {
          Frame parsed = parseFrame(frame.toString());
          if (parsed != null) {
            frames.add(parsed);
            if (event.equals(parsed.event())) {
              return frames;
            }
          }
        }
        frame.setLength(0);
        continue;
      }
      frame.append(line).append('\n');
    }
    throw new AssertionError("在 " + timeout.toMillis() + "ms 内没有读到 event: " + event);
  }

  // ────────────────────────── ⑤ 越权（404 而不是 403）──────────────────────────

  /**
   * ★★ 判据⑤：别人的会话 ⇒ <strong>404</strong>（不是 403）。
   *
   * <p>★ 403 等于告诉对方"这个会话 id 是存在的"——那是一次免费的探测（{@code 07 §3.3} 纪律 2）。
   * 所以"不在我的可见范围"与"根本不存在"必须走<strong>同一个</strong>出口、同一段文案。
   */
  @Test
  void someoneElsesConversationIsNotFoundNotForbidden() throws Exception {
    seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.streaming(0, 60, 12));
    UUID foreign = seedConversation(SOMEBODY_ELSE, "别人的会话");

    HttpResponse<String> response = postChat(login(), body(null, foreign, "差旅住宿标准"));

    assertThat(response.statusCode())
        .as("★★ 判据⑤：越权访问别人的会话必须返 404 —— 403 会确认这个 id 存在")
        .isEqualTo(404);
    assertThat(response.body()).as("★ 包络里的码与 HTTP 码同值（08 §4.1）").contains("\"code\":404");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cv_message where conversation_id = ?::uuid",
                Integer.class,
                foreign))
        .as("★★ 越权被拒时<strong>一条消息都不许落库</strong> —— 否则'看不见'的会话里会多出别人的内容")
        .isZero();
  }

  // ────────────────────────── HTTP 与解析工具 ──────────────────────────

  /** 一帧 SSE。{@code openPadding} 区分"开流填充帧"与"心跳"——两者都是注释帧，但语义不同。 */
  private record Frame(String event, String data, boolean heartbeat, boolean openPadding) {}

  /** 一次流式请求的读数。 */
  private record Response(int status, String contentType, List<Frame> frames) {

    List<String> events() {
      return frames.stream().filter(frame -> !frame.heartbeat()).map(Frame::event).toList();
    }

    /** ★ 真·心跳条数 —— 不含开流那条填充注释帧（它证明的是"响应头提交了"，不是"连接还活着"）。 */
    long heartbeats() {
      return frames.stream().filter(frame -> frame.heartbeat() && !frame.openPadding()).count();
    }

    /** 开流填充注释帧的条数（恒为 1；写成计数是为了"它真的发过"这条可断言）。 */
    long openPaddingFrames() {
      return frames.stream().filter(Frame::openPadding).count();
    }

    /** 某个事件名的<strong>第一条</strong> data（done 只有一条；其余本类只取第一条）。 */
    String dataOf(String event) {
      return frames.stream()
          .filter(frame -> event.equals(frame.event()))
          .map(Frame::data)
          .findFirst()
          .orElseThrow(() -> new AssertionError("响应里没有 event: " + event + " 的帧"));
    }

    /** 只把"帧名"拼出来（用于断言心跳的 wire 形状时对照）。 */
    String raw() {
      StringBuilder text = new StringBuilder();
      for (Frame frame : frames) {
        text.append(frame.heartbeat() ? ": keep-alive" : "event: " + frame.event()).append('\n');
      }
      return text.toString();
    }
  }

  private Response chat(String token, String body) throws Exception {
    List<Frame> frames;
    try (InputStream stream = openChatStream(token, body)) {
      frames = readFrames(stream, Integer.MAX_VALUE);
    }
    return new Response(200, "text/event-stream", frames);
  }

  private InputStream openChatStream(String token, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/conversations/chat"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response =
        http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      throw new AssertionError("SSE 端点返回了 " + response.statusCode() + "（期望 200）");
    }
    return response.body();
  }

  private HttpResponse<String> postChat(String token, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/conversations/chat"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * 读 SSE 流到结尾（或读满 {@code maxFrames} 帧）。
   *
   * <p>★ 手写解析而不是引一个 SSE 库：本类的判据之一就是"帧的 wire 形状" （{@code event:} 行 + {@code data:} 行 + 空行收尾、心跳是
   * {@code :} 开头的注释行）—— 用一个"帮你解析好"的库，那一条就没人验了。
   *
   * <p>★ 调用方用 {@code try-with-resources} 包住流：读满即关 = 客户端主动断开 （{@code DISCONNECT} 用例就靠这一行为）。
   */
  private static List<Frame> readFrames(InputStream stream, int maxFrames) throws IOException {
    List<Frame> frames = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    StringBuilder frame = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty()) {
        if (isOpenPadding(frame.toString())) {
          frame.setLength(0);
          continue;
        }
        Frame parsed = parseFrame(frame.toString());
        frame.setLength(0);
        if (parsed != null) {
          frames.add(parsed);
          if (frames.size() >= maxFrames) {
            return frames;
          }
        }
        continue;
      }
      frame.append(line).append('\n');
    }
    if (!isOpenPadding(frame.toString())) {
      Frame parsed = parseFrame(frame.toString());
      if (parsed != null) {
        frames.add(parsed);
      }
    }
    return frames;
  }

  /**
   * 一个 SSE 块的文本 ⇒ 一帧（{@code event:} / {@code data:} / 注释行）。
   *
   * @return {@code null} = 这个块里既没有 data 也没有注释（不能算一帧）
   */
  private static Frame parseFrame(String block) {
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
    if (comment == null && data == null) {
      return null;
    }
    return new Frame(event, data == null ? "" : data, comment != null, false);
  }

  /** 服务端尝试写出去的事件名（副本；见 {@link #attemptedFrames} 那条字段的说明）。 */
  private List<String> attemptedFrames() {
    synchronized (attemptedFrames) {
      return List.copyOf(attemptedFrames);
    }
  }

  /**
   * 关掉写失败注入并清空上一次的读数。
   *
   * <p>★ 每个用例开头与 {@code @AfterEach} 各调一次：注入是一个<strong>进程级</strong>开关（bean 是单例）， 漏掉重置会让下一条用例莫名其妙地读到
   * {@code FAILED}。
   */
  private void resetWriteFailureInjection() {
    failAfterFrames.set(0);
    synchronized (attemptedFrames) {
      attemptedFrames.clear();
    }
    while (writeFailureFired.getCount() > 0) {
      writeFailureFired.countDown();
    }
  }

  /**
   * 把"写失败"注入链路 —— ★ 依赖替换：{@code @Primary} 覆盖生产那个 {@code ChatService}。
   *
   * <h3>★★ 为什么这样开测试出口（而不是在生产里加开关）</h3>
   *
   * <p>写失败这件事在链路上<strong>本来就有一条路径</strong>：控制器的 {@code EventSink} 把 {@code IOException} 包成 {@link
   * UncheckedIOException}，{@code ChatService} 按它判 {@code DISCONNECT}。缺的从来不是分支，而是
   * <strong>一个能真的触发它的出口</strong>。
   *
   * <p>⇒ 本类在<strong>测试层</strong>替换 {@code ChatService}：{@link WriteFailingChatService} 把控制器给出的
   * 出口包一层，写满 N 帧之后抛一次那个异常 —— 而<strong>生产代码一行没改</strong>（没有 {@code if (testMode)}、
   * 没有测试专用分支，判据也没改弱）。其余用例（{@code failAfterFrames == 0}）走的就是纯粹的生产路径。
   */
  @TestConfiguration
  static class WriteFailureConfig {

    @Bean
    AtomicInteger failAfterFrames() {
      return new AtomicInteger();
    }

    @Bean
    CountDownLatch writeFailureFired() {
      return new CountDownLatch(1);
    }

    @Bean
    List<String> attemptedFrames() {
      return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * 覆盖生产的 {@code ChatService}：收尾那一侧<strong>逐字继承</strong>生产实现，只重写"进去的那条路" （把事件出口包一层）。
     *
     * <p>★ 依赖显式传入并原样交给 {@code super}：本类<strong>不</strong>造第二份编排， 而是站在生产那一份之上拦一次。
     */
    @Bean
    @Primary
    WriteFailingChatService writeFailingChatService(
        ChatTurnStore turns,
        SegmentSearchPort segmentSearch,
        AccessScopePort accessScope,
        ChatStreamPort chatStream,
        ChatStopSignal stopSignal,
        ChatStreamProperties properties,
        AtomicInteger failAfterFrames,
        CountDownLatch writeFailureFired,
        List<String> attemptedFrames) {
      return new WriteFailingChatService(
          turns,
          segmentSearch,
          accessScope,
          chatStream,
          stopSignal,
          properties,
          failAfterFrames,
          writeFailureFired,
          attemptedFrames);
    }
  }

  /**
   * 只做一件事的 {@code ChatService} 子类：把事件出口包成"写满 N 帧后断一次"，并记下服务端尝试写的帧名。
   *
   * <p>★ 它与生产实现的差别<strong>只有</strong>下面那个重写的方法 —— 落库、线程、心跳、四路径判定全部继承。
   */
  static final class WriteFailingChatService extends ChatService {

    private final AtomicInteger failAfterFrames;
    private final CountDownLatch writeFailureFired;
    private final List<String> attemptedFrames;

    WriteFailingChatService(
        ChatTurnStore turns,
        SegmentSearchPort segmentSearch,
        AccessScopePort accessScope,
        ChatStreamPort chatStream,
        ChatStopSignal stopSignal,
        ChatStreamProperties properties,
        AtomicInteger failAfterFrames,
        CountDownLatch writeFailureFired,
        List<String> attemptedFrames) {
      super(turns, segmentSearch, accessScope, chatStream, stopSignal, properties);
      this.failAfterFrames = failAfterFrames;
      this.writeFailureFired = writeFailureFired;
      this.attemptedFrames = attemptedFrames;
    }

    @Override
    public void streamTurn(
        ChatTurnWriter.PreparedTurn turn, String question, ChatService.EventSink sink) {
      int failAfter = failAfterFrames.get();
      AtomicBoolean broken = new AtomicBoolean(false);
      super.streamTurn(
          turn,
          question,
          event -> {
            attemptedFrames.add(event.kind().wireName());
            if (failAfter > 0 && !broken.get() && attemptedFrames.size() > failAfter) {
              broken.set(true);
              writeFailureFired.countDown();
              throw ChatStreamWriter.disconnected(new IOException("测试注入：写连接失败"));
            }
            sink.onEvent(event);
          });
    }
  }

  private static String messageIdOf(List<Frame> frames) {
    return frames.stream()
        .filter(frame -> "meta".equals(frame.event()))
        .map(frame -> stringField(frame.data(), "messageId"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("流里没有 meta 帧 ⇒ 拿不到 messageId"));
  }

  // ────────────────────────── 库读数 ──────────────────────────

  private String statusInDb(String messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, messageId);
  }

  private String contentInDb(String messageId) {
    return jdbc.queryForObject(
        "select content from cv_message where id = ?::uuid", String.class, messageId);
  }

  private String failReasonInDb(String messageId) {
    return jdbc.queryForObject(
        "select fail_reason from cv_message where id = ?::uuid", String.class, messageId);
  }

  private String refsInDb(String messageId) {
    return jdbc.queryForObject(
        "select reference_snapshot::text from cv_message where id = ?::uuid",
        String.class,
        messageId);
  }

  /**
   * 某条分段所属的文档 id（{@code #91} 的深链载体）。
   *
   * <p>★ 为什么从库里查而不是让夹具返回：本类要验的正是"装配点有没有把**真实的行**接上去" —— 夹具自己记一个期望值，等于让被验的那条读数由夹具出题（它可能记错）。
   */
  private String documentIdInDb(UUID segmentId) {
    return jdbc.queryForObject(
        "select document_id::text from kb_segment where id = ?", String.class, segmentId);
  }

  /**
   * 某条分段在库里的密级 rank（{@code V22} 的生成列）。
   *
   * <p>★ 为什么从库里核而不是从 {@code refs} 载荷里核：{@code SourceRef}（契约 {@code stream-types.ts:105-120} 冻结的 10
   * 个字段）里<strong>没有</strong> classificationRank 这一类字段 —— 密级是检索 pre-filter 的判据，
   * 不进引用载荷。所以"密级与预期一致"只能这样读： 库里那两列（密级字符串与生成列 rank）彼此同口径。
   */
  private int classificationRankInDb(UUID segmentId) {
    return jdbc.queryForObject(
        "select classification_rank from kb_segment where id = ?", Integer.class, segmentId);
  }

  /**
   * 给 {@code ChatService} 那个 logger 挂一个内存追加器（读完必须 {@link #releaseLogCapture}）。
   *
   * <p>★ 为什么需要它：本类要断言的"跳过 N 条"是<strong>日志</strong>里的读数 —— 它没有别的载体（不落库、不进事件）， 而"降级不许静默"这条判据不验就等于没做。
   */
  private static ListAppender<ILoggingEvent> captureChatServiceLog() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ChatService.class)).addAppender(appender);
    return appender;
  }

  /** 摘掉上面那个追加器（★ 不摘会把本用例的日志漏给下一条用例，而那种漏读起来像"别处也在跳过"）。 */
  private static void releaseLogCapture(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ChatService.class)).detachAppender(appender);
    appender.stop();
  }

  /** 那个追加器收到的日志正文（已经过占位符格式化）。 */
  private static List<String> logMessagesOf(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private Integer tokensInDb(String messageId) {
    return jdbc.queryForObject(
        "select prompt_tokens + completion_tokens from cv_message where id = ?::uuid",
        Integer.class,
        messageId);
  }

  /**
   * 等"那条助手消息"以某个状态出现在库里，并返回它的 id。
   *
   * <p>★ 为什么这条路子比"从响应的流里读 {@code meta}"可靠（本批实测踩到过）： 本机（Tomcat 11 + 真
   * HTTP）实测<strong>客户端在整条流结束之前读不到任何一帧</strong>， 于是"边读边干预"（停止 / 断开）这种写法总要等到服务端写完才轮到自己 —— 那时干预已经无效。
   * 而助手消息<strong>先于第一个字节就落库</strong>（这正是 {@code A2} 要的形状）⇒ "库里出现
   * GENERATING"是"这一轮真的在生成"的<strong>确定观测点</strong>。
   *
   * <p>★★ <strong>它只回答"那一行在不在"，不回答"生成有没有开始"</strong>（{@code #125}）：助手行在第一个 {@code delta} 之前就落库，状态也是
   * {@code GENERATING}、正文还是空串。 ⇒ <strong>凡是要断言"已经生成过内容"的用例，必须用 {@link
   * AssistantGeneratedTextWait#await}</strong>；等状态只够用来拿 messageId（例如 {@code DISCONNECT} 那条，
   * 它的中断是"写满 N 帧"注入的、与这个窗口无关）。
   *
   * <p>★ 等待有上限，超时即断言失败 —— 它不会把"服务端没收尾"这个缺陷藏起来。
   */
  private String waitForAssistantInStatus(String status, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
    while (System.nanoTime() < deadline) {
      List<String> found =
          jdbc.queryForList(
              "select m.id::text from cv_message m join cv_conversation c on c.id ="
                  + " m.conversation_id where c.user_id = ?::uuid and m.role = 'ASSISTANT' and"
                  + " m.status = ? order by m.created_at desc limit 1",
              String.class,
              ADMIN_ID,
              status);
      if (!found.isEmpty()) {
        return found.getFirst();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("在 " + timeoutMs + "ms 内没有等到状态为 " + status + " 的助手消息");
  }

  /**
   * 等某条消息落到某个终态。
   *
   * <p>★ 为什么必须等：{@code DISCONNECT} 的收尾发生在<strong>服务端的另一条线</strong>上， 而客户端在掐断连接的那一刻就可以返回了 ——
   * 不等一下就会读到 {@code GENERATING}。
   */
  private void waitForStatus(String messageId, String status, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
    while (System.nanoTime() < deadline && !status.equals(statusInDb(messageId))) {
      Thread.sleep(20);
    }
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(UUID appId, UUID kbId, UUID segmentId) {}

  private String base() {
    return "http://localhost:" + port;
  }

  private String login() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("种子账号必须能登录（否则后面每一条都读不到真实鉴权链）").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private String body(UUID appId, UUID conversationId, String question) {
    StringBuilder json = new StringBuilder("{");
    if (appId != null) {
      json.append("\"appId\":\"").append(appId).append("\",");
    }
    if (conversationId != null) {
      json.append("\"conversationId\":\"").append(conversationId).append("\",");
    }
    json.append("\"question\":\"").append(question).append("\",");
    // ★ 契约里前端会传这两个字段（lib/sse.ts 的 body）—— 后端收下但不消费
    json.append("\"attachmentIds\":[],\"thinking\":false}");
    return json.toString();
  }

  /**
   * 建模型 → 库 → 应用 → 文档 → 分段（分段正文含"差旅住宿标准"，全文检索能命中）。
   *
   * <p>★ 夹具的分段<strong>必须带 title</strong>：库里那一列可空（{@code V15}），而 {@code SourceRef} 把 {@code title}
   * 当必填 —— 夹具一旦让它为 NULL，引用就会<strong>整批</strong>装不成（{@code #123} 那条路径），
   * 于是本类所有"引用"读数都会安静地退化成"零引用"（这正是第 17 周那个 P0 能长期没被发现的形状）。
   */
  private Fixture seedWithVisibleSegment() {
    UUID chatModel = seedChatModel();
    UUID embeddingModel = seedEmbeddingModel();
    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, 'SSE 夹具库', '制度规范', 'LOCAL', ?, 'INTERNAL', ?, 'PRIVATE',"
            + " now(), now())",
        kbId,
        embeddingModel,
        ADMIN_ID);
    UUID appId = UUID.randomUUID();
    jdbc.update(
        "insert into as_assistant (id, name, type, model_id, owner_id, visibility, published,"
            + " created_at, updated_at) values (?, 'SSE 夹具应用', 'SIMPLE', ?, ?, 'PRIVATE',"
            + " true, now(), now())",
        appId,
        chatModel,
        ADMIN_ID);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);
    seededApps.add(appId);
    UUID segmentId = seedDocumentAndSegment(kbId, "SSE 差旅制度文档", "差旅住宿标准章节", "差旅住宿标准为每人每天500元");
    return new Fixture(appId, kbId, segmentId);
  }

  private UUID seedChatModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, 'SSE 夹具对话模型', 'CHAT', 'LOCAL_INFERENCE',"
            + " 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        id);
    return id;
  }

  private UUID seedEmbeddingModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at) values (?, 'SSE 夹具向量模型', 'EMBEDDING',"
            + " 'LOCAL_INFERENCE', 'bge-m3', 'http://localhost:11434/v1', 768, 'LOADED', true,"
            + " now(), now())",
        id);
    return id;
  }

  /**
   * 造一条文档 + 一条分段（{@code ordinal = 0}）。
   *
   * @param title 分段标题 —— ★ <strong>允许为 {@code null}</strong>：库里那一列可空（{@code V15}）， "title
   *     为空的分段"是真实数据的形状，{@code #123} 判据①要造的就是它（而 {@code SourceRef} 把 title 当必填 ⇒ 这一条装不成、必须被逐条摘掉）
   */
  private UUID seedDocumentAndSegment(UUID kbId, String docName, String title, String text) {
    UUID docId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_document (id, kb_id, scope_kind, scope_id, name, source, doc_type, org_id,"
            + " classification, status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?, ?, 'KB', ?, ?, 'local', '制度', ?::uuid, 'INTERNAL', 'SUCCEEDED', 0, 1,"
            + " true, now(), now())",
        docId,
        kbId,
        kbId,
        docName,
        ROOT_ORG);
    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " content, search_text, content_hash, char_count, enabled, embedding_status,"
            + " created_at, updated_at, classification) values (?, ?, ?, 'KB', ?, 0, ?, ?, ?, ?, ?,"
            + " true, 'PENDING_EMBEDDING', now(), now(), (select classification from kb_document"
            + " where id = ?))",
        segmentId,
        docId,
        kbId,
        kbId,
        title,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
    return segmentId;
  }

  /** 直接建一个属于某个用户的会话（越权用例用）。 */
  private UUID seedConversation(UUID userId, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, created_at, updated_at)"
            + " values (?, ?, ?, now(), now())",
        id,
        userId,
        title);
    return id;
  }

  /** 从 JSON 里取一个顶层字符串字段（与 {@code SecurityChainTest} 同一手法）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  /** 置上显式停止信号（用 {@code meta} 里的 messageId）。 */
  private void requestStop(String messageId) {
    stopSignal.request(UUID.fromString(messageId));
  }

  // ────────────────────────── 假模型的装配 ──────────────────────────

  /**
   * 把假模型装进容器。
   *
   * <p>★ 为什么是"一个可替换的持有者"而不是"每个用例一个 {@code @TestConfiguration}"： 后者每种配置都会让 Spring
   * 的测试上下文缓存<strong>各起一份</strong>（连带各起一个 Testcontainers
   * PostgreSQL），而测试的确定性并不来自"容器不同"，只来自"假模型不同"。一个可替换的分发器让上下文被复用， 同时每个用例仍然能换自己的假模型。
   */
  @TestConfiguration
  static class FakeModelConfig {

    @Bean
    AtomicReference<ChatStreamPort> fakeChatStreamPortHolder() {
      return new AtomicReference<>();
    }

    /**
     * 真正被装配进 {@code ChatService} 的那一个：有假模型就用假的，没有就用真实的兜底实现。
     *
     * <p>★ {@code @Primary} 覆盖 {@code ChatPortFallbackStreamAdapter} 的
     * {@code @ConditionalOnMissingBean} —— 于是"测试用假的、生产用真的"由容器装配决定， 业务代码里没有一行 if。
     */
    @Bean
    @Primary
    SwappableChatStreamPort swappableChatStreamPort(
        AtomicReference<ChatStreamPort> holder, ObjectProvider<ChatStreamPort> allPorts) {
      return new SwappableChatStreamPort(holder, allPorts);
    }
  }

  /**
   * 可替换的分发器（★ 具名类而不是 lambda：兜底实现要从"所有 {@code ChatStreamPort} bean"里挑出来，
   * 而那个集合里<strong>包含它自己</strong> —— 按具名类型排除掉，否则就是无限递归）。
   */
  static final class SwappableChatStreamPort implements ChatStreamPort {

    private final AtomicReference<ChatStreamPort> holder;
    private final ObjectProvider<ChatStreamPort> allPorts;

    SwappableChatStreamPort(
        AtomicReference<ChatStreamPort> holder, ObjectProvider<ChatStreamPort> allPorts) {
      this.holder = holder;
      this.allPorts = allPorts;
    }

    @Override
    public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
      ChatStreamPort port = holder.get();
      if (port == null) {
        port =
            allPorts.stream()
                .filter(candidate -> !(candidate instanceof SwappableChatStreamPort))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "测试既没装假模型，兜底实现也不在容器里（ChatPortFallbackStreamAdapter 没被装配）"));
      }
      port.stream(modelId, prompt, timeout, callback);
    }
  }
}
