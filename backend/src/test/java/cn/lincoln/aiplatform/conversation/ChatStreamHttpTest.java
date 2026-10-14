package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatStopSignal;
import cn.lincoln.aiplatform.conversation.application.ChatStreamWriter;
import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * 20 秒"。 契约常量本身由 {@code ChatStreamTimingTest} 单独断言。
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

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ChatStopSignal stopSignal;

  /** 每个用例自己装一个假模型（见 {@link FakeChatStreamPorts}）；{@code null} = 用兜底实现。 */
  @Autowired AtomicReference<ChatStreamPort> fakeModel;

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
                + "★ 元素级字段名（index/title/docName/kbName/score/segmentId）不在本类断言："
                + "本类夹具在真栈上全文检索零命中（见交付报告的'未完成'），那 10 个字段名由既有的"
                + " SourceRefSerializationTest / MessageContractCrossCheckTest 逐字钉住（本类不重复一份）")
        .startsWith("{\"refs\":[")
        .endsWith("]}");
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
    assertThat(refsInDb(messageId))
        .as("★ A4 的载体：引用快照随消息落库（本类夹具全文检索零命中 ⇒ 是明确的空数组，" + "而不是 NULL —— '零命中'与'引用从没到过'是两件事）")
        .isEqualTo("[]");
    assertThat(tokensInDb(messageId))
        .as("★ tokens 落库（本批假模型不报用量 ⇒ 两列都是 0，而不是 NULL —— 调用确实发生过）")
        .isNotNull();
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
   * 主线程<strong>轮询数据库</strong>等到那条助手消息以 {@code GENERATING} 出现，再置上显式停止信号。
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
    String requestBody = body(fixture.appId(), null, "差旅住宿标准");

    Response response;
    String messageId;
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var running = pool.submit(() -> chat(token, requestBody));
      // ★ 等它在库里出现（生成中）—— 这是"还在生成"的确定观测点，与能不能从流里读到帧无关
      messageId = waitForAssistantInStatus("GENERATING", 10_000);
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
   * ★★ 判据②（断开）：客户端读一半掐断连接 ⇒ 库里那条是 {@code FAILED}，且已经生成的正文不丢。
   *
   * <p>★★ <strong>为什么这条用例断言不了"error 帧 + done 帧"</strong>（如实记）：
   * 连接是<strong>客户端主动掐的</strong>，之后服务端写什么都到不了 —— 那两帧在 wire 上不存在，
   * 任何"从响应流里读帧"的断言都只能读到断点之前的部分。所以这一条读的是：
   *
   * <ol>
   *   <li><strong>库里的终态</strong>（{@code FAILED} + 原因）—— 那才是"服务端确实收尾了"的证据；
   *   <li>库里的<strong>正文不是被清掉的</strong>（断开不清空）。
   * </ol>
   *
   * <p>★ 那两帧的顺序与"恰好一次"由 {@code ChatStreamEventRecordingTest} 用同一个 {@code ChatService}
   * 读（接管事件出口）：HTTP 层与事件层各读一半，合起来才是完整读数。
   *
   * <p>★★ <strong>本批【未完成】—— 三次尝试都没能在真 HTTP 上制造出 DISCONNECT</strong>（如实记，不把断言改弱）：
   *
   * <ol>
   *   <li>{@code HttpClient} + 关闭响应体：服务端下一次写照样成功（TCP 缓冲区吃得下、连接没被重置） ⇒ 库里读到 {@code GENERATED}；
   *   <li>裸 socket + {@code setSoLinger(true, 0)} + {@code close()}（发 RST）：<strong>仍然</strong>读到
   *       {@code GENERATED} —— 服务端那个"攒住响应头"的行为意味着它可能已经把整段写进了容器缓冲， 挂断时并没有一次真的 socket 写失败；
   *   <li>把"掐断时刻"改成轮询数据库到 {@code GENERATING} 再掐（本条现在的写法）：同上。
   * </ol>
   *
   * <p>⇒ 缺的不是服务端那条分支（它由 {@code ChatStreamEventRecordingTest} 与装配器测试各自读），
   * 而是<strong>"从客户端真的掐断一条正在写的连接"</strong>这个前置。 下一步方向：让测试能接管 {@code EventSink}（把写失败注入链路）而不是靠网络时序 ——
   * 那需要生产代码开一个测试可注入的出口。
   *
   * <p>★ 保留 {@code @Disabled} 而不是删掉它、也不改成"恒真"：一个写在案上的未完成， 比一个假的绿或一个空壳都更有价值（先写下来的"没做完"）。
   */
  @Test
  @org.junit.jupiter.api.Disabled("未完成：真 HTTP 上制造不出 DISCONNECT（见方法注释的三次尝试）")
  void clientDisconnectPersistsFailedWithoutLosingWhatWasGenerated() throws Exception {
    Fixture fixture = seedWithVisibleSegment();
    fakeModel.set(FakeChatStreamPorts.longAnswerStreaming(120, 12));
    String token = login();
    String requestBody = body(fixture.appId(), null, "差旅住宿标准");

    String messageId;
    try (Socket socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
      socket.setTcpNoDelay(true);
      OutputStream raw = socket.getOutputStream();
      raw.write(
          ("POST /api/v1/conversations/chat HTTP/1.1\r\n"
                  + "Host: localhost:"
                  + port
                  + "\r\n"
                  + "Authorization: Bearer "
                  + token
                  + "\r\n"
                  + "Accept: text/event-stream\r\n"
                  + "Content-Type: application/json\r\n"
                  + "Content-Length: "
                  + requestBody.getBytes(StandardCharsets.UTF_8).length
                  + "\r\n"
                  + "Connection: close\r\n\r\n"
                  + requestBody)
              .getBytes(StandardCharsets.UTF_8));
      raw.flush();
      // ★ 不从流里读 meta（本机实测：容器攒住响应头，整条流结束前读不到任何一帧）。
      //   改成轮询数据库：助手消息**先于第一个字节就落库**，它出现即"服务端正在生成"。
      messageId = waitForAssistantInStatus("GENERATING", 10_000);
      // ★★ RST：SO_LINGER = 0 让 close() 发 RST 而不是 FIN（FIN 对面只会读到"流结束"）
      socket.setSoLinger(true, 0);
    }

    waitForStatus(messageId, "FAILED", 10_000);
    assertThat(statusInDb(messageId))
        .as("★★ 断开 ⇒ FAILED（不是 STOPPED：客户端断开不等于用户点了停止，见 ABORT 那条用例）")
        .isEqualTo("FAILED");
    assertThat(failReasonInDb(messageId)).as("★ 断开也要给出可读原因").isNotBlank();
    assertThat(contentInDb(messageId))
        .as("★ 断开不是清空：库里仍是断开那一刻已经生成的那一段（可以是空串，但绝不能是被清掉的）")
        .isNotNull();
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

  /** 建模型 → 库 → 应用 → 文档 → 分段（分段正文含"差旅住宿标准"，全文检索能命中）。 */
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
    UUID segmentId = seedDocumentAndSegment(kbId, "SSE 差旅制度文档", "差旅住宿标准为每人每天500元");
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

  private UUID seedDocumentAndSegment(UUID kbId, String docName, String text) {
    UUID docId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_document (id, kb_id, name, source, doc_type, org_id, classification,"
            + " status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?, ?, ?, 'local', '制度', ?::uuid, 'INTERNAL', 'SUCCEEDED', 0, 1, true,"
            + " now(), now())",
        docId,
        kbId,
        docName,
        ROOT_ORG);
    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, ordinal, content, search_text,"
            + " content_hash, char_count, enabled, embedding_status, created_at, updated_at,"
            + " classification) values (?, ?, ?, 0, ?, ?, ?, ?, true, 'PENDING_EMBEDDING',"
            + " now(), now(), (select classification from kb_document where id = ?))",
        segmentId,
        docId,
        kbId,
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
