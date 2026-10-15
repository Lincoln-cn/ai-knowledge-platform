package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatStreamWriter;
import cn.lincoln.aiplatform.conversation.support.AssistantGeneratedTextWait;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * 「停止生成」端点的<strong>真 HTTP + 真库</strong>读数（{@code #119} 的判据 ①②③）。
 *
 * <h3>★★ 为什么必须是这一组（而不是领域层 / MockMvc）</h3>
 *
 * <p>{@code #119} 的病是"契约里有两个终态，而服务端分不出这两件事"。要回答"现在分得出吗"， 必须让<strong>真的 HTTP
 * 请求</strong>打进来：停止是一个<strong>独立于连接</strong>的请求 （{@code POST
 * /api/v1/conversations/{conversationId}/messages/{messageId}/stop}）， 而 MockMvc 不经过真容器与真过滤器链 ——
 * "前端的这次请求真能打到那个方法上吗"它答不了。 库里那一行更要真读（{@code A5}：停止保留已生成内容，判据是"重新查库"）。
 *
 * <h3>★★ 三条用例各自钉住一件事（判据 ①②③）</h3>
 *
 * <table>
 *   <tr><th>用例</th><th>造法</th><th>终态</th></tr>
 *   <tr><td>{@link #stopViaTheEndpointEndsAsStoppedAndKeepsTheGeneratedText()}</td>
 *       <td>真 HTTP 调停止端点（生成中途）</td><td>{@code STOPPED} + 正文保留</td></tr>
 *   <tr><td>{@link #aDisconnectWithoutAnyStopEndsAsFailed()}</td>
 *       <td>★ 从头到尾没收到过停止，连接那条路断了</td><td>{@code FAILED}</td></tr>
 *   <tr><td>{@link #stopThenDisconnectStillEndsAsStopped()}</td>
 *       <td>★★ 先停止（端点），**再**断连</td><td>{@code STOPPED}（不是 {@code FAILED}）</td></tr>
 * </table>
 *
 * <p>★★ <strong>第三条是本批的金丝雀靶子</strong>：它<strong>只有</strong>在"显式停止优先于连接断开"
 * 那一步真的在的时候才会绿。造法刻意让"停止"与"断连"之间<strong>一个块都没有</strong> —— 假模型吐一块之后就挂住，等到测试放行才抛"写不出去"。于是 {@code
 * ChatService.Run#onChunk} 那个检查点<strong>永远看不到停止信号</strong>， 唯一能把它判成 {@code ABORT}
 * 的，就是收尾那一刻按写死的顺序再读一次信号。 去掉那一步 ⇒ 这一条立刻红（终态变 {@code FAILED}，实测逐字记在交付报告里）。
 *
 * <h3>★★ 关于「断开」这一条怎么造（如实记，免得被读成"真断网"）</h3>
 *
 * <p>本类用<strong>假模型抛 {@link UncheckedIOException}</strong> 制造断开。它与"写连接失败"
 * 走的是生产里<strong>同一个</strong> catch 分支（{@code ChatService#startModelThread} 的 {@code catch
 * (UncheckedIOException disconnected)}）—— 路径、终态、文案全都一样。
 *
 * <p>★ 为什么不真的掐客户端连接：{@code ChatStreamHttpTest} 逐字记录了三次尝试 （关响应体 / 裸 socket 发 RST / 轮询到 {@code
 * GENERATING} 再 RST）<strong>三次都读到 {@code GENERATED}</strong> —— 客户端 RST 之后服务端那次写很可能被容器缓冲吞掉，{@code
 * IOException} 从未发生。 所以本类不重复那条已经证伪的路子，而是让断开这件事在其<strong>唯一的观测点</strong>上发生。
 *
 * <p>★ 假模型只在测试里，不起任何长驻服务（项目纪律）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=1200",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class ConversationStopEndpointTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** 另一个用户（造"别人的会话"用；★ 库里没有这个人也没关系 —— 归属判定只比 id）。 */
  private static final UUID SOMEBODY_ELSE = UUID.fromString("00000000-0000-4000-8000-00000000dead");

  /** 假模型吐的字（不含引号与换行，便于直接断言原始帧文本）。 */
  private static final String ANSWER = "停止端点夹具：差旅住宿标准为每人每天500元，超出部分由个人承担；报销需附发票与行程单。";

  /**
   * 长答案（约 200 字，{@link #ANSWER} 重复四遍）—— 给"生成中途"那类读数留出足够宽的窗口。
   *
   * <p>★ 为什么不能只吐 {@link #ANSWER} 一句：它 50 字上下，几块就写完了，而"轮询库拿到 messageId → 打停止端点" 正常也要几十毫秒 ⇒ 用例会偶发地读到
   * {@code GENERATED}（那种红看起来像"停止没生效"，真实原因却是窗口太窄）。
   */
  private static final String LONG_ANSWER = ANSWER.repeat(4);

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /** 每个用例自己装一个假模型；{@code null} = 用兜底实现（本类不用它）。 */
  @Autowired AtomicReference<ChatStreamPort> fakeModel;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation"
            + " where user_id = ?::uuid)",
        ADMIN_ID);
    // ★ 越权那条用例会往"别人的会话"里塞一条消息 ⇒ 也要先删消息再删会话
    jdbc.update(
        "delete from cv_message where conversation_id in (select id from cv_conversation"
            + " where user_id = ?::uuid)",
        SOMEBODY_ELSE);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", SOMEBODY_ELSE);
    jdbc.update("delete from ai_model where name like 'SSE 停止夹具%'");
    fakeModel.set(null);
  }

  // ────────────────────────── ① 停止 ⇒ STOPPED + 正文保留 ──────────────────────────

  /**
   * ★★ 判据①：真 HTTP 上调停止端点 ⇒ 本轮收在 {@code STOPPED}，且<strong>已生成内容保留</strong>。
   *
   * <p>造法：假模型吐得很慢（约 17 块 × 200ms ≈ 3.4 秒，见 {@link #LONG_ANSWER}）。主线程<strong>轮询数据库</strong>
   * 等那条助手消息<strong>正文非空</strong>（= 它真的吐过字，见 {@link AssistantGeneratedTextWait#await}），再打停止端点 ——
   * 于是这一条读的是"从 HTTP 打进来的停止请求" 真的能把一轮收成 {@code STOPPED}（在此之前，只有测试里直接置 {@code ChatStopSignal} 才做得到，
   * 也就是说这条链在真栈上<strong>不可用</strong>）。
   *
   * <p>★★ <strong>等的条件不能是"状态是 {@code GENERATING}"</strong>（本批实测踩到过两次，`#125` 的同族）： 那一行在<strong>第一个
   * chunk 之前</strong>就已经存在（助手消息是"先落库再开流"，见 {@code ChatTurnWriter#prepare}） ⇒
   * 停止请求可能赶在第一块之前到达，而那时它走的是"上游还没吐字"那条路径 ⇒ 终态确实还是 {@code STOPPED}， 但<strong>正文是空的</strong> ⇒ 下面那条
   * {@code isNotEmpty} 红。红的样子像"停止丢了内容"，真实原因是 <strong>等待条件选错了观测点</strong> —— 所以这里等的是"库里正文非空"，不是状态。
   *
   * <p>★ 断言的四面：① 端点回执 {@code accepted=true}；② 流里 {@code done.status=STOPPED} 且<strong>没有</strong>
   * {@code error} 帧（停止不是故障）；③ 库里那条是 {@code STOPPED} 且 {@code fail_reason} 为 {@code null}；④
   * 库里的正文<strong>非空</strong>、是答案的前缀 （非空断言才排得掉"停止其实发生在写完之后"这种假绿）。
   */
  @Test
  void stopViaTheEndpointEndsAsStoppedAndKeepsTheGeneratedText() throws Exception {
    seedChatModel();
    UUID conversationId = seedConversation(ADMIN_ID, "停止夹具会话");
    // ★ 块间 200ms：把"生成中途"那一段拉到 3 秒量级 ⇒ 停止落在"已经吐过字、还没写完"的窗口里有大余量
    fakeModel.set(slowStreaming(200, 12));
    String token = login();

    List<Frame> frames;
    UUID messageId;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var running = pool.submit(() -> chatFrames(token, conversationId, "差旅住宿标准"));
      // ★★ 等它"真的吐过字"（库里正文非空）—— ★ 不是等状态 GENERATING（见 AssistantGeneratedTextWait 的类注释：
      //     那会造出"先停后吐"那条假红）。★ 这把等待全仓只有一处（#125 的收尾）
      messageId = AssistantGeneratedTextWait.await(jdbc, conversationId, 10_000);
      Receipt receipt = stop(token, conversationId, messageId);
      assertThat(receipt.status()).as("★ 端点回执的 HTTP 码必须是 200（停止是一次正常请求，不是错误）").isEqualTo(200);
      assertThat(receipt.body())
          .as("★★ 判据①：端点收下了这次停止（accepted=true）")
          .contains("\"accepted\":true");
      frames = running.get(60, TimeUnit.SECONDS);
    }

    List<String> events = frames.stream().map(Frame::event).toList();
    assertThat(events.stream().filter("done"::equals).count())
        .as("★ 停止路径也必须恰好一次 done")
        .isEqualTo(1);
    assertThat(events).as("★★ 判据①：ABORT 不发 error —— 界面上不该出现一条「出错了请重试」的横幅").doesNotContain("error");
    assertThat(dataOf(frames, "done"))
        .as("★★ 判据①：真 HTTP 上打进来的停止请求 ⇒ 终态 STOPPED（在此之前只有测试能触发）")
        .contains("\"status\":\"STOPPED\"");

    assertThat(statusInDb(messageId)).as("★★ 判据①：库里那条是 STOPPED").isEqualTo("STOPPED");
    assertThat(failReasonInDb(messageId)).as("★ 非失败路径不许留下失败原因（库里那一列可空，写空串也是错的）").isNull();
    String content = contentInDb(messageId);
    assertThat(content).as("★★ A5：停止保留已生成内容（清空的话用户看到的是一条空白回答，重生成也失去起点）").isNotEmpty();
    assertThat(LONG_ANSWER).as("★ 它确实是答案的前缀 —— 即停止发生在写完之前（否则这条断言在「其实写完了」时也会绿）").startsWith(content);
  }

  // ────────────────────────── ② 断开（没收到过停止）⇒ FAILED ──────────────────────────

  /**
   * ★★ 判据②：<strong>从头到尾没有收到过停止</strong>，只是连接那条路断了 ⇒ {@code FAILED}， 且已生成的正文不丢。
   *
   * <p>★ 这一条与上面那条<strong>并存</strong>才说明"两件事可区分"：两条用例造的是同一轮生成里的两种收尾，
   * 各自断言<strong>不同</strong>的终态（{@code STOPPED} vs {@code FAILED}）。只有一条时， "把停止也当断开"这种改法照样绿 —— 那正是
   * {@code #119} 之前的状态。
   */
  @Test
  void aDisconnectWithoutAnyStopEndsAsFailed() throws Exception {
    seedChatModel();
    UUID conversationId = seedConversation(ADMIN_ID, "断开夹具会话");
    // ★ 吐两块之后抛"写不出去"（= DISCONNECT 在服务端唯一的观测点；见类注释）
    fakeModel.set(disconnectAfterChunks(2));
    String token = login();

    List<Frame> frames = chatFrames(token, conversationId, "差旅住宿标准");
    UUID messageId = UUID.fromString(messageIdOf(frames));

    List<String> events = frames.stream().map(Frame::event).toList();
    assertThat(events)
        .as("★ 断开路径：error 收原因、done 收终态（error 不是终态）")
        .contains("error")
        .contains("done");
    assertThat(dataOf(frames, "done"))
        .as("★★ 判据②：没收到过停止 ⇒ 终态 FAILED")
        .contains("\"status\":\"FAILED\"");
    assertThat(dataOf(frames, "done"))
        .as("★★ 与停止那条用例的分界：FAILED 不是 STOPPED（两者必须可区分）")
        .doesNotContain("\"status\":\"STOPPED\"");

    assertThat(statusInDb(messageId)).as("★ 库里那条必须是 FAILED").isEqualTo("FAILED");
    assertThat(failReasonInDb(messageId))
        .as("★ 这一路没有上游 detail ⇒ 库里记「连接中断」（与 error 帧同源）")
        .isEqualTo("连接中断");
    assertThat(contentInDb(messageId)).as("★ 断开不是清空：断开那一刻已经生成的那一段仍在库里").isNotEmpty();
  }

  // ────────────────────────── ③ 先停止、再断连 ⇒ STOPPED（金丝雀靶子）──────────────────────────

  /**
   * ★★ 判据③：<strong>先停止（端点）、再断连</strong> ⇒ 终态必须是 {@code STOPPED}（不是 {@code FAILED}）。
   *
   * <h3>★★ 造法（这是本批最要紧的一条，读它的人请连着 {@code ChatService#decideTerminalPath} 读）</h3>
   *
   * <p>假模型吐<strong>一块</strong>之后<strong>挂住</strong>，等测试放行才抛"写不出去"。于是：
   *
   * <ol>
   *   <li>测试先等<strong>那一块真的吐出去</strong>（{@code chunkEmitted}），★ 这一步不能省：
   *       否则停止请求可能赶在<strong>第一块之前</strong>到达，那一轮会走 {@code onChunk} 那个检查点 （它同样给 {@code STOPPED}）⇒
   *       "收尾那一刻的判定顺序"根本没被触发（本批实测踩到过，见交付报告）；
   *   <li>测试打停止端点（服务端置上信号）；
   *   <li>★ 此后<strong>一个块都没有</strong> ⇒ {@code Run#onChunk} 那个检查点<strong>永远看不到</strong>这个信号 （{@code
   *       cancelledByStop} 恒为 {@code false}）；
   *   <li>测试放行 ⇒ 假模型抛 {@code UncheckedIOException} ⇒ 服务端把这一轮判成"连接断了"。
   * </ol>
   *
   * <p>⇒ 唯一能把它收成 {@code ABORT} 的，是收尾那一刻按<strong>写死的顺序</strong>再读一次停止信号 （{@code
   * ChatService.Run#decideTerminalPath} 的第一行）。★ 去掉那一步 ⇒ 这一条立刻红： 终态变 {@code FAILED}、{@code
   * fail_reason} 变成「连接中断」（本批实测逐字记在交付报告里）。
   *
   * <p>★ 另一面：这条用例同时证明"停止保留内容"在竞态下也成立（假模型吐过的那一块仍在库里）。
   */
  @Test
  void stopThenDisconnectStillEndsAsStopped() throws Exception {
    seedChatModel();
    UUID conversationId = seedConversation(ADMIN_ID, "先停后断夹具会话");
    CountDownLatch chunkEmitted = new CountDownLatch(1);
    CountDownLatch releaseDisconnect = new CountDownLatch(1);
    fakeModel.set(disconnectWhenReleased(chunkEmitted, releaseDisconnect));
    String token = login();

    List<Frame> frames;
    UUID messageId;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var running = pool.submit(() -> chatFrames(token, conversationId, "差旅住宿标准"));
      assertThat(chunkEmitted.await(10, TimeUnit.SECONDS))
          .as("★ 前置：假模型已经开始吐字（否则停止请求可能赶在第一块之前 ⇒ 判定顺序那一步根本没被触发）")
          .isTrue();
      // ★ 这一句只用来取 messageId（库里那一行先于第一个字节就存在）；
      //   本条的**实质前置**是上面那把闩（"那一块真的吐出去了"），不是这个状态条件
      messageId = waitForAssistantInStatus(conversationId, "GENERATING", 10_000);
      Receipt receipt = stop(token, conversationId, messageId);
      assertThat(receipt.body())
          .as("★ 前置：停止请求真的被服务端收下了（否则下面那一条在验一条从没发生过的顺序）")
          .contains("\"accepted\":true");
      // ★ 放行断开：此后连接"没了"，而停止信号已经在服务端手上
      releaseDisconnect.countDown();
      frames = running.get(60, TimeUnit.SECONDS);
    }

    List<String> events = frames.stream().map(Frame::event).toList();
    assertThat(events.stream().filter("done"::equals).count()).isEqualTo(1);
    assertThat(dataOf(frames, "done"))
        .as("★★ 判据③：先停止、再断连 ⇒ 终态是 STOPPED（判定顺序写死，不看竞态）")
        .contains("\"status\":\"STOPPED\"");
    assertThat(events).as("★ 它是停止路径 ⇒ 不发 error（若判定顺序反了，这里会先出现一条 error）").doesNotContain("error");

    assertThat(statusInDb(messageId))
        .as("★★ 判据③：库里那条必须是 STOPPED，不是 FAILED —— 这就是 #119 的病因那一格")
        .isEqualTo("STOPPED");
    assertThat(failReasonInDb(messageId))
        .as("★★ 停止不是失败：认成 FAILED 时这一列会被写成「连接中断」——它必须仍是 null")
        .isNull();
    assertThat(contentInDb(messageId)).as("★ 停止保留已生成内容（假模型吐过的那一块仍在库里）").isNotEmpty();
  }

  // ────────────────────────── ④ 端点自身的边界（404 而不是 403）──────────────────────────

  /**
   * ★ 别人的会话 ⇒ <strong>404</strong>（不是 403），且一条信号都不许置上。
   *
   * <p>★ 403 等于告诉对方"这个会话/消息 id 是存在的"（{@code 07 §3.3} 纪律 2），那是一次免费的探测。
   * 停止是一条<strong>写端点</strong>，它必须与整套权限口径一致。
   */
  @Test
  void someoneElsesConversationCannotBeStoppedAndLooksNonexistent() throws Exception {
    seedChatModel();
    UUID foreign = seedConversation(SOMEBODY_ELSE, "别人的会话");
    UUID messageId = seedGeneratingMessage(foreign);

    Receipt receipt = stop(login(), foreign, messageId);

    assertThat(receipt.status()).as("★★ 越权停止别人的会话必须返 404 —— 403 会确认这个 id 存在").isEqualTo(404);
    assertThat(receipt.body()).as("★ 包络里的码与 HTTP 码同值（08 §4.1）").contains("\"code\":404");
    assertThat(statusInDb(messageId))
        .as("★★ 越权被拒时消息状态一个字节都不许变（停止的副作用只允许落在合法请求上）")
        .isEqualTo("GENERATING");
  }

  /**
   * ★ 停止一条<strong>已经收尾</strong>的消息 ⇒ {@code accepted=false} + 当前状态（不是错误、更不是静默）。
   *
   * <p>这是"晚到的停止"那条正常路径：用户点了停止，而生成恰好在同一毫秒写完。三种处置里只有这一种对 （报错 ⇒ 把一次正常操作显示成故障；静默 200 ⇒ 谁也说不清到底停没停）。
   */
  @Test
  void stoppingAnAlreadyFinishedMessageReportsNotAccepted() throws Exception {
    seedChatModel();
    UUID conversationId = seedConversation(ADMIN_ID, "已收尾夹具会话");
    UUID messageId = seedTerminalMessage(conversationId, "GENERATED");

    Receipt receipt = stop(login(), conversationId, messageId);

    assertThat(receipt.status()).as("★ 晚到的停止不是错误：HTTP 仍是 200").isEqualTo(200);
    assertThat(receipt.body())
        .as("★ 回执必须说清「没停成，因为它已经收尾了」—— 静默 200 会让'停没停'变成不可读")
        .contains("\"accepted\":false")
        .contains("\"status\":\"GENERATED\"");
  }

  // ────────────────────────── 假模型 ──────────────────────────

  /** 逐块吐字、块间停顿的假模型（吐 {@link #LONG_ANSWER} 的全部）。 */
  private static ChatStreamPort slowStreaming(long chunkDelayMs, int chunkChars) {
    return (modelId, prompt, timeout, callback) -> {
      for (int at = 0; at < LONG_ANSWER.length(); at += chunkChars) {
        callback.onChunk(
            LONG_ANSWER.substring(at, Math.min(LONG_ANSWER.length(), at + chunkChars)));
        sleep(chunkDelayMs);
      }
      callback.onEnd(ChatStreamPort.End.COMPLETE, null);
    };
  }

  /** 吐 {@code chunks} 块之后抛"写不出去"（{@code DISCONNECT} 的观测点，见类注释）。 */
  private static ChatStreamPort disconnectAfterChunks(int chunks) {
    return (modelId, prompt, timeout, callback) -> {
      int perChunk = Math.max(1, ANSWER.length() / Math.max(1, chunks));
      for (int at = 0; at < chunks * perChunk && at < ANSWER.length(); at += perChunk) {
        callback.onChunk(ANSWER.substring(at, Math.min(ANSWER.length(), at + perChunk)));
      }
      throw disconnected();
    };
  }

  /**
   * 吐<strong>一块</strong>之后挂住，等 {@code release} 放行再抛"写不出去"。
   *
   * <p>★★ 挂住这一段是判据③的全部关键：它让"停止请求"与"断连"之间<strong>一个块都没有</strong>， 于是 {@code onChunk}
   * 那个检查点失效，只有收尾时的判定顺序还能救。
   *
   * <p>★★ {@code chunkEmitted} 那把闩同样不能省（本批实测踩到过）：测试必须<strong>等这一块真的吐出去</strong>
   * 再打停止端点。否则停止请求可能赶在<strong>第一块之前</strong>到达 —— 那一轮会走 {@code onChunk} 那个检查点 （它同样能得到 {@code
   * STOPPED}），于是"收尾那一刻的判定顺序"<strong>根本没被触发</strong>， 这条用例就在验一件它没造出来的事（那种绿是假绿）。
   *
   * <p>★★ <strong>为什么这里不等"库里正文非空"（判据① 等的是那个）</strong>：这一条里假模型吐完一块就<strong>挂住了</strong>， 而落库 flush
   * 只在 {@code onChunk} 里被驱动（{@code ChatService.Run#flushIfDue}）⇒ 库里的正文要到<strong>收尾</strong>
   * 才被补上（{@code ChatTurnWriter#finish}）—— "库里正文非空"在这一条里是<strong>永远等不到</strong>的观测点。
   * 所以这里的等法是假模型端口自己的信号：它证明"这一块已经被交给上游回调"，比读库更强、也更贴这一条要造的那一刻。 ★
   * 两条用例等的东西不同，是因为它们要造的时刻不同：判据①要"吐过字、还在写"；判据③要"吐过一块、此后一个块都没有"。
   */
  private static ChatStreamPort disconnectWhenReleased(
      CountDownLatch chunkEmitted, CountDownLatch release) {
    return (modelId, prompt, timeout, callback) -> {
      callback.onChunk(ANSWER.substring(0, Math.min(12, ANSWER.length())));
      chunkEmitted.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("测试没有放行断开");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("假模型在等断开放行时被打断", interrupted);
      }
      throw disconnected();
    };
  }

  /** 与生产同一条包装：写连接失败以 {@link UncheckedIOException} 传出。 */
  private static UncheckedIOException disconnected() {
    return ChatStreamWriter.disconnected(new IOException("测试：写连接失败（连接没了）"));
  }

  /** 不吞中断：被打断时恢复中断位并抛出（假模型也要遵守"取消要能生效"）。 */
  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("假模型在吐字间隙被打断", interrupted);
    }
  }

  /** 可替换的假模型（与 {@code ChatStreamHttpTest} 同一手法：依赖替换，生产代码一行不改）。 */
  @TestConfiguration
  static class FakeModelConfig {

    @Bean
    AtomicReference<ChatStreamPort> fakeChatStreamPortHolder() {
      return new AtomicReference<>();
    }

    @Bean
    @Primary
    SwappableChatStreamPort swappableChatStreamPort(
        AtomicReference<ChatStreamPort> holder, ObjectProvider<ChatStreamPort> allPorts) {
      return new SwappableChatStreamPort(holder, allPorts);
    }
  }

  /** ★ 具名类而不是 lambda：兜底实现要从"所有 {@code ChatStreamPort} bean"里挑，而那个集合里有它自己。 */
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
                .orElseThrow(() -> new IllegalStateException("测试既没装假模型，兜底实现也不在容器里"));
      }
      port.stream(modelId, prompt, timeout, callback);
    }
  }

  // ────────────────────────── HTTP ──────────────────────────

  /** 一帧 SSE：事件名 + data。 */
  private record Frame(String event, String data) {}

  /** 停止端点的回执读数。 */
  private record Receipt(int status, String body) {}

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

  /** 打一次停止端点（契约的 {@code POST /conversations/{id}/messages/{mid}/stop}）。 */
  private Receipt stop(String token, UUID conversationId, UUID messageId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    base()
                        + "/api/v1/conversations/"
                        + conversationId
                        + "/messages/"
                        + messageId
                        + "/stop"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Receipt(response.statusCode(), response.body());
  }

  /** 跑一轮问答并读完全部帧（心跳/开流填充这类注释帧不产生业务帧）。 */
  private List<Frame> chatFrames(String token, UUID conversationId, String question)
      throws Exception {
    String body =
        "{\"conversationId\":\""
            + conversationId
            + "\",\"question\":\""
            + question
            + "\",\"attachmentIds\":[],\"thinking\":false}";
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
    assertThat(response.statusCode()).as("SSE 端点必须是 200（流开始了，终态在流里）").isEqualTo(200);
    try (InputStream stream = response.body()) {
      return readFrames(stream);
    }
  }

  /**
   * 手写 SSE 解析（不引库）：本类要读的正是"帧名与字段"这些 wire 形状。
   *
   * <p>★ 注释帧（心跳 {@code : keep-alive} 与开流填充 {@code : open: …}）<strong>不产生业务帧</strong>。
   */
  private static List<Frame> readFrames(InputStream stream) throws IOException {
    List<Frame> frames = new ArrayList<>();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    StringBuilder block = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.isEmpty()) {
        block.append(line).append('\n');
        continue;
      }
      Frame parsed = parseFrame(block.toString());
      block.setLength(0);
      if (parsed != null) {
        frames.add(parsed);
      }
    }
    Frame tail = parseFrame(block.toString());
    if (tail != null) {
      frames.add(tail);
    }
    return frames;
  }

  /** 一个 SSE 块 ⇒ 一帧；只有注释的块返回 {@code null}（心跳与开流填充都不算业务帧）。 */
  private static Frame parseFrame(String block) {
    String event = "message";
    StringBuilder data = new StringBuilder();
    boolean sawComment = false;
    for (String line : block.split("\n")) {
      if (line.startsWith(":")) {
        sawComment = true;
      } else if (line.startsWith("event:")) {
        event = line.substring("event:".length()).trim();
      } else if (line.startsWith("data:")) {
        data.append(line.substring("data:".length()).trim());
      }
    }
    if (sawComment && data.isEmpty()) {
      return null;
    }
    if (data.isEmpty()) {
      return null;
    }
    return new Frame(event, data.toString());
  }

  private static String dataOf(List<Frame> frames, String event) {
    return frames.stream()
        .filter(frame -> event.equals(frame.event()))
        .map(Frame::data)
        .findFirst()
        .orElseThrow(() -> new AssertionError("响应里没有 event: " + event + " 的帧"));
  }

  private static String messageIdOf(List<Frame> frames) {
    return stringField(dataOf(frames, "meta"), "messageId");
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

  // ────────────────────────── 库读数 ──────────────────────────

  private String statusInDb(UUID messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?", String.class, messageId);
  }

  private String contentInDb(UUID messageId) {
    return jdbc.queryForObject(
        "select content from cv_message where id = ?", String.class, messageId);
  }

  private String failReasonInDb(UUID messageId) {
    return jdbc.queryForObject(
        "select fail_reason from cv_message where id = ?", String.class, messageId);
  }

  /**
   * 等某个会话里出现指定状态的助手消息，并返回它的 id（★ 有上限，超时即断言失败 —— 不藏缺陷）。
   *
   * <p>★ 为什么走库而不是"从响应流里读 {@code meta}"：本机实测客户端在整条流结束之前可能读不到帧， 而助手消息<strong>先于第一个字节就落库</strong> ⇒
   * 库里那一行是更早、更确定的观测点。
   *
   * <p>★★ <strong>它不能用来等"已经生成过内容"</strong>（见 {@link AssistantGeneratedTextWait#await}）： 状态 {@code
   * GENERATING} 在第一个 chunk 之前就有了。
   */
  private UUID waitForAssistantInStatus(UUID conversationId, String status, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
    while (System.nanoTime() < deadline) {
      List<String> found =
          jdbc.queryForList(
              "select m.id::text from cv_message m where m.conversation_id = ?::uuid"
                  + " and m.role = 'ASSISTANT' and m.status = ? order by m.created_at desc limit 1",
              String.class,
              conversationId,
              status);
      if (!found.isEmpty()) {
        return UUID.fromString(found.getFirst());
      }
      // ★ 20ms 只是轮询粒度（这一句等的是"那一行出现了没有"，不是"吐过字没有"——
      //   后者是 AssistantGeneratedTextWait#await 的事，两者别混用）
      Thread.sleep(20);
    }
    throw new AssertionError("在 " + timeoutMs + "ms 内没有等到状态为 " + status + " 的助手消息");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private void seedChatModel() {
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, 'SSE 停止夹具对话模型', 'CHAT',"
            + " 'LOCAL_INFERENCE', 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true,"
            + " now(), now())",
        UUID.randomUUID());
  }

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

  /** 直接造一条 {@code GENERATING} 的助手消息（越权用例用 —— 它不该被停止请求碰到）。 */
  private UUID seedGeneratingMessage(UUID conversationId) {
    return seedMessage(conversationId, "GENERATING", "");
  }

  /** 直接造一条已经收尾的消息（"晚到的停止"那条用例用）。 */
  private UUID seedTerminalMessage(UUID conversationId, String status) {
    return seedMessage(conversationId, status, "已经写完的答案");
  }

  private UUID seedMessage(UUID conversationId, String status, String content) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status, created_at,"
            + " updated_at) values (?, ?, 'ASSISTANT', ?, ?, now(), now())",
        id,
        conversationId,
        content,
        status);
    return id;
  }
}
