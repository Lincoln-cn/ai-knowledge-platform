package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import cn.lincoln.aiplatform.conversation.support.FakeChatPorts;
import cn.lincoln.aiplatform.conversation.support.FakeChatStreamPorts;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code #187} 判据 4 的<strong>真栈读数</strong>：{@code POST /api/v1/files}
 * 上传的会话附件，在<strong>发起问答时</strong> 真的变成一条可检索的会话域文档。
 *
 * <h3>★★ 这条判据为什么必须走真栈（而不是单元测试）</h3>
 *
 * <p>要证明的是<strong>三段之间真的接上了</strong>：
 *
 * <ol>
 *   <li>{@code ChatStreamBody.attachmentIds} 被控制器<strong>消费</strong>（此前是"收下但不消费"）；
 *   <li>附件被登记成 {@code kb_document} 的第三域（{@code scope_kind='CONVERSATION'}、{@code
 *       scope_id}=会话、{@code owner_id}=上传者、{@code kb_id} 为空），并解析出 {@code kb_segment}；
 *   <li>该分段<strong>进得了检索候选</strong>（{@code 13 §11.3} 第三支谓词对它生效），并真的出现在这一轮回答的<strong>引用</strong>里。
 * </ol>
 *
 * <p>第 1 段只有真 HTTP 能读（请求体 → 控制器 → 服务），第 2、3 段只有真 PostgreSQL 能读（Flyway 建的生成列 {@code
 * conversation_id}、{@code content_tsv}、CHECK 约束都在库里）。三者缺一，这条判据就只是"我写了一个方法"。
 *
 * <h3>★★ 假模型（不启任何长驻服务）</h3>
 *
 * <p>用 {@link FakeChatStreamPorts#oneShot()} 替掉 {@code ChatStreamPort}：本类判据与"模型说了什么"无关，只与"问答这一轮
 * 真的开始了"有关。真模型（或 {@code tools/openai_stub.py}）给不了确定性，而项目纪律是<strong>假模型就用测试里的假实现</strong>。
 *
 * <h3>★★ 本类断言到哪一层（诚实边界，逐字）</h3>
 *
 * <p>断言到四层：<strong>(a)</strong> 库里出现该会话域的 {@code kb_document} 与含金丝雀词的 {@code kb_segment}；
 * <strong>(b)</strong> 用 {@code SegmentSearchPort#fullText}（装配真 {@code RetrievalScope}，身份经 {@code
 * SecurityContexts} 装成 admin）能把这条分段检索出来，{@code load()} 回来的卡片 {@code kbId()} 为空、{@code docName()}
 * 是上传时的文件名；<strong>(c)</strong> {@code POST /api/v1/conversations/chat} 这一轮的 SSE {@code refs}
 * 帧里有它，且 {@code cv_message.reference_snapshot} 里能查到一条 {@code documentId} 指向该文档、{@code kbId} 为空的条目；
 * <strong>(d)</strong> 同一份附件连问两次只登记一次（幂等）。
 *
 * <p><strong>★ (c) 的配置前提（必须写在读数旁边）</strong>：{@code as_assistant.retrieval_threshold} 的表默认值是 {@code
 * 0.60}（{@code V29__assistant_app.sql:46}），而全文那一路给的是 {@code ts_rank} 分（实测 {@code 0.0x}）⇒ 默认配置下命中会被
 * {@code ChatService#retrieve} 的 {@code hit.score() < threshold} 整批丢掉，引用恒为空。本类夹具把那 一格显式设成 {@code
 * 0.00}（{@code seedAppBoundToKb} 的注释写了为什么）。也就是说：<u>(c) 证明的是"链路把这条分段交给了引用 组装"，不是"默认阈值下也会出现引用"</u> ——
 * 后者是产品配置问题，属 {@code #187} 之外的账。
 *
 * <p><strong>没有断言到</strong>的部分，逐条：① 真模型读了这条引用之后的<u>答案内容</u>（本类用 {@link
 * FakeChatStreamPorts#oneShot()}，断言的是链路而不是模型）；② 向量路（{@code
 * RetrievalScope.CONVERSATION_SCOPE_IN_VECTOR_PATH = false} 是刻意关的，{@code
 * RetrievalVectorScopeBoundaryTest} 钉着它）；③ 访客/分享面（{@code 13 §11.7} 判据 2 属另一条账）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      // ★ 与 SecurityChainTest / FileUploadHttpTest 同一条理由：本类验的是附件链路，登录只是拿令牌的手段
      "app.security.captcha.enabled=false",
      "app.files.root=target/test-files-187",
      "app.chat.heartbeat-interval-ms=300",
      "app.chat.idle-timeout-ms=2000",
      "app.chat.flush-interval-ms=60",
      "app.chat.flush-chars=200"
    })
class ConversationAttachmentRetrievalHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 种子账号（{@code V4}）。 */
  private static final String ADMIN = "admin";

  private static final String ADMIN_PASSWORD = "admin";

  /** {@code V4} 里 admin 的 id。 */
  private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** 附件正文里的<strong>金丝雀词</strong>：它只可能来自附件的字节，不可能来自任何种子数据。 */
  private static final String CANARY = "ZQXLAMP9";

  /** 附件文件名（★ 纯 ASCII：multipart 的 {@code filename} 走 UTF-8 会引入与本判据无关的编码变量）。 */
  private static final String ATTACHMENT_NAME = "attachment-187.txt";

  /** 附件正文：一句话 + 金丝雀词。Tika 对 {@code .txt} 直出正文。 */
  private static final String ATTACHMENT_TEXT =
      "本份附件的验收口径：" + CANARY + " 是这次会话里唯一的关键词，差旅住宿标准按每人每天 500 元执行。";

  /** 问题里**必须**含金丝雀词：全文检索的 tsquery 由它切词（{@code JdbcSegmentSearchQuery#lexemesOf}）。 */
  private static final String QUESTION = CANARY + " 这句话讲的是什么？";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  /** 替换生产 {@code ChatStreamPort}（含 @Primary 的 UpstreamStreamingChatAdapter，避免两个 @Primary 冲突）。 */
  @MockitoBean ChatStreamPort chatStreamPort;

  /**
   * 查询理解也走模型端口（{@code ChatPort#complete}）—— 同一「假模型不打真上游」口径（{@code #297}）： 桩的 {@code pickChatModel}
   * 默认返回 {@code Optional.empty} ⇒ 查询理解立刻 D4 退回原句单路，不发任何上游请求。
   */
  @MockitoBean ChatPort chatPort;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * 这一类的假模型：一次吐完、立刻收尾。
   *
   * <p>★ 四个重载都要转（生产链上走哪一个由应用/模式决定）；漏掉一个就会变成"mock 返回 null ⇒ 流里一个字节都没有"， 而那种红看起来像"检索坏了"。
   */
  @BeforeEach
  void wireFakeModel() {
    FakeChatPorts.wire(chatPort);
    ChatStreamPort fake = FakeChatStreamPorts.oneShot();
    doAnswer(
        invocation -> {
          fake.stream(
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
          fake.stream(
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
              fake.streamConversation(
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
              fake.streamConversation(
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

  /**
   * 清掉本类造的行。
   *
   * <p>★ 顺序按外键：{@code kb_segment} → {@code kb_document} → {@code kb_knowledge_base}；{@code
   * cv_file}/{@code cv_message}/{@code cv_conversation} 按 admin 清（真栈里别的类也用同一个种子账号）。
   *
   * <p>★ 会话域的分段与文档<strong>没有 kb_id</strong>（{@code 13 §11.3}），所以不能按库删 —— 必须按 {@code
   * scope_kind='CONVERSATION'} 删，否则本类会把自己造的行留在库里，下一条用例的 "只登记一次"断言就会读成失败。
   */
  @AfterEach
  void cleanUp() {
    jdbc.update("delete from kb_segment where scope_kind = 'CONVERSATION'");
    jdbc.update("delete from kb_document where scope_kind = 'CONVERSATION'");
    jdbc.update(
        "delete from as_assistant_kb where assistant_id in"
            + " (select id from as_assistant where name like '附件夹具%')");
    jdbc.update("delete from as_assistant where name like '附件夹具%'");
    jdbc.update(
        "delete from kb_segment where document_id in (select id from kb_document where kb_id in"
            + " (select id from kb_knowledge_base where name like '附件夹具%'))");
    jdbc.update(
        "delete from kb_document where kb_id in (select id from kb_knowledge_base where name like"
            + " '附件夹具%')");
    jdbc.update("delete from kb_knowledge_base where name like '附件夹具%'");
    jdbc.update("delete from ai_model where name like '附件夹具%'");
    jdbc.update(
        "delete from cv_message where conversation_id in"
            + " (select id from cv_conversation where user_id = ?::uuid)",
        ADMIN_ID);
    jdbc.update("delete from cv_conversation where user_id = ?::uuid", ADMIN_ID);
    jdbc.update("delete from cv_file where owner_id = ?::uuid", ADMIN_ID);
  }

  // ────────────────────────── 判据 4 ──────────────────────────

  /**
   * ★★ 核心用例：上传 → 带 {@code attachmentIds} 问答 → 会话域文档与分段落库 → 该分段进检索候选 → 幂等。
   *
   * <p>★ 可证伪：把 {@code ChatController#chat} 里那行 {@code attachments.register(...)} 去掉 ⇒ 第 ① ② ③ 段的断言
   * （文档行、分段、检索候选）<strong>全部</strong>红。本批的反向验证就是这么做的（读数写在提交消息与报告里）。
   */
  @Test
  void anUploadedAttachmentBecomesARetrievableConversationDocument() throws Exception {
    String token = login();
    UUID appId = seedAppBoundToKb();
    UUID fileId = upload(token, ATTACHMENT_NAME, ATTACHMENT_TEXT);
    UUID conversationId = createConversation(token, appId);

    HttpResponse<String> response =
        postSse(token, chatBody(appId, conversationId, QUESTION, List.of(fileId.toString())));
    assertThat(response.statusCode())
        .as("SSE 端点必须 200 —— 附件登记发生在流开始之前，它失败会是一个 4xx 包络")
        .isEqualTo(200);
    assertThat(response.body())
        .as("★ 流的终态：假模型一次吐完 ⇒ 必须收在 done，且不是 error")
        .contains("event: done")
        .doesNotContain("event: error");

    // ── ① kb_document：这一轮真的登记了一条会话域文档 ──
    List<Map<String, Object>> documents =
        jdbc.queryForList(
            "select id, kb_id, scope_kind, scope_id, owner_id, name, doc_type, classification,"
                + " status from kb_document where scope_kind = 'CONVERSATION' and scope_id ="
                + " ?::uuid",
            conversationId);
    assertThat(documents)
        .as(
            "★★ 判据 4 的第一段：attachmentIds 被消费了 —— 库里必须出现**恰好一条**该会话的会话域文档"
                + "（多于一条说明幂等没生效；零条说明控制器没接线）")
        .hasSize(1);
    Map<String, Object> document = documents.get(0);
    assertThat(document.get("kb_id"))
        .as("★ 13 §11.2：会话临时只有 kb_document 行、没有 kb_knowledge_base 行 ⇒ kb_id 必须为 NULL（V34 的 CHECK）")
        .isNull();
    assertThat(document.get("owner_id"))
        .as("★ 13 §11.3 第三支谓词按 owner_id 收窄 ⇒ 它必须是上传者本人（取错人 = 谁都检索不到）")
        .isEqualTo(ADMIN_ID);
    assertThat(document.get("name")).as("★ 显示名进分段前缀与引用标题 ⇒ 必须是上传时的文件名").isEqualTo(ATTACHMENT_NAME);
    assertThat(document.get("doc_type"))
        .as(
            "★ 口径 ①：会话附件的文种是一个固定常量（只为满足 V13 的 NOT NULL）；"
                + "★ 它刻意不是「会话附件」那四个字 —— 显示名归 SourceLabels 独占，两者混用会让"
                + "「值只有一处」变成两处真相（SourceLabelsTest 钉着字面量唯一）")
        .isEqualTo("临时附件");
    // ★ 口径 ② 的读数：附件密级 = 会话当前密级（取值相等：既不低于下限，也不凭空抬升）
    String conversationMaxClassification =
        jdbc.queryForObject(
            "select max_classification from cv_conversation where id = ?::uuid",
            String.class,
            conversationId);
    assertThat(document.get("classification"))
        .as(
            "★★ 口径 ②：附件密级必须等于会话当前密级（13 §11.5 ① 的'不许低于'取下限等号；"
                + "若这里读到一个更高的值，说明有人凭空抬了密级，而抬升的写侧只许是 raiseClassification）")
        .isEqualTo(conversationMaxClassification);
    assertThat(document.get("status"))
        .as("★ 解析 + 分段必须**同步**完成（异步的话第一次问答必然检索不到，而'检索不到'与'附件里没有'在用户眼里一样）")
        .isEqualTo("PENDING_EMBEDDING");

    UUID documentId = (UUID) document.get("id");

    // ── ② kb_segment：附件正文真的被解析成了分段 ──
    List<Map<String, Object>> segments =
        jdbc.queryForList(
            "select id, content, search_text, kb_id, scope_kind, scope_id, owner_id, classification"
                + " from kb_segment where document_id = ?::uuid",
            documentId);
    assertThat(segments).as("★ 解析 + 分块必须留下分段（零分段 = 附件进了库却永远检索不到）").isNotEmpty();
    assertThat(segments)
        .as("★★ 金丝雀词必须出现在分段正文里 —— 它只可能来自上传的字节")
        .anySatisfy(row -> assertThat((String) row.get("content")).contains(CANARY));
    assertThat(segments)
        .as("★ 分段的域形状必须与文档一致（会话域：kb_id 为空、scope_id = 会话、owner_id = 上传者）")
        .allSatisfy(
            row -> {
              assertThat(row.get("kb_id")).isNull();
              assertThat(row.get("scope_kind")).isEqualTo("CONVERSATION");
              assertThat(row.get("scope_id")).isEqualTo(conversationId);
              assertThat(row.get("owner_id")).isEqualTo(ADMIN_ID);
            });

    // ── ③ 检索候选：它进得了 ChatService#retrieve 用的那个端口 ──
    List<UUID> hitIds =
        SecurityContexts.asUser(
            ADMIN_ID,
            ClassificationLevel.INTERNAL,
            () -> {
              // ★ 与 ChatService#retrieve 同一个装配点（13 §11.4.1：域解析唯一一处）
              RetrievalScope scope = segmentSearch.retrievalScope(List.of(), conversationId, false);
              return segmentSearch
                  .fullText(new SegmentSearchPort.TextQuery(CANARY, scope, 10))
                  .stream()
                  .map(SegmentSearchPort.Scored::segmentId)
                  .toList();
            });
    assertThat(hitIds)
        .as("★★ 判据 4 的第二段：附件分段必须进入检索候选。零命中说明第三支谓词对它不成立" + "（scope_id / owner_id / 密级 / 标签四者之一装错了）")
        .isNotEmpty();

    List<SegmentSearchPort.Card> cards =
        SecurityContexts.asUser(
            ADMIN_ID, ClassificationLevel.INTERNAL, () -> segmentSearch.load(hitIds));
    assertThat(cards)
        .as("★★ 卡片里必须能看到附件正文，且 kbId 为空 —— 后者正是后端补「会话附件」这个显示名的**结构性判据**")
        .anySatisfy(
            card -> {
              assertThat(card.content()).contains(CANARY);
              assertThat(card.kbId()).isNull();
              assertThat(card.docName()).isEqualTo(ATTACHMENT_NAME);
            });

    // ── ③-b 引用：回答的引用集合里真的出现了它 ──
    //
    // ★ 这一段的门槛是阈值：V29 的 as_assistant.retrieval_threshold 默认 0.60，而全文那一路给的是 ts_rank 分
    //   （实测 0.0x）⇒ 默认配置下命中会被 ChatService#retrieve 的 `hit.score() < threshold` 整批丢掉。
    //   本类夹具把它显式设成 0.00（见 seedAppBoundToKb 的说明），目的只是让"引用里能出现它"这条可断言 ——
    //   它属产品配置，不是判据 4 的实现细节。
    assertThat(response.body())
        .as("★★ 判据 4 的第三段：SSE 的 refs 事件里必须出现这份附件的名字（docName 进引用，标题来自分段前缀）")
        .contains(ATTACHMENT_NAME);
    Long refsWithDocument =
        jdbc.queryForObject(
            "select count(*) from cv_message m where m.conversation_id = ?::uuid"
                + " and m.reference_snapshot is not null"
                + " and exists (select 1 from jsonb_array_elements(m.reference_snapshot) e"
                + " where e->>'documentId' = ? and e->>'kbId' is null)",
            Long.class,
            conversationId,
            documentId.toString());
    assertThat(refsWithDocument)
        .as(
            "★★ 判据 4 的守门断言：本轮助手的引用快照里必须有一条**指向该会话域文档且 kbId 为空**的条目"
                + "（kbId 为空正是后端补「会话附件」显示名的结构性判据）")
        .isGreaterThanOrEqualTo(1L);

    // ── ④ 幂等：同一份附件连问第二次，不许在会话域里落第二条文档 ──
    HttpResponse<String> second =
        postSse(token, chatBody(appId, conversationId, QUESTION, List.of(fileId.toString())));
    assertThat(second.statusCode()).as("第二次问答同样必须 200").isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from kb_document where scope_kind = 'CONVERSATION' and scope_id ="
                    + " ?::uuid",
                Long.class,
                conversationId))
        .as("★★ 幂等：前端重发 / 用户连点都会让同一份附件被提交两次；不幂等就会落两条同名文档 ⇒ 同一段正文命中两次")
        .isEqualTo(1L);
  }

  // ────────────────────────── 夹具 ──────────────────────────

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
    assertThat(response.statusCode()).as("种子账号必须能登录").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  /**
   * 造：一个对话模型 + 一个库 + 一个应用，并把库绑到应用上。
   *
   * <p>★ 为什么要绑库：{@code ChatService#retrieve} 在"调用方没指定库"时会看通用问答的召回开关（{@code
   * app.retrieval.retrieval_enabled}，默认关）。应用**显式绑了库**是用户自己配的范围，不受那个开关影响 —— 绑上它，
   * 这条用例读到的就是"检索真的跑了"，而不是"开关把检索关了"。
   */
  private UUID seedAppBoundToKb() {
    UUID chatModel = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,"
            + " created_at, updated_at) values (?, '附件夹具对话模型', 'CHAT', 'LOCAL_INFERENCE',"
            + " 'qwen3:4b', 'http://localhost:11434/v1', 'LOADED', true, now(), now())",
        chatModel);
    UUID embeddingModel = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at) values (?, '附件夹具向量模型', 'EMBEDDING',"
            + " 'LOCAL_INFERENCE', 'bge-m3', 'http://localhost:11434/v1', 768, 'LOADED', true,"
            + " now(), now())",
        embeddingModel);
    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, '附件夹具库', '制度规范', 'LOCAL', ?, 'INTERNAL', ?, 'PRIVATE', now(),"
            + " now())",
        kbId,
        embeddingModel,
        ADMIN_ID);
    UUID appId = UUID.randomUUID();
    jdbc.update(
        // ★★ 阈值必须显式调到 0：V29 的默认值是 0.60，而全文那一路给的是 ts_rank 分（实测 0.0x），
        //   在默认阈值下会话附件的命中会被 ChatService#retrieve 的 `hit.score() < threshold` 整批丢掉
        //   ⇒ 库里明明有分段、界面却一条引用都没有。本类要断言的是"引用里能出现它"，所以这一格必须归零；
        //   它在真实部署里由应用/平台设置决定，属产品配置，不是本判据的实现细节。
        "insert into as_assistant (id, name, type, model_id, owner_id, visibility, published,"
            + " retrieval_mode, retrieval_threshold, created_at, updated_at)"
            + " values (?, '附件夹具应用', 'SIMPLE', ?, ?, 'PRIVATE', true, 'HYBRID', 0.00,"
            + " now(), now())",
        appId,
        chatModel,
        ADMIN_ID);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);
    return appId;
  }

  /**
   * 走真的 {@code POST /api/v1/files}（multipart）上传一份纯文本附件。
   *
   * <p>★ 手拼 multipart 而不引测试库：{@code FileUploadHttpTest} 也是这么做的（同一条理由 —— 这条端点的形状就是 "一个 {@code file}
   * 部件"，用框架封装反而把要验的形状藏起来）。
   */
  private UUID upload(String token, String fileName, String content) throws Exception {
    String boundary = "----attachment-187-boundary";
    String body =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + fileName
            + "\"\r\n"
            + "Content-Type: text/plain\r\n\r\n"
            + content
            + "\r\n--"
            + boundary
            + "--\r\n";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/files"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("上传必须成功（否则后面读到的是'没传过附件'的链路，而不是这条判据）").isEqualTo(200);
    String id = stringField(response.body(), "id");
    assertThat(id).as("上传响应的 data.id 必须存在（前端就是把它放进 attachmentIds 的）").isNotNull();
    return UUID.fromString(id);
  }

  private UUID createConversation(String token, UUID appId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/conversations"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"appId\":\"" + appId + "\"}", StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("建会话必须成功").isEqualTo(200);
    return UUID.fromString(stringField(response.body(), "id"));
  }

  /**
   * 问答请求体（{@code ChatStreamBody} 的 wire 形状）。
   *
   * <p>★ {@code attachmentIds} 是这整条判据的入口：它是前端 {@code useChatSession} 真实发送的字段。
   */
  private String chatBody(UUID appId, UUID conversationId, String question, List<String> ids) {
    String attachmentIds = ids.isEmpty() ? "[]" : "[\"" + String.join("\",\"", ids) + "\"]";
    return "{"
        + "\"appId\":\""
        + appId
        + "\","
        + "\"conversationId\":\""
        + conversationId
        + "\","
        + "\"question\":\""
        + question
        + "\","
        + "\"attachmentIds\":"
        + attachmentIds
        + ",\"thinking\":false}";
  }

  /** 发流式问答并**读完**整条流（读完 = 服务端那条流已经收尾、落库动作全部完成）。 */
  private HttpResponse<String> postSse(String token, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/conversations/chat"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 从 JSON 里取一个字符串字段（与 {@code SecurityChainTest} / {@code FileUploadHttpTest} 同一手法）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return start > 0 && end > start ? json.substring(start, end) : null;
  }
}
