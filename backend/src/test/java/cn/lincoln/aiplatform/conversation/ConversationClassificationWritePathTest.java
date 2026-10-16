package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatTurnStore;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * ★★ {@code #27}：<strong>会话累计密级的生产写入口</strong> —— 一轮问答的引用集合里有 {@code SECRET} ⇒ 库里 {@code
 * cv_conversation.max_classification} 必须是 {@code SECRET}。
 *
 * <h3>★★ 它钉的是"最后一格"，而不是"某个方法存在"</h3>
 *
 * <p>在这条用例之前，这件事的每一件零件都已就位：列在（{@code V32}）、领域守卫在（{@code Conversation#raiseClassification}）、库侧单调
 * UPDATE 在（{@code ConversationRepository#raiseClassification}）—— 而 <strong>{@code backend/src/main}
 * 里零调用点</strong>： "本轮引用集合的最高密级"到不了落库那一刻，因为快照里<strong>没有</strong>密级这一格。
 * 所以本类的判据只能是"<strong>一次真问答之后去库里读那一列</strong>"，读内存对象一律不算。
 *
 * <h3>★★ 判据的三条路径（缺哪条都留一个洞）</h3>
 *
 * <ol>
 *   <li>{@link #productionQuestionRaisesTheConversationToTheSecretOfItsReferences()} —— <strong>真
 *       HTTP 全链路</strong> （假检索给一条 {@code SECRET} 引用）：这一条证明"生产装配上真的有人喂"；
 *   <li>{@link #allPublicReferencesLeaveTheColumnUntouched()} —— 全 {@code PUBLIC} ⇒
 *       那一列<strong>不动</strong>（不降）；
 *   <li>{@link #neverArrivedReferencesLeaveTheColumnUntouched()} —— {@code references == null}
 *       （引用从没到过）⇒ 也<strong>不动</strong>，且它与"零命中"（空列表）在库里是同一结果、在语义上不是；
 *   <li>★ {@link #theHighestReferenceOfTheRoundLandsInTheColumn()} ——
 *       多档引用时必须取<strong>最高</strong>那一档 （{@code INTERNAL}+{@code SECRET} ⇒ 库里 {@code SECRET}）。★
 *       这一条是<strong>金丝雀②唯一能红的落点</strong>， 详见该方法的 javadoc（第一版判据在"max 是
 *       PUBLIC"那种输入上失去了分辨力，被适配器的早退掩盖成绿）。
 * </ol>
 *
 * <h3>★★ 金丝雀（红在哪一行 + 逐字消息都要核）</h3>
 *
 * <p><strong>① 删掉生产调用点</strong>（{@code ChatTurnWriter#finish} 里那句 {@code
 * conversations.raiseClassification(...)}） ⇒ 第 1 条用例红在它自己的库级断言上：{@code expected: "SECRET" but was:
 * "PUBLIC"}。
 *
 * <p><strong>② 把 {@code maxClassificationOf} 的折叠方向改反</strong>（{@code max} → {@code
 * ClassificationLevel.narrower}）⇒ <strong>只有</strong>判据④ 红：{@code expected: "SECRET" but was:
 * "INTERNAL"}。 ★ 本批实测：这一条反证<strong>第一次做时四条全绿</strong> —— 因为当时的判据④ 选的输入会让错误值落在 {@code PUBLIC}
 * 上，而适配器对 {@code lower.isEmpty()} 早退（{@code ConversationRepositoryAdapter:58-60}）⇒ 库里"不动"， 断言照样绿。⇒
 * 记下来的口径：<strong>"不动"这类判据必须问一句"错误实现会不会也正好不动"</strong>。
 *
 * <p>★ 另一条反证（把折叠换成库侧 {@code greatest()}）**没有**写进本类：那需要改生产 SQL， 而"PG 的字典序 ≠ 密级次序"（{@code
 * greatest('CONFIDENTIAL','SECRET') = 'SECRET'}）已由 {@code ConversationClassificationWriteTest}
 * 的等价用例与一条改坏必红的反证钉住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      // ★ 与 ChatStreamHttpTest 同一条理由：本类验的是问答链路，登录只是拿令牌的手段。
      "app.security.captcha.enabled=false",
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=15000",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class ConversationClassificationWritePathTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id（造"自己的会话"与清理用）。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** 假的命中分段 id（全类共用一个：假检索把它原样回给装配点）。 */
  private static final UUID SEGMENT_ID = UUID.fromString("27000000-0000-4000-8000-000000000027");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired ChatTurnWriter writer;

  /** ★ 走端口读会话（与生产调用点同一个对象）：本类要验的是"落库那一刻"，不是某个类的形状。 */
  @Autowired ChatTurnStore turns;

  @Autowired ConversationRepository conversations;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "delete from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?::uuid)",
        ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from ai_model where name like 'ConvClass 夹具%'");
  }

  // ────────────────────────── ① 真 HTTP 全链路 ──────────────────────────

  /**
   * ★★ 判据①（{@code #27}）：一次真问答的引用里有 {@code SECRET} ⇒ 库里那一列 = {@code SECRET}。
   *
   * <p>★ 为什么必须真 HTTP：{@code retrieve} → 装配点（{@code card.classification()}）→ 引用集合 → {@code finish}
   * 这一整条链上任何一格断了，"密级到不了落库那一刻"，而<strong>直接调 {@code finish} 的用例读不出来</strong> （它会把那一格跳过去）——
   * 那种写法正是"看起来有读数"。
   */
  @Test
  void productionQuestionRaisesTheConversationToTheSecretOfItsReferences() throws Exception {
    seedChatModel();
    String token = login();

    HttpResponse<String> response =
        postChat(token, "{\"question\":\"差旅住宿标准\",\"attachmentIds\":[],\"thinking\":false}");

    assertThat(response.statusCode()).as("SSE 端点必须 200（登录面拿真令牌）").isEqualTo(200);
    String refsFrame = dataOf(response.body(), "refs");
    assertThat(refsFrame)
        .as(
            "★ 线上必须真的带 classification 这一格 —— ChatStreamWriter#refsOf 是**手写映射**，"
                + "给 record 加了分量却忘了加到这里，症状是「类型上有、线上没有」，而两端测试都可能照样绿")
        .contains("\"classification\":\"SECRET\"");

    UUID conversationId = conversationIdOfTheLatestMessage();
    assertThat(maxClassificationInDb(conversationId))
        .as(
            "★★ 判据①（#27）：本轮的引用含 SECRET ⇒ 会话累计密级必须是 SECRET。把 ChatTurnWriter#finish 里那句"
                + " raiseClassification 删掉，这一条立刻红（expected SECRET but was PUBLIC）")
        .isEqualTo("SECRET");
  }

  // ────────────────────────── ② / ③ 不抬升的两条路径 ──────────────────────────

  /**
   * ★★ 判据②：全部引用都是 {@code PUBLIC} ⇒ 那一列<strong>保持 PUBLIC 不动</strong>（不降、也不抬）。
   *
   * <p>★ 这一条与判据①是<strong>同一处代码</strong>的两个方向：只验"能抬"会漏掉"把 PUBLIC 当成真的值写进去" （症状：会话水位被一次公开问答改掉）。
   */
  @Test
  void allPublicReferencesLeaveTheColumnUntouched() {
    UUID id = newConversation();
    UUID messageId = prepareMessage(id);

    turns.finish(
        messageId,
        StreamTerminationPath.COMPLETE,
        "根据《差旅管理规定》第三条",
        0,
        List.of(ref(ClassificationLevel.PUBLIC)),
        TokenUsage.zero(),
        1200L,
        null,
        null);

    assertThat(maxClassificationInDb(id))
        .as("★ 全 PUBLIC 的引用不许把会话水位抬高（那一列必须还是建会话时的 PUBLIC）")
        .isEqualTo("PUBLIC");
    assertThat(messageStatusInDb(messageId)).as("前提：这一轮正常收尾（判据不是从失败路径上读的）").isEqualTo("GENERATED");
  }

  /**
   * ★★ 判据④（金丝雀②逼出来的那一格）：**多档引用时必须取"最高"那一档** —— 库里那一列必须等于 {@code max(本轮 refs)}，而不是 {@code min}、不是现值。
   *
   * <h3>★★ 为什么单独一条（以及第一版为什么会写成假绿）</h3>
   *
   * <p>本批实测过一次：把 {@code maxClassificationOf} 里的 {@code max} 改成 {@code
   * ClassificationLevel.narrower}（取更<strong>低</strong>的那一档）——<strong>四条用例全绿</strong>。
   * 根因可逐字核对，而且是两层：
   *
   * <ol>
   *   <li>判据① 只有<strong>一条</strong>引用 ⇒ {@code max(单元素) == narrower(单元素)}，两者不可分；
   *   <li>★ 第一版这条判据写的是"前置水位 {@code CONFIDENTIAL} + 引用 = {@code PUBLIC}+{@code SECRET} ⇒
   *       必须不动"，而取低之后落成 {@code PUBLIC} —— 偏偏 {@code PUBLIC} 会让适配器 {@link
   *       cn.lincoln.aiplatform.conversation.infrastructure.ConversationRepositoryAdapter#raiseClassification}
   *       在 {@code lower.isEmpty()} 处<strong>早退</strong>（没有任何现值能严格低于 PUBLIC）⇒ 库里确实"不动" ⇒
   *       <strong>断言照样绿</strong>。也就是说那版判据在"最容易被违反的场景（max 是 PUBLIC）"上<strong>失去了分辨力</strong>。
   * </ol>
   *
   * <p>⇒ 正确形状是让两种实现落成<strong>两个都非 PUBLIC、且都能真的写进库</strong>的值： 前置水位 {@code PUBLIC}（默认值，不会被早退挡），引用 =
   * {@code INTERNAL} + {@code SECRET}。
   *
   * <ul>
   *   <li>正确实现：{@code max(INTERNAL, SECRET) = SECRET} ⇒ 库里落成 {@code SECRET}；
   *   <li>取低的实现：{@code narrower(INTERNAL, SECRET) = INTERNAL} ⇒ 库里落成 {@code INTERNAL} ——
   *       <strong>低于真实最高档</strong>，一次静默漏抬（会话本该是秘密，却停在内部）。
   * </ul>
   */
  @Test
  void theHighestReferenceOfTheRoundLandsInTheColumn() {
    UUID id = newConversation();
    UUID messageId = prepareMessage(id);

    turns.finish(
        messageId,
        StreamTerminationPath.COMPLETE,
        "根据《差旅管理规定》第三条",
        0,
        List.of(ref(1, ClassificationLevel.INTERNAL), ref(2, ClassificationLevel.SECRET)),
        TokenUsage.zero(),
        1100L,
        null,
        null);

    assertThat(maxClassificationInDb(id))
        .as(
            "★★ 本轮引用 = INTERNAL + SECRET ⇒ 库里必须是 SECRET（取最高那一档）。"
                + "把 maxClassificationOf 的 max 换成 narrower 这一条立刻红（实际落成 INTERNAL = 静默漏抬）")
        .isEqualTo("SECRET");
  }

  /**
   * ★★ 判据③：{@code references == null}（引用<strong>从没到过</strong>）⇒ 不动。
   *
   * <p>★ 它与空列表在库里是同一结果、在语义上不是：{@code null} = 检索没跑起来 / 没回来；{@code []} = 明确的零命中。 两个都不该改会话水位 —— 但"把
   * null 当成空集合"这种写法会让将来某次改动把两者糊成一个（{@code MessagePersistenceTest} 的 {@code
   * zeroHitReferencesAreDistinctFromNeverArrivedReferences} 钉的是落库形状这一半）。
   *
   * <p>★ 前置：会话先被抬到 {@code CONFIDENTIAL} ⇒ 这条用例才能真正验"不动"（否则它读到的 PUBLIC 无法区分 "没动"与"没跑"）。
   */
  @Test
  void neverArrivedReferencesLeaveTheColumnUntouched() {
    UUID id = newConversation();
    conversations.raiseClassification(id, ClassificationLevel.CONFIDENTIAL);
    UUID messageId = prepareMessage(id);

    turns.finish(
        messageId,
        StreamTerminationPath.COMPLETE,
        "这一轮没有引用",
        0,
        null,
        TokenUsage.zero(),
        900L,
        null,
        null);

    assertThat(maxClassificationInDb(id))
        .as("★ 引用从没到过 ⇒ 水位一个字都不许动（它已经有一条 CONFIDENTIAL 的历史）")
        .isEqualTo("CONFIDENTIAL");
  }

  // ────────────────────────── 假检索（唯一那份素材来源）──────────────────────────

  /**
   * 把"给出带密级引用"的假检索装成容器里那个 {@link SegmentSearchPort}。
   *
   * <p>★ {@code @Primary} 覆盖生产的 {@code JdbcSegmentSearchQuery} —— "测试用假的、生产用真的"仍由容器装配决定， 业务代码里没有一行
   * if（与 {@code ChatStreamUsageHttpTest} 同一条口径）。
   *
   * <p>★ 只重写"给什么素材"这三个方法，域解析走 {@link RetrievalScope#shared}（与全文路同一条口径）； {@code visibleAmong}
   * 也照实回全部候选 —— 假检索不该在<strong>可见性</strong>上加戏，那是别的账的判据。
   */
  @TestConfiguration
  static class SecretSegmentSearchConfig {

    @Bean
    @Primary
    SegmentSearchPort secretSegmentSearchPort() {
      return new SecretSegmentSearchPort();
    }
  }

  /** 一条 {@code SECRET} 引用的素材来源（正文与标记都写死，便于逐字断言）。 */
  static final class SecretSegmentSearchPort implements SegmentSearchPort {

    private static final String CONTENT = "差旅住宿标准为每人每天500元，超出部分由个人承担。";

    /** ★ 本假检索"能检索到的库"（夹具值）。见 {@link #retrievalScope} 的理由 —— 它必须非空。 */
    private static final UUID KB_ID = UUID.fromString("27000000-0000-4000-8000-0000000000b1");

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB_ID);
    }

    /**
     * ★★ 域必须<strong>非空</strong>，哪怕真问答没带任何 kbIds。
     *
     * <p>理由可逐字核对：{@code ChatService#retrieve} 拿到的 {@code requestedKbIds} 在"通用问答"下是空集合 （请求体里没有
     * kbIds），而 {@code RetrievalScope#isEmpty()} 判的是 "共用库空 <b>且</b> 个人库 null <b>且</b> conversationId
     * null"⇒ {@code shared(空)} 会被判成<strong>空域</strong>， 于是一次检索都不发、直接零引用（那条早退还带一条
     * INFO："本轮没有可检索的知识库范围"）。 ★ 也就是说这一格在真实环境里代表的是"用户确实没有任何可见库"——那是<strong>另一个账</strong>的判据，
     * 不该混进本类"密级有没有到落库那一刻"的读数里。
     */
    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      return RetrievalScope.shared(List.of(KB_ID));
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      return List.of(new Scored(SEGMENT_ID, 0.93));
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return List.of(
          new Card(
              SEGMENT_ID,
              "差旅住宿标准章节",
              CONTENT,
              "ConvClass 差旅制度文档",
              "ConvClass 夹具库",
              UUID.fromString("27000000-0000-4000-8000-0000000000d1"),
              UUID.fromString("27000000-0000-4000-8000-0000000000b1"),
              ClassificationLevel.SECRET));
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
      return Set.copyOf(candidateIds);
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不走向量路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类不走向量路");
    }
  }

  // ────────────────────────── 夹具与库读数 ──────────────────────────

  private static SourceRef ref(ClassificationLevel classification) {
    return ref(1, classification);
  }

  /** 带 {@code index} 的重载：判据④ 要两条不同档的引用，而 {@code [N]} 必须从 1 连续递增。 */
  private static SourceRef ref(int index, ClassificationLevel classification) {
    return new SourceRef(
        index,
        "差旅住宿标准章节",
        "ConvClass 差旅制度文档",
        "ConvClass 夹具库",
        0.93,
        1,
        "差旅住宿标准为每人每天500元",
        SEGMENT_ID.toString(),
        "27000000-0000-4000-8000-0000000000d1",
        "27000000-0000-4000-8000-0000000000b1",
        classification);
  }

  /**
   * 建一个会话（直插：本类不验建会话那一段，只验"抬升"）。
   *
   * <p>★ 走 SQL 而不是端口：{@code ConversationRepository#save} 也会写 {@code max_classification} （{@code
   * #108} 明确留下的残，见该 Issue 的评论），用它造夹具会让"那一列是怎么来的"变得不清楚。
   */
  private UUID newConversation() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, message_count, max_classification,"
            + " created_at, updated_at) values (?::uuid, ?::uuid, 'ConvClass 夹具会话', 0, 'PUBLIC',"
            + " now(), now())",
        id.toString(),
        ADMIN_ID.toString());
    return id;
  }

  /**
   * 落一条助手消息（{@code GENERATING}）—— {@code finish} 的前置：它要求"助手消息已经存在"。
   *
   * <p>★ 直插而不是走 {@code prepare}：{@code prepare} 会顺带写两个计数器（{@code #108} 的另一半），
   * 那会让本类的读数混进"计数器对不对"这件事（它有自己的测试类）。
   */
  private UUID prepareMessage(UUID conversationId) {
    UUID messageId = UUID.randomUUID();
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status, created_at,"
            + " updated_at) values (?::uuid, ?::uuid, 'ASSISTANT', '', 'GENERATING', now(), now())",
        messageId.toString(),
        conversationId.toString());
    return messageId;
  }

  private String maxClassificationInDb(UUID conversationId) {
    return jdbc.queryForObject(
        "select max_classification from cv_conversation where id = ?::uuid",
        String.class,
        conversationId.toString());
  }

  private String messageStatusInDb(UUID messageId) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, messageId.toString());
  }

  /** 这一轮那条助手消息挂在哪个会话上（用真 HTTP 跑完之后唯一的把手）。 */
  private UUID conversationIdOfTheLatestMessage() {
    return jdbc.queryForObject(
        "select conversation_id from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?::uuid)"
            + " order by created_at desc limit 1",
        UUID.class,
        ADMIN_ID.toString());
  }

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

  /** 从整条 SSE 流里取某个事件名的 {@code data}（手写解析，与 {@code ChatStreamHttpTest} 同一手法）。 */
  private static String dataOf(String stream, String event) {
    for (String block : stream.split("\n\n")) {
      String name = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          data = line.substring("data:".length()).trim();
        }
      }
      if (event.equals(name)) {
        return data;
      }
    }
    throw new AssertionError("响应里没有 event: " + event + " 的帧；整条流：\n" + stream);
  }

  /** 从 JSON 里取一个顶层字符串字段（与 {@code ChatStreamHttpTest} / {@code ChatStreamUsageHttpTest} 同一手法）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }

  /**
   * 造一个可用的对话模型（本类是通用问答：没有应用绑定 ⇒ {@code pickChatModel()} 要挑得到一个）。
   *
   * <p>★ 假检索不看模型，但 {@code ChatTurnWriter#prepare} 会在"一个可用的对话模型都没有"时抛 400 ——
   * 那会让这条用例红在一个与密级无关的原因上（而且看起来像"链路坏了"）。
   */
  private void seedChatModel() {
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, 'ConvClass 夹具对话模型', 'CHAT',"
            + " 'LOCAL_INFERENCE', 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(),"
            + " now())",
        UUID.randomUUID());
  }
}
