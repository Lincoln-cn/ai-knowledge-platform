package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
            + " visibility, published, prologue, quick_questions, created_at, updated_at)"
            + " values (?, ?, '🤖', '公开停止夹具应用', 'SIMPLE', ?, ?, 'PRIVATE', true,"
            + " '你好', array[]::text[], now(), now())",
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

    @Bean
    @Primary
    ChatStreamPort replaceableChatStreamPort(AtomicReference<ChatStreamPort> holder) {
      return (modelId, prompt, timeout, callback) -> {
        ChatStreamPort current = holder.get();
        if (current == null) {
          throw new IllegalStateException("本用例没有装假模型（fixture 缺失）");
        }
        current.stream(modelId, prompt, timeout, callback);
      };
    }
  }

  // ────────────────────────── HTTP 与库读数 ──────────────────────────

  /**
   * 开一轮公开问答，并在**异步线程**上读那条流（读完整条就结束），返回助手消息 id。
   *
   * <p>★ 为什么要异步：这一轮的正文很长（假模型逐块吐 + 块间 120ms）⇒ 同步读会把测试卡在流上， 而我们要的是"生成**中途**去发停止请求"。
   */
  private String startPublicTurnAndWaitUntilGenerating() throws Exception {
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

    String messageId = waitUntilGenerating();
    return messageId;
  }

  /** 等到库里出现一条 {@code GENERATING} 的助手消息，返回它的 id。 */
  private String waitUntilGenerating() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      String id =
          jdbc.query(
              "select id::text from cv_message where role = 'ASSISTANT' and status = 'GENERATING'"
                  + " and conversation_id in (select id from cv_conversation where user_id = ?)"
                  + " order by created_at desc limit 1",
              rs -> rs.next() ? rs.getString(1) : null,
              ZHANGYUN);
      if (id != null) {
        return id;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("夹具前提不成立：30 秒内没有出现 GENERATING 的助手消息（这一轮没开起来）");
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
    UUID docId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_document (id, kb_id, scope_kind, scope_id, name, source, doc_type, org_id,"
            + " classification, status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?, ?, 'KB', ?, ?, 'local', '制度', ?::uuid, 'INTERNAL', 'SUCCEEDED', 0, 1,"
            + " true, now(), now())",
        docId,
        kbId,
        kbId,
        FIXTURE_PREFIX + "文档",
        ROOT_ORG);
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
