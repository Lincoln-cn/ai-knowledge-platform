package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import cn.lincoln.aiplatform.conversation.support.AssistantGeneratedTextWait;
import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ {@code #143} 前半：<strong>公开面（免登录）的停止端点</strong> —— 用户 2026-10-16 拍板"做端点，与 {@code chat} 同口径"。
 *
 * <h3>★★ 它补的是哪一个静默降级（修之前的形状）</h3>
 *
 * <p>公开页的"停止"走的是客户端 {@code abort()}（掐 fetch）⇒ 服务端只看到"连接没了" ⇒ 本轮按 {@code DISCONNECT} 收尾、 库里落
 * <strong>{@code FAILED}</strong>。而契约给"用户停止"与"连接断开"定的是两个终态 （{@code ABORT ⇒ STOPPED} / {@code
 * DISCONNECT ⇒ FAILED}）⇒ <strong>用户点了停止，却得到一条失败记录</strong>， 且界面上写着「已停止」、任何地方都不报错。
 *
 * <h3>★★ 判据（三条 + 一条边界）</h3>
 *
 * <ol>
 *   <li>{@link #stoppingAnInFlightPublicTurnEndsAsStoppedInTheDatabase()} —— 真 HTTP：公开面的问答流正在生成时，
 *       访客（<strong>匿名</strong>，只有令牌）调停止端点 ⇒ 库里那条消息终态必须是 {@code STOPPED} （★ 修之前这里会是 {@code FAILED}）；
 *   <li>{@link #stoppingAnUnknownMessageIsIndistinguishableFromNotExisting()} —— 停一条不属于任何会话的消息 ⇒
 *       <strong>404</strong>（不是 403、不是 200）。★ 这条同时钉住"会话 id 由服务端从消息行反解"那条边界：
 *       端点<strong>不收</strong>请求体里的会话 id，所以"客户端指定这条链接能停哪个会话"在形状上不存在；
 *   <li>{@link #stoppingAnAlreadyFinishedMessageIsAcceptedFalseNotAnError()} —— 已经收尾的消息 ⇒ {@code
 *       200} + {@code accepted:false} +
 *       当前状态（停止晚到了是正常路径，<strong>不是</strong>错误、也<strong>不是</strong>静默 200）。
 * </ol>
 *
 * <h3>★ 为什么必须是真栈（真 HTTP + 真库）</h3>
 *
 * <p>这条链上"停止"的全部效果是服务端一条进程内信号（{@code ChatStopSignal}）被生成线程消费、然后在 <strong>四条终止路径的唯一出口</strong>上判成
 * {@code ABORT}。直接调服务层读不出"信号有没有被那个出口看到" —— 那正是 {@code #119} 第一条金丝雀红错地方（"停止赶在首块之前"）的形状。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      "app.chat.heartbeat-interval-ms=300",
      // ★ 闲置超时给宽（15s）：本类靠"答案很长 + 块间停顿"把生成拉到可采样的时长，
      //   超时太短会把这一轮收成 IDLE_TIMEOUT —— 那会让判据红在一个与停止无关的原因上。
      "app.chat.idle-timeout-ms=15000",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class PublicStopEndpointTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** {@code V4} 的种子账号：链接归属人（KB_OPERATOR / ALL / SECRET，技术部）。 */
  private static final UUID ZHANGYUN = UUID.fromString("10000000-0000-0000-0000-000000000002");

  /** 免登录问答流的真实 URL（与 {@code PublicChatHttpTest} 逐字同一条：凭据走请求头）。 */
  private static final String CHAT_PATH = "/api/v1/conversations/chat";

  /** {@code V25} 种子写死的 ROOT 部门（{@code kb_document.org_id} 是外键，见 Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /** 每个用例自己装一个假模型（见下面的配置类）。 */
  @Autowired AtomicReference<ChatStreamPort> fakeModel;

  /** 替换生产 ChatStreamPort，避免测试 @Primary 与 UpstreamStreamingChatAdapter 的 @Primary 冲突。 */
  @MockitoBean ChatStreamPort chatStreamPort;

  @BeforeEach
  void wireChatStreamPort() {
    doAnswer(
        invocation -> {
          ChatStreamPort port = fakeModel.get();
          if (port == null) {
            throw new IllegalStateException("本用例没有装假模型（fixture 缺失）");
          }
          port.stream(
              invocation.getArgument(0),
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3));
          return null;
        })
        .when(chatStreamPort)
        .stream(any(), any(), any(Duration.class), any());
    doAnswer(
        invocation -> {
          ChatStreamPort port = fakeModel.get();
          if (port == null) {
            throw new IllegalStateException("本用例没有装假模型（fixture 缺失）");
          }
          port.stream(
              invocation.getArgument(0),
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3),
              invocation.getArgument(4));
          return null;
        })
        .when(chatStreamPort)
        .stream(any(), any(), anyBoolean(), any(Duration.class), any());
    doAnswer(
            invocation -> {
              ChatStreamPort port = requireFakeModel();
              port.streamConversation(
                  invocation.getArgument(0),
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  invocation.getArgument(3));
              return null;
            })
        .when(chatStreamPort)
        .streamConversation(any(), any(), any(Duration.class), any());
    doAnswer(
            invocation -> {
              ChatStreamPort port = requireFakeModel();
              port.streamConversation(
                  invocation.getArgument(0),
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  invocation.getArgument(3),
                  invocation.getArgument(4));
              return null;
            })
        .when(chatStreamPort)
        .streamConversation(any(), any(), anyBoolean(), any(Duration.class), any());
  }

  private ChatStreamPort requireFakeModel() {
    ChatStreamPort port = fakeModel.get();
    if (port == null) {
      throw new IllegalStateException("本用例没有装假模型（fixture 缺失）");
    }
    return port;
  }

  /** ★ 直接观测信号残留（成因层读数）—— 不必绕真栈去猜"下一轮为什么被停"。 */
  @Autowired cn.lincoln.aiplatform.conversation.application.ChatStopSignal stopSignal;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private String token;

  private UUID credentialId;

  private UUID appId;

  private UUID chatModelId;

  /** 本类夹具的名字前缀 —— 真库容器跨测试类共享，固定名字会与别的类撞车。 */
  private static final String FIXTURE_PREFIX =
      "公开停止夹具-" + UUID.randomUUID().toString().substring(0, 8);

  /** ★ 默认装"很长的答案 + 块间 120ms 停顿"的假模型：判据① 需要一个**稳定可采样的生成中途** （短答案会在我们来得及发停止请求之前就写完了）。 */
  @BeforeEach
  void seed() {
    fakeModel.set(FakeChatStreamPorts.longAnswerStreaming(120, 12));
    chatModelId = seedChatModel();
    UUID embeddingModelId = seedEmbeddingModel();
    // ★★ 这个应用**必须挂一个知识库**才能过公开面的可见性判定（`PublicAppAdapter#findVisibleById` →
    //   `accessScope::canSee`）。本类实测踩到过：不挂库时令牌解析明明成功，停止端点却返回
    //   「这条链接当前不可用」（见 linkUnavailable）—— 那是"链接打不开"，不是"停止坏了"。
    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, 'INTERNAL', ?, 'PRIVATE', now(), now())",
        kbId,
        FIXTURE_PREFIX + "库",
        embeddingModelId,
        ZHANGYUN);
    seedDocumentAndSegment(kbId);
    appId = UUID.randomUUID();
    jdbc.update(
        "insert into as_assistant (id, name, icon, description, type, model_id, owner_id,"
            + " visibility, published, retrieval_mode, retrieval_threshold, prologue,"
            + " quick_questions, created_at, updated_at)"
            + " values (?, ?, '🤖', '公开停止夹具应用', 'SIMPLE', ?, ?, 'PRIVATE', true,"
            + " 'FULLTEXT', 0.00, '你好', array[]::text[], now(), now())",
        appId,
        FIXTURE_PREFIX + "应用",
        chatModelId,
        ZHANGYUN);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);
    credentialId = UUID.randomUUID();
    token = "pk_live_" + randomToken();
    jdbc.update(
        "insert into as_access_credential (id, assistant_id, token, enabled, max_classification,"
            + " access_count, created_at, created_by, updated_at, version)"
            + " values (?, ?, ?, true, 'INTERNAL', 0, now(), ?, now(), 0)",
        credentialId,
        appId,
        token,
        ZHANGYUN);
  }

  @AfterEach
  void cleanUp() {
    fakeModel.set(null);
    // ★ 顺序"从被引用者到引用者"（与 PublicChatHttpTest 同一条）：
    //   凭证 → 应用 → 模型 → 知识侧（分段 → 文档 → 库）→ 会话侧
    jdbc.update("delete from as_access_credential where id = ?", credentialId);
    jdbc.update("delete from as_assistant where id = ?", appId);
    jdbc.update("delete from ai_model where id = ?", chatModelId);
    jdbc.update(
        "delete from kb_segment where document_id in"
            + " (select id from kb_document where name like ?)",
        FIXTURE_PREFIX + "%");
    jdbc.update(
        "delete from kb_document where kb_id in"
            + " (select id from kb_knowledge_base where name like ?)",
        FIXTURE_PREFIX + "%");
    jdbc.update("delete from kb_knowledge_base where name like ?", FIXTURE_PREFIX + "%");
    jdbc.update(
        "delete from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?)",
        ZHANGYUN);
    jdbc.update("delete from cv_conversation where user_id = ?", ZHANGYUN);
  }

  // ────────────────────────── ① 公开面停止 ⇒ 库里 STOPPED ──────────────────────────

  /**
   * ★★ 判据①：公开面的问答流正在生成时，<strong>匿名</strong>访客调停止端点 ⇒ 库里终态 = {@code STOPPED}。
   *
   * <p>★★ 金丝雀（红在哪一行 + 逐字读数，本批实测）：把 {@code PublicChatService#stopTurn} 里的 {@code
   * stopSignal.request(messageId)} 删掉 ⇒ 判据① 红在它自己的库级断言上：{@code expected: "STOPPED" but was:
   * "GENERATED"}。
   *
   * <p>★ 这里有一处**必须如实记**的差别：那一格的取值<strong>取决于客户端这侧有没有把流读到底</strong> —— 本类的读流线程会把整条流读完 ⇒ 生成线程正常收尾 ⇒
   * 读到 {@code GENERATED}；而真实公开页在用户点停止时会 {@code abort()}（掐 fetch）⇒ 那条路径上读到的是 {@code FAILED}。
   * <strong>两者都是"停止没生效"的读数</strong>，但逐字消息不同 ⇒ 引用这条金丝雀时不许把 {@code FAILED} 写成必然。
   */
  @Test
  void stoppingAnInFlightPublicTurnEndsAsStoppedInTheDatabase() throws Exception {
    String messageId = startPublicTurnAndWaitUntilGenerating();

    HttpResponse<String> stopped = postStop(token, messageId);

    assertThat(stopped.statusCode())
        .as(
            "★ 公开面的停止端点对**匿名**访客必须可用（正当性 = 令牌有效，不是 JWT）。"
                + "★ 回执带在消息里是为了取证：404 有几种来源（令牌解析四态 / 消息不属于这条链接），"
                + "它们的包络不同，盲改会把几种原因混成一个。实际回执：%s",
            stopped.body())
        .isEqualTo(200);
    assertThat(stopped.body())
        .as("★ 回执 accepted=true = 服务端真的置上了停止信号（不是静默 200，也不是错误）")
        .contains("\"accepted\":true");
    // ★ 终态由**生成线程**落库，与那次响应之间有一段正常的间隔（契约逐字：回执说的是"服务端有没有收下"，
    //   不是"这一轮已经停住了"）⇒ 读数必须先等它收尾，否则读到的是 GENERATING（本批实测踩到过）。
    waitUntilTerminal(messageId);
    assertThat(statusInDb(messageId))
        .as(
            "★★ 判据①（#143 前半）：公开面停止 ⇒ 库里那条必须是 STOPPED（不是 FAILED）。"
                + "删掉 stopTurn 里的 stopSignal.request 这一条立刻红：expected STOPPED but was FAILED")
        .isEqualTo("STOPPED");
  }

  // ────────────────────────── ② 陌生消息 id ⇒ 404 ──────────────────────────

  /**
   * ★★ 判据②：停一条<strong>不存在</strong>的消息 ⇒ 404，且与"不属于这条链接"不可区分。
   *
   * <p>★ 这条钉的是"看不见一律 404（不是 403）"那条既有纪律：403 等于确认这个 id 存在，是一次免费的探测。
   */
  @Test
  void stoppingAnUnknownMessageIsIndistinguishableFromNotExisting() throws Exception {
    HttpResponse<String> response = postStop(token, UUID.randomUUID().toString());

    assertThat(response.statusCode())
        .as("★ 陌生消息 id ⇒ 404（不是 403、不是 200）—— 存在性不许从报错里漏出去")
        .isEqualTo(404);
  }

  // ────────────────────────── ③ 已收尾 ⇒ accepted=false ──────────────────────────

  /**
   * ★★ 判据③：已经收尾的消息 ⇒ {@code 200} + {@code accepted:false} + 当前状态。
   *
   * <p>★ 三种处置里只有这一种是对的（与登录面逐字同口径）：报错 ⇒ 用户按的是"停止"却看到失败提示；静默 200 ⇒ 谁也说不清这一轮到底有没有被停。★
   * 而且**不置信号**：为一条已收尾的消息置信号，那个 id 会永远留在 {@code ChatStopSignal} 里（只由 {@code consume} 取走）——
   * 直到某天有人重发同一个消息 id。
   */
  @Test
  void stoppingAnAlreadyFinishedMessageIsAcceptedFalseNotAnError() throws Exception {
    String messageId = startPublicTurnAndWaitUntilGenerating();

    assertThat(postStop(token, messageId).body()).contains("\"accepted\":true");
    waitUntilTerminal(messageId);

    HttpResponse<String> second = postStop(token, messageId);

    assertThat(second.statusCode()).as("★ 晚到的停止是**正常路径**：200，不是 4xx/5xx").isEqualTo(200);
    assertThat(second.body())
        .as("★ 回执必须说清『没停成，因为它已经收尾了』，并带上此刻的状态")
        .contains("\"accepted\":false")
        .contains("STOPPED");
  }

  /**
   * ★★ 判据③之二（{@code #143} 后半的**收回**那一半）：<strong>晚到的停止不留残留</strong>。
   *
   * <p>场景逐字：第一轮正常地被停止（终态 {@code STOPPED}），然后一个**晚到的**停止请求打到同一条消息上。 那一刻收尾路径**已经跑完**（它的 {@code
   * consume} 早走了）⇒ 如果这里只是"置上信号就返回"， 那个 id 会永远留在集合里（没有任何人在消费它），直到某天有人重发同一个消息 id。
   *
   * <p>⇒ 本用例断言回执说"没停成"（{@code accepted=false}），而**紧接着的第二轮必须照常跑完**
   * （`twoPublicTurnsLandInTheSameConversation` 那一侧的同时，这里额外证明"晚到的停止没有毒到下一轮"）。
   *
   * <p>★ 它同时是"{@code ChatStopService} 第 ⑤ 步（置完再读一次、读到就收回）"的观测点。
   */
  @Test
  void aLateStopLeavesNoResidueBehind() throws Exception {
    String messageId = startPublicTurnAndWaitUntilGenerating();

    assertThat(postStop(token, messageId).body()).contains("\"accepted\":true");
    waitUntilTerminal(messageId);

    // 晚到的停止（这一刻收尾已经发生）
    assertThat(postStop(token, messageId).body()).contains("\"accepted\":false");

    // ★★ 直接观测"残留"（比只看第二轮的结果状态更精确）：这一刻信号集合里**不该有这条消息**。
    //   它是 #143 后半那条账的**成因层**读数 —— 残留若在，下一轮一定会被停，而界面上只是"没有回答"。
    assertThat(stopSignal.isRequested(UUID.fromString(messageId)))
        .as("★★ #143 后半：晚到的停止必须**当场拒收、不留残留** —— 否则这个 id 会一直留在集合里，" + "直到某天有人重发同一个消息 id（一种没有症状的泄漏）")
        .isFalse();

    // ★ 第二轮：必须照常跑完 —— 而它落在**同一条会话**上（§5.2.1 的会话链），所以"残留毒到下一轮"在这里读得出来
    String nextMessageId = startPublicTurnAndWaitUntilGenerating();
    waitUntilTerminal(nextMessageId);

    assertThat(nextMessageId)
        .as("★ 第二轮是一条**新的**助手消息（这与'消息 id 不复用'那条约定一致，也正是 begin 的兜底对象）")
        .isNotEqualTo(messageId);
    assertThat(statusInDb(nextMessageId))
        .as(
            "★★ #143 后半：晚到的停止**不许**毒到下一轮 —— 若残留信号还在，这一轮会在第一个块上就按 ABORT 收尾"
                + "（终态 STOPPED，而用户根本没点过这一轮的停止）。取证：round1=%s round2=%s 信号状态=%s",
            messageId, nextMessageId, stopSignal.isRequested(UUID.fromString(nextMessageId)))
        .isEqualTo("GENERATED");
  }

  // ────────────────────────── ④ 公开面历史消息（#143 同批：归属键 = 会话链）──────────────────────────

  /**
   * ★★ 判据④（{@code 13 §5.2.1} 判据①）：<strong>同一个 token 连问两轮 ⇒ 只有一条会话</strong>。
   *
   * <p>★★ 这一条钉的是归属键本身：公开面的问答流**收不到 {@code conversationId}**（公开页手上只有合成串 {@code public:<token>}）⇒
   * "这一轮写进哪条会话"由服务端反解。改成"每轮新建一条"这一条立刻红 （计数变成 2），而它的症状是**刷新后永远读不回历史** —— 不报错、只是历史没了。
   */
  @Test
  void twoPublicTurnsLandInTheSameConversation() throws Exception {
    runTurnToCompletion();
    assertThat(conversationCountInDb()).as("前提：第一轮之后恰好一条会话（多一条会让下面的计数分不清是第几轮建的）").isEqualTo(1);

    runTurnToCompletion();

    assertThat(conversationCountInDb())
        .as(
            "★★ 判据④（13 §5.2.1）：公开面连问两轮必须落在**同一条**会话上（服务端按 (归属人, 应用) "
                + "找最近一条续写）。改成每轮新建一条这一条立刻红：计数会是 2，而症状是刷新后读不回历史")
        .isEqualTo(1);
  }

  /**
   * ★★ 判据⑤（{@code 13 §5.2.1} 判据②）：公开面**读得回历史**，且引用快照逐字带回来（含 {@code #27} 的密级）。
   *
   * <p>★ 三件事一起断言，因为它们各自坏掉的症状不同：
   *
   * <ol>
   *   <li><strong>读不回</strong>（今天修之前的样子）⇒ 页面刷新后是空对话；
   *   <li><strong>顺序不对</strong> ⇒ 用户问与答错位（库里按 {@code created_at} 升序，这一条要能读出来）；
   *   <li>★ <strong>引用快照丢了 {@code classification}</strong> ⇒ 历史里的引用与流里的引用**不一致**， 而"这条引用当时是什么密级"正是
   *       A4「引用可展开溯源」要读的那一格。
   * </ol>
   */
  @Test
  void publicHistoryReadsBackTheConversationWithItsReferences() throws Exception {
    runTurnToCompletion();

    HttpResponse<String> history = getMessages(token);

    assertThat(history.statusCode()).as("★ 公开面历史对**匿名**访客必须可用（正当性 = 令牌有效）").isEqualTo(200);
    String body = history.body();
    assertThat(body)
        .as("★ 契约形状：listPublicMessages 返回的是数组（不是分页对象）")
        .contains("\"role\":\"USER\"")
        .contains("\"role\":\"ASSISTANT\"");
    assertThat(indexOf(body, "\"role\":\"USER\""))
        .as("★★ 顺序：用户消息必须排在助手回答**之前**（库里按 created_at 升序，反了就是问答错位）")
        .isLessThan(indexOf(body, "\"role\":\"ASSISTANT\""));
    assertThat(body)
        .as(
            "★★ A4 溯源：历史里的引用快照必须逐字带回来 —— 含 #27 新加的 classification"
                + "（值取自夹具读出来的那一档：那条分段**继承文档密级** INTERNAL）。"
                + "少了它，历史引用与流里引用就不一致，而「这条引用当时多密」无处可读")
        .contains("\"refs\":[{")
        .contains("\"classification\":\"INTERNAL\"");
    assertThat(body)
        .as("★ 用户消息那一侧的 refs 必须是 null（不是 []）：它没有引用快照，与「零命中」是两件事")
        .contains("\"role\":\"USER\",\"content\":\"合同违约金\",\"status\":\"GENERATED\",\"refs\":null");
  }

  /**
   * ★★ 判据⑥（{@code 13 §5.2.1} 判据③）：<strong>无效 token 读历史 ⇒ 404</strong>，且与"还没问过"可分。
   *
   * <p>★ 这两件事在界面上完全不同（四态文案 vs 空对话），后端却都可能返回"空"——
   * 一个把两者糊成一个的实现，会让"链接已撤销"显示成"这里还没有人说过话"。所以：令牌无效走**四态码**， "还没问过"走 <strong>200 + {@code
   * []}</strong>。
   */
  @Test
  void unknownTokenCannotReadHistoryWhileAnEmptyChainIsAnEmptyList() throws Exception {
    HttpResponse<String> unknown = getMessages("pk_live_" + randomToken());
    assertThat(unknown.statusCode()).as("★ 不认识的令牌 ⇒ 404（四态里的第①态），不是 200 + 空列表").isEqualTo(404);

    // ★ 同一条有效 token、还没问过 ⇒ 200 + 空数组（链接是好的，只是没人说过话）
    HttpResponse<String> empty = getMessages(token);
    assertThat(empty.statusCode()).as("★ 有效令牌但还没有对话 ⇒ 200").isEqualTo(200);
    assertThat(empty.body().replace(" ", "")).as("★ 空链是 []，与'令牌无效'必须可分").contains("\"data\":[]");
  }

  // ────────────────────────── 假模型配置 ──────────────────────────

  /**
   * 把"可替换的假模型"装成容器里那个 {@link ChatStreamPort}（与 {@code PublicChatHttpTest} 同一手法）。
   *
   * <p>★ {@code @Primary} 覆盖生产的兜底实现 {@code ChatPortFallbackStreamAdapter}：**业务代码里没有一行 if**
   * ——"测试用假的、生产用真的"仍由容器装配决定。
   */
  @TestConfiguration
  static class ReplaceableFakeModelConfig {

    @Bean
    AtomicReference<ChatStreamPort> fakeModel() {
      return new AtomicReference<>();
    }
  }

  // ────────────────────────── HTTP 与库读数 ──────────────────────────

  /**
   * 开一轮公开问答，并在**异步线程**上读那条流（读完整条就结束），返回助手消息 id。
   *
   * <p>★ 为什么要异步：这一轮的正文很长（假模型逐块吐 + 块间 120ms）⇒ 同步读会把测试卡在流上， 而我们要的是"生成**中途**去发停止请求"。
   *
   * <p>★★ <strong>等的是"库里正文非空"，而且是全仓唯一那一把</strong> （{@link
   * AssistantGeneratedTextWait#await}）：本项目的口径闸门 （{@code
   * GeneratedTextWaitSingleSourceTest}）要求**驱动停止的用例必须引用它** —— 本类第一版自己写了一个 "等到状态离开
   * GENERATING"的私有等待，闸门当场红（`Expecting empty but was: [...PublicStopEndpointTest.java]`）。 ★ 那条闸门挡的正是
   * {@code #125} 那个洞：助手行在**第一个 chunk 之前**就已经是 {@code GENERATING} 了， 等状态会让"停止"赶在第一块之前（终态确实是 {@code
   * STOPPED}，但正文是空的）。
   */
  private String startPublicTurnAndWaitUntilGenerating() throws Exception {
    // ★★ 先记下"起跑线上已经有哪些助手消息"：本类的两轮落在**同一条会话**上（§5.2.1 的会话链），
    //   所以"最新一条"在第二轮会指到第一轮那条还在生成的身上（本批实测就踩到了：两轮拿到同一个 id）。
    //   ⇒ 判据必须是"**新的**那一条"，不是"最新那一条"。
    Set<String> before = assistantMessageIds();

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + CHAT_PATH))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("X-Access-Credential", token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"question\":\"合同违约金\",\"attachmentIds\":[],\"thinking\":false}",
                    StandardCharsets.UTF_8))
            .build();

    // ★ 流的读取必须发生在另一条线程上：否则我们无法在"还在生成"的时候发出停止请求。
    Thread reader =
        new Thread(
            () -> {
              try {
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
              } catch (Exception ignored) {
                // 客户端这侧读失败不影响服务端落库；判据读的是库里的那一行
              }
            },
            "public-stop-stream-reader");
    reader.setDaemon(true);
    reader.start();

    // ★ 会话要先有（**必须在发请求之后等**：会话是这一轮公开问答建的）—— 用来给唯一那把等待定位会话。
    UUID conversationId = waitUntilConversationExists();

    // ★★ 等这一轮**新出现**的那条助手消息 —— 锚点是"新出现的 id 集合"，走唯一那把等待的第二个入口。
    //   ★ 本批实测踩到的坑（必须记下来）：`AssistantGeneratedTextWait#await(jdbc, 会话, …)` 的锚点是
    //   "该会话里正文非空、按 created_at 排序的最新那一条" —— 而本类的两轮落在**同一条会话**上
    //   （§5.2.1 的会话链），第一轮那条**已经收尾、正文非空** ⇒ 第二轮调用它会**原样返回第一轮那条**
    //   （两轮拿到同一个 id，而"第二轮被停"的读数其实是在第一轮那行上取的 —— 本批实测 `round1 == round2`）。
    //   ⇒ 换成 `awaitAnyOf`：判据一个字没改（助手角色 + 正文非空），只把锚点从"会话里最新"换成"新出现的这批 id"。
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Set<String> now = assistantMessageIds();
      now.removeAll(before);
      if (!now.isEmpty()) {
        Set<UUID> fresh = new HashSet<>();
        for (String id : now) {
          fresh.add(UUID.fromString(id));
        }
        return AssistantGeneratedTextWait.awaitAnyOf(jdbc, fresh, 30_000).toString();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("夹具前提不成立：30 秒内这一轮没有新建助手消息（会话 " + conversationId + "）");
  }

  /** 归属人名下全部助手消息 id（判据只用来算"新出现的那一条"）。 */
  private Set<String> assistantMessageIds() {
    return new java.util.HashSet<>(
        jdbc.queryForList(
            "select m.id::text from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.user_id = ? and m.role = 'ASSISTANT'",
            String.class,
            ZHANGYUN));
  }

  /**
   * 等这一轮（公开面建的）会话出现，返回它的 id。
   *
   * <p>★ 只用来定位会话，**判据不是它** —— 而且它天然是"链上那一条"：两轮落在同一条会话上（§5.2.1）。
   */
  private UUID waitUntilConversationExists() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      List<UUID> found =
          jdbc.queryForList(
              "select id from cv_conversation where user_id = ? and deleted_at is null"
                  + " order by created_at desc limit 1",
              UUID.class,
              ZHANGYUN);
      if (!found.isEmpty()) {
        return found.getFirst();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("夹具前提不成立：30 秒内这一轮公开会话没有落库");
  }

  /** 等那条消息落到终态（收尾是另一条线程做的，读数必须等它）。 */
  private void waitUntilTerminal(String messageId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!"GENERATING".equals(statusInDb(messageId))) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("30 秒内那条消息没有收尾（status 仍是 GENERATING）");
  }

  private String statusInDb(String messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, messageId);
  }

  /** 归属人名下（未软删）的会话条数 —— 判据④ 的读数。 */
  private int conversationCountInDb() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from cv_conversation where user_id = ? and deleted_at is null",
            Integer.class,
            ZHANGYUN);
    return count == null ? 0 : count;
  }

  /**
   * 跑完整一轮公开问答，**等到库里那条助手消息落到终态**才算这一轮结束。
   *
   * <p>★ <strong>为什么不用"响应体里有没有 {@code event:done}"当判据</strong>（本批实测踩到过）： 这条流会先发一大段 {@code : open:}
   * 填充注释帧（{@code sse-response-header-commit-padding}）， 同步读到的响应体在缓冲上会分成多段 ⇒
   * 拿"字符串包含"当"跑完了"的判据**不可靠**（读到的是前半段， 本批第一次就是这么红的）。★ 可靠口径与判据① 完全同源：<strong>去看库里那一行</strong>
   * （终态只由收尾那条路径写）。
   */
  private void runTurnToCompletion() throws Exception {
    String messageId = startPublicTurnAndWaitUntilGenerating();
    waitUntilTerminal(messageId);
  }

  /** 读公开面历史（★ 匿名：不带 Authorization，令牌在路径里）。 */
  private HttpResponse<String> getMessages(String linkToken) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/public/" + linkToken + "/messages"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 子串下标（顺序断言用；不存在 ⇒ -1，而 -1 会让 `isLessThan` 报错，正是我们要的显性失败）。 */
  private static int indexOf(String haystack, String needle) {
    return haystack.indexOf(needle);
  }

  private String base() {
    return "http://localhost:" + port;
  }

  /** 调公开面的停止端点（★ 匿名：不带 Authorization，只带令牌在路径里）。 */
  private HttpResponse<String> postStop(String linkToken, String messageId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    base() + "/api/v1/public/" + linkToken + "/messages/" + messageId + "/stop"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private UUID seedChatModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, ?, 'CHAT', 'LOCAL_INFERENCE', 'qwen3:4b',"
            + " 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        id,
        FIXTURE_PREFIX + "对话模型");
    return id;
  }

  private UUID seedEmbeddingModel() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at) values (?, ?, 'EMBEDDING', 'LOCAL_INFERENCE',"
            + " 'bge-m3', 'http://localhost:11434/v1', 768, 'LOADED', true, now(), now())",
        id,
        FIXTURE_PREFIX + "向量模型");
    return id;
  }

  /**
   * 一条 {@code SUCCEEDED} 的文档 + 一条分段（夹具：本类只需要它存在，检索本身不是本类的判据）。
   *
   * <p>★ 形状逐字照 {@code PublicChatHttpTest#seedDocumentAndSegment}（包括"密级从文档继承"那条：
   * 写死一个常量就会把被测的谓词换成另一条），只去掉本类用不到的"四条密级文档"。
   */
  private void seedDocumentAndSegment(UUID kbId) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    UUID docId =
        DocumentFixture.insertSucceeded(jdbc, kbId.toString(), FIXTURE_PREFIX + "文档", "INTERNAL");
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            + " values (?, ?, ?, 'KB', ?, 0, '第一条', '第一章', '违约金按日计收，内部档口径',"
            + " '违约金按日计收，内部档口径', ?, ?, false, false, false, true,"
            + " 'PENDING_EMBEDDING', now(), now(),"
            + " (select classification from kb_document where id = ?))",
        UUID.randomUUID(),
        docId,
        kbId,
        kbId,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        16,
        docId);
  }

  private static String randomToken() {
    byte[] entropy = new byte[32];
    new java.security.SecureRandom().nextBytes(entropy);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }
}
