package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ 三域（共用库 / 个人库 / 会话临时）—— {@code 13 §11.2} / §11.3，Issue {@code #31}。真 PG + 纯 SQL 夹具。
 *
 * <h3>为什么走 {@code SegmentSearchPort} 而不是 HTTP</h3>
 *
 * <p>它就是检索链路消费的那个端口（{@code RetrievalService.retrieveOneQuery} / {@code ChatService.retrieve}），
 * 也就是"域在哪一支上生效"的**唯一实现点**（{@code JdbcSegmentSearchQuery}）。走 HTTP 还要先有会话与附件端点 —— 那两件本批**不做**（{@code
 * 13 §11.6} 第 4 项）。★ 而"域判定写错了"这件事不该跟着 HTTP 面的排期一起等。
 *
 * <p>★ 夹具全是 SQL（不建模型、不要桩服务）⇒ 本类<strong>不会因为桩不可达而整类跳过</strong>。 那是有意的：域判定的错误是**静默**的（少看见 /
 * 多看见），它不该跟着桩一起失效。
 *
 * <h3>三域各一条 + 两条金丝雀盯的是同一件事</h3>
 *
 * <p>四行语料<strong>内容几乎一样</strong>（都含同一个查询词），只有归属不同：
 *
 * <table>
 *   <tr><th>分段</th><th>归属</th><th>它该出现在哪个域</th></tr>
 *   <tr><td>{@code segShared}</td><td>共用库 A</td><td>共用库域（{@link #sharedKbDocumentIsVisibleInTheBoundDomain}）</td></tr>
 *   <tr><td>{@code segPersonal}</td><td>我的个人库 P</td><td>★ 只在**隐式档**（{@link #personalKbIsVisibleOnlyInTheImplicitBucket}）</td></tr>
 *   <tr><td>{@code segConvA}</td><td>会话 A · 我传的</td><td>★ 只有"会话 A + 我"（{@link #conversationAttachmentIsVisibleOnlyInItsOwnConversationForItsUploader}）</td></tr>
 *   <tr><td>{@code segConvB}</td><td>会话 B · 我传的</td><td>★ 同一个人的**另一个**会话 —— 判据 1 要它<strong>检索不到</strong></td></tr>
 *   <tr><td>{@code segConvOther}</td><td>会话 A · **别人**传的</td><td>★ 会话对、人不对 —— 判据 2 的"访客拿作者的附件"反过来说</td></tr>
 * </table>
 *
 * <p>★★ 两条金丝雀（{@code 13 §11.7} 判据 4）就落在这个形状上，它们的实测读数见交付报告： ① 删掉谓词第三支的 {@code scope_id =
 * :conversationId} ⇒ 会话 A 会捞到会话 B 的附件； ② 把域过滤从分段那一侧（{@code s.}）改成文档那一侧（{@code d.}）⇒ 冗余列漂移那一行会被捞出来。
 */
@SpringBootTest
@Testcontainers
class RetrievalDomainScopeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V25 种子写死的 ROOT 部门 id（{@code kb_document.org_id} 是外键，不能塞随机 UUID —— Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  /** ★ 四行语料都含它 ⇒ 一次查询能同时命中三个域，于是"哪些域进来了"一目了然。 */
  private static final String QUERY = "合同违约金";

  private static final int LIMIT = 20;

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  @Autowired VectorIndexPort vectorIndex;

  private UUID me;
  private UUID other;
  private UUID sharedKb;
  private UUID personalKb;
  private UUID conversationA;
  private UUID conversationB;

  private UUID segShared;
  private UUID segPersonal;
  private UUID segConvA;
  private UUID segConvB;
  private UUID segConvOther;

  @BeforeEach
  void seedThreeDomains() {
    me = UUID.randomUUID();
    other = UUID.randomUUID();
    // ★ 共用库设成 PUBLIC：本类验的是**域**，不是可见性（可见性另有 RetrievalSecurityTest 与 ScopeFilterTest）
    sharedKb = insertKb("共用库", "SHARED", me, "PUBLIC");
    // ★ 个人库就是"kind = PERSONAL 且 owner_id = 我"的那一行（13 §11.2）
    personalKb = insertKb("我的资料", "PERSONAL", me, "PRIVATE");
    conversationA = insertConversation(me, "会话 A");
    conversationB = insertConversation(me, "会话 B");

    segShared = insertKbSegment(sharedKb, "合同违约金：共用库里的制度条款");
    segPersonal = insertKbSegment(personalKb, "合同违约金：我的资料里的备忘");
    segConvA = insertConversationSegment(conversationA, me, "合同违约金：会话 A 的附件");
    segConvB = insertConversationSegment(conversationB, me, "合同违约金：会话 B 的附件");
    segConvOther = insertConversationSegment(conversationA, other, "合同违约金：会话 A 里别人传的附件");
  }

  // ────────────────────────── 域①：共用库 ──────────────────────────

  /**
   * 共用库域：绑定了这个库的检索只看得到它（会话域/个人库都不在域里）。
   *
   * <p>★ 这条同时是"另外两域**不会**被顺手放进来"的反证：一个人可以同时有个人库与多个会话， 而"没开"必须是"看不见"，不是"默认全开"。
   */
  @Test
  void sharedKbDocumentIsVisibleInTheBoundDomain() {
    assertThat(hitIds(RetrievalScope.shared(List.of(sharedKb))))
        .as("共用库那一支：绑定的库里的分段必须检索得到，且**只有**它")
        .containsExactly(segShared);
  }

  // ────────────────────────── 域②：个人库（只在隐式档） ──────────────────────────

  /**
   * ★★ 个人库**只出现在隐式档**（{@code 13 §11.2} 的既定口径）：不传 {@code personalKbId} 就一条都不进来。
   *
   * <p>★ 三条断言各管一半：
   *
   * <ol>
   *   <li>开：共用库 + 个人库 ⇒ 两条都在（"加上我的资料"生效）；
   *   <li>关：只带共用库 ⇒ 个人库那条**不在**（否则用户的历史杂物会污染每一次回答）；
   *   <li>★ 别人的个人库在我的域里**永远不会出现**：它不是共用库 ⇒ 没有可见性面（{@code 13 §11.2} ②） —— 这一条是把"个人库"与"一张 owner
   *       是我的普通库"分开的那道线。
   * </ol>
   */
  @Test
  void personalKbIsVisibleOnlyInTheImplicitBucket() {
    assertThat(hitIds(new RetrievalScope(List.of(sharedKb), personalKb, null, me)))
        .as("隐式档打开（「加上我的资料」）：共用库 + 个人库两条都要在")
        .containsExactlyInAnyOrder(segShared, segPersonal);

    assertThat(hitIds(new RetrievalScope(List.of(sharedKb), null, null, me)))
        .as("隐式档关闭（默认）：个人库那条**不许**出现 —— 否则用户的历史杂物会污染每一次回答")
        .containsExactly(segShared);

    assertThat(hitIds(new RetrievalScope(List.of(sharedKb), null, null, other)))
        .as("★ 别人的个人库不是这一个 scope 的绑定值 ⇒ 它永远进不来（它不是共用库，没有可见性面）")
        .containsExactly(segShared);

    assertThat(hitIds(new RetrievalScope(List.of(), personalKb, null, me)))
        .as("★ 只带个人库（没有绑定任何共用库）是合法情形：共用库那一支为空时必须是'恒假'，不能是一条会炸的 SQL")
        .containsExactly(segPersonal);
  }

  // ────────────────────────── 域③：会话临时（本会话 + 本人） ──────────────────────────

  /**
   * ★★ 会话临时的两条边界（{@code #31} 判据 1 与判据 2 的形状）：**本会话** 与 **本人**，缺一不可。
   *
   * <p>★ 断言里的三条"不许"逐条对应一个静默洞：
   *
   * <ul>
   *   <li>{@code segConvB}（同一个人的**另一个**会话）不出现 ⇒ 删掉 {@code scope_id = :conversationId} 之后它就会出现（判据 1
   *       的金丝雀）；
   *   <li>{@code segConvOther}（会话对、人不对）不出现 ⇒ 删掉 {@code owner_id = :ownerId} 之后它就会出现；
   *   <li>{@code segPersonal} 不出现 ⇒ 会话域**不**顺带把个人库打开（否则"会话附件"与"我的资料"混成一件事， 而那正是 {@code #31}
   *       正文说的"个人库解决归属谁、不解决属于哪个会话"）。
   * </ul>
   */
  @Test
  void conversationAttachmentIsVisibleOnlyInItsOwnConversationForItsUploader() {
    assertThat(hitIds(new RetrievalScope(List.of(sharedKb), null, conversationA, me)))
        .as("★ 判据 1：会话 A 的检索只看得到**会话 A 里我传的**那一条 —— 会话 B 的、别人传的都不许出现")
        .containsExactlyInAnyOrder(segShared, segConvA);

    assertThat(hitIds(new RetrievalScope(List.of(sharedKb), null, conversationB, me)))
        .as("★ 同一个人的会话 B：只看得到会话 B 的那一条（会话 A 的附件在会话 B 里检索不到）")
        .containsExactlyInAnyOrder(segShared, segConvB);
  }

  /**
   * ★★ 判据 2 的形状：分享一个带附件的会话给访客 —— 访客问得到**附件**，但**检索不到作者的个人库**。
   *
   * <p>公开面走 {@code AccessScopePort.asSubject(归属人, 冻结密级)}，于是 {@code ownerId} = 归属人 ⇒
   * 会话域那一支成立（附件看得见）；而公开面解析出来的域里 {@code personalKbId} 必须是 {@code null} （{@code 13 §11.2}
   * ③：公开面的域**只有**共用库 + 该会话的会话临时）。
   *
   * <p>★ 这一条与上一条的分工：上一条钉"会话域要 owner + conversation 两条都在"， 这一条钉"把个人库并进公开面是一次静默越权"（访客会按**作者的文档**检索）。
   */
  @Test
  void visitorOfASharedConversationReachesTheAttachmentWithoutTheAuthorsPersonalLibrary() {
    RetrievalScope publicFace = new RetrievalScope(List.of(sharedKb), null, conversationA, me);

    assertThat(hitIds(publicFace))
        .as(
            "★ 判据 2：访客要能问到会话 A 的附件（ownerId = 归属人 ⇒ 会话域那一支成立），"
                + "但作者的**个人库**那条不许出现 —— personalKbId 必须为空（13 §11.2 ③）")
        .containsExactlyInAnyOrder(segShared, segConvA)
        .doesNotContain(segPersonal, segConvB, segConvOther);
  }

  // ────────────────────────── 引用素材的标识：两个域各一条 ──────────────────────────

  /**
   * ★★ {@code load} 给出的标识必须来自<strong>真实的行</strong>：知识库域带 {@code kbId}、会话域<strong>必为 {@code
   * null}</strong>，而两个域都带 {@code documentId}。
   *
   * <h3>为什么这一条落在端口这一层、而且是同一条用例里两个方向</h3>
   *
   * <p>这两个分量的来源就在这一条 SQL 的两个列上（{@code s.document_id} 与左连接的 {@code k.id}）：
   *
   * <ul>
   *   <li>{@code kbId} 是前端「引用卡片按来源类型切换标签」那条<strong>结构性</strong>判据的判据本身 （缺失 = 会话域）⇒
   *       生产链路上连库引用也必须带上它，而**会话域必须是 null**。两个方向一起钉， 才挡得住"给所有引用都补一个 kbId"那种改法（它会让库引用那条断言照样绿、而判据被毁掉）；
   *   <li>{@code documentId} 为空的症状是引用深链<strong>静默 no-op</strong>（{@code #91}）：前端拼出的 query
   *       是空的，点了没反应也不报错。★ 会话域的文档行同样存在 ⇒ 它也该有值。
   * </ul>
   *
   * <p>★ 只钉一端不够：装配点恒传 {@code null}（本批修掉的那一处）会让"会话域为空"这条**恒绿**；
   * 而只钉另一端则挡不住"全都补上"。所以两个方向在<strong>同一条用例</strong>里。
   */
  @Test
  void loadCarriesTheKbAndDocumentIdentityOnBothDomains() {
    Map<UUID, SegmentSearchPort.Card> cards =
        segmentSearch.load(List.of(segShared, segConvA)).stream()
            .collect(Collectors.toMap(SegmentSearchPort.Card::segmentId, card -> card));

    assertThat(cards)
        .as("★ 前置：两条分段（库域 + 会话域）都必须取得到展示信息，否则下面两条断言在空跑")
        .containsOnlyKeys(segShared, segConvA);
    SegmentSearchPort.Card kbCard = cards.get(segShared);
    SegmentSearchPort.Card conversationCard = cards.get(segConvA);

    assertThat(kbCard.kbId())
        .as("★★ 判据 1：知识库域的引用素材必须带真实库 id —— 缺了它，前端会把每条库引用读成「来源：」" + "（那正是 kbId 恒 null 时的症状）")
        .isEqualTo(sharedKb);
    assertThat(kbCard.documentId())
        .as("★★ 判据 3（#91）：documentId 必须来自那一行，否则引用深链拼不出 query（静默 no-op）")
        .isEqualTo(documentIdOf(segShared));

    assertThat(conversationCard.kbId())
        .as("★★ 判据 2：会话临时的分段没有库行 ⇒ 这里**必须**是 null（不许兜底成空串、占位串或『最近的一个库』）——" + "它正是前端那条结构性判据成立的地方")
        .isNull();
    assertThat(conversationCard.documentId())
        .as("★ 会话域的文档行是真实存在的 ⇒ 它的深链载体同样要有值（会话附件也点得开）")
        .isEqualTo(documentIdOf(segConvA));
  }

  // ────────────────────────── 域的解析只有一处 ──────────────────────────

  /**
   * ★★ {@code retrievalScope} 是域的**唯一解析点**（{@code 13 §11.3} 的绑定值不许由调用方自己拼）。
   *
   * <p>三条断言各钉一件：
   *
   * <ol>
   *   <li>个人库那一档**默认关**（{@code 13 §11.4}），且开关打开时解析出来的是"我的那一行"；
   *   <li>★ {@code ownerId} 取的是**当前范围的主体**，不是调用方传的：换一个身份解析同一次请求， 拿到的是另一个 owner ——
   *       这正是"会话附件只对本人可见"的入口；
   *   <li>别人的身份打开开关也解析不出**我的**个人库（它是按 {@code owner_id = 我} 查的）。
   * </ol>
   */
  @Test
  void scopeResolutionClosesThePersonalBucketUnlessAskedAndTakesOwnerFromTheCurrentScope() {
    RetrievalScope closed =
        SecurityContexts.asUser(
            me,
            ClassificationLevel.CONFIDENTIAL,
            () -> segmentSearch.retrievalScope(List.of(sharedKb), conversationA, false));
    assertThat(closed.personalKbId()).as("隐式档默认关（13 §11.4）：开关没打开时**不许**解析出个人库").isNull();
    assertThat(closed.sharedKbIds()).as("共用库那一支仍要按可见性解析出来").containsExactly(sharedKb);
    assertThat(closed.conversationId()).as("会话域由调用方给（它是请求的一部分）").isEqualTo(conversationA);
    assertThat(closed.ownerId())
        .as("★ 传输者取自**当前范围的主体**，不由调用方传（否则它就是'用谁的身份检索会话附件'的入参）")
        .isEqualTo(me);

    RetrievalScope open =
        SecurityContexts.asUser(
            me,
            ClassificationLevel.CONFIDENTIAL,
            () -> segmentSearch.retrievalScope(List.of(sharedKb), null, true));
    assertThat(open.personalKbId())
        .as("隐式档打开：解析出来的必须是**我的**那一行（kind = 'PERSONAL' and owner_id = 我）")
        .isEqualTo(personalKb);

    RetrievalScope someoneElse =
        SecurityContexts.asUser(
            other,
            ClassificationLevel.CONFIDENTIAL,
            () -> segmentSearch.retrievalScope(List.of(sharedKb), null, true));
    assertThat(someoneElse.personalKbId())
        .as("★ 别人的身份打开开关：解析不出**我的**个人库（按 owner_id = 我 查）—— 个人库不是按请求参数选的")
        .isNull();
  }

  /**
   * ★★ 向量那一路用的是**同一个**谓词（{@code 13 §三} 闸门②："两路同谓词"），而且会话临时也要能被它捞到。
   *
   * <p>★ 为什么单开一条：向量路的 SQL 与全文路是**两段不同的字符串**（驱动表是向量表 + {@code <=>}），
   * 而"只给一路加过滤条件"正是这个项目最贵的病。这条断言把域谓词在**第二路**上钉住。
   *
   * <p>★ 它顺带钉住一个真实的陷阱：会话临时的分段 {@code kb_id} 是**空的**， 而旧的 {@code join kb_knowledge_base k on k.id =
   * s.kb_id} 是<strong>内连接</strong> ⇒ 会话附件会被静默丢掉。 这条断言要求 {@code segConvA} 出现在会话 A 的向量结果里 ——
   * 内连接写法会让它红。
   *
   * <p>★ 夹具**不经过模型服务**：向量直接 {@code upsert}（维度 4，表按需建），查询向量是写死的 ⇒ 本类依旧不依赖桩服务（与类注释同一条理由）。
   */
  @Test
  void conversationDomainIsAlsoReachableOnTheVectorPath() {
    upsertVector(segShared, sharedKb, 1.0f);
    upsertVector(segPersonal, personalKb, 0.9f);
    upsertVector(segConvA, null, 0.8f);
    upsertVector(segConvB, null, 0.7f);

    assertThat(vectorHitIds(new RetrievalScope(List.of(sharedKb), null, conversationA, me)))
        .as(
            "★ 向量路用的是同一个谓词：会话 A 的结果 = 共用库 + 会话 A 的附件（会话 B 的那条不许出现）。"
                + "同时它证明**左连接**那条修正是对的：内连接下会话附件一条都出不来")
        .containsExactlyInAnyOrder(segShared, segConvA);

    assertThat(vectorHitIds(new RetrievalScope(List.of(sharedKb), personalKb, null, me)))
        .as("★ 隐式档在向量路上同样生效：个人库只在开关打开时进来")
        .containsExactlyInAnyOrder(segShared, segPersonal);
  }

  /** 三个域全关 ⇒ 一条都不返回（{@code 13 §11.4} 的"没开"必须是"看不见"）。 */
  @Test
  void emptyDomainReturnsNothing() {
    assertThat(
            SecurityContexts.asClearance(
                ClassificationLevel.CONFIDENTIAL,
                () ->
                    segmentSearch.fullText(
                        new SegmentSearchPort.TextQuery(
                            QUERY, new RetrievalScope(List.of(), null, null, null), LIMIT))))
        .as("三个域全关：调用方应当据此走'没有可检索的范围'那条分支，而不是把全库当成默认范围")
        .isEmpty();
  }

  /**
   * ★ `#192`：向量检索过去只按维度选表（`vec_embedding_<dim>`），不认 embedding 模型 ——
   * 同维度下别的模型写的旧向量会被一起捞进结果。这条断言钉住：查询带 `embeddingModelId` 时， 结果里只许出现该模型写入的向量。
   */
  @Test
  void vectorSearchOnlyUsesVectorsWrittenByTheQueryModel() {
    UUID modelA = UUID.randomUUID();
    UUID modelB = UUID.randomUUID();
    UUID segA = insertKbSegment(sharedKb, "违约金上限：模型 A 写的向量");
    UUID segB = insertKbSegment(sharedKb, "违约金上限：模型 B 写的向量");
    upsertVector(segA, sharedKb, 1.0f, modelA);
    upsertVector(segB, sharedKb, 1.0f, modelB);

    float[] query = {1f, 0f, 0f, 0f};
    List<UUID> hits =
        SecurityContexts.asUser(
                me,
                ClassificationLevel.CONFIDENTIAL,
                () ->
                    segmentSearch.vectorSearch(
                        new SegmentSearchPort.VectorQuery(
                            4,
                            query,
                            new RetrievalScope(List.of(sharedKb), null, null, null),
                            LIMIT,
                            modelA)))
            .stream()
            .map(SegmentSearchPort.Scored::segmentId)
            .toList();

    assertThat(hits)
        .as("★ 只许返回查询模型（modelA）写的向量；同维度下 modelB 写的那条必须被过滤掉")
        .contains(segA)
        .doesNotContain(segB);
  }

  // ────────────────────────── 夹具（纯 SQL） ──────────────────────────

  /** 某条分段所属的文档 id（★ 从库里读，不由夹具自己记一个期望值 —— 那会让读数变成自证）。 */
  private UUID documentIdOf(UUID segmentId) {
    return jdbc.queryForObject(
        "select document_id from kb_segment where id = ?", UUID.class, segmentId);
  }

  /** 一次真检索（全文那一路就是 {@code RetrievalService.retrieveOneQuery} 消费的那一条）。 */
  private List<UUID> hitIds(RetrievalScope scope) {
    return SecurityContexts.asUser(
            me,
            ClassificationLevel.CONFIDENTIAL,
            () -> segmentSearch.fullText(new SegmentSearchPort.TextQuery(QUERY, scope, LIMIT)))
        .stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .toList();
  }

  /** 向量那一路：查询向量写死（4 维），所以不需要任何模型服务。 */
  private List<UUID> vectorHitIds(RetrievalScope scope) {
    float[] query = {1f, 0f, 0f, 0f};
    return SecurityContexts.asUser(
            me,
            ClassificationLevel.CONFIDENTIAL,
            () ->
                segmentSearch.vectorSearch(
                    new SegmentSearchPort.VectorQuery(4, query, scope, LIMIT)))
        .stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .toList();
  }

  /**
   * 给一条分段写一条向量（★ 走 {@code VectorIndexPort.upsert} 的**生产写入路径**，含按需建表）。
   *
   * @param similarity 余弦相似度的大致档位（4 维基向量，越大越靠前）
   */
  private void upsertVector(UUID segmentId, UUID kbId, float similarity) {
    upsertVector(segmentId, kbId, similarity, UUID.randomUUID());
  }

  /** 同上，但模型 id 由调用方指定 —— 向量表的 metadata 里记的就是它（Issue {@code #192}）。 */
  private void upsertVector(UUID segmentId, UUID kbId, float similarity, UUID modelId) {
    UUID documentId =
        jdbc.queryForObject(
            "select document_id from kb_segment where id = ?", UUID.class, segmentId);
    vectorIndex.upsert(
        new VectorIndexPort.Entry(
            segmentId, kbId, documentId, modelId, "向量夹具", new float[] {similarity, 0f, 0f, 0f}));
  }

  private UUID insertKb(String name, String kind, UUID owner, String visibility) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, kind, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, ?, '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, ?, now(), now())",
        id,
        name + "-" + shortId(),
        kind,
        UUID.randomUUID(),
        owner,
        visibility);
    return id;
  }

  private UUID insertConversation(UUID owner, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, created_at, updated_at)"
            + " values (?, ?, ?, now(), now())",
        id,
        owner,
        title + "-" + shortId());
    return id;
  }

  /** 库域文档 + 一段分段（段的域与文档逐字一致 —— 冗余列的写侧规则）。 */
  private UUID insertKbSegment(UUID kbId, String text) {
    UUID docId = insertDocument(kbId, "KB", kbId, me, "文档-" + shortId());
    return insertSegment(docId, kbId, "KB", kbId, me, text, 0);
  }

  /** 会话域文档 + 一段分段：★ 没有库行（{@code kb_id} 留空，{@code scope_id} 是会话）。 */
  private UUID insertConversationSegment(UUID conversationId, UUID owner, String text) {
    UUID docId = insertDocument(null, "CONVERSATION", conversationId, owner, "附件-" + shortId());
    return insertSegment(docId, null, "CONVERSATION", conversationId, owner, text, 0);
  }

  private UUID insertDocument(UUID kbId, String scopeKind, UUID scopeId, UUID owner, String name) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    //   ★ 本类要造的是**产品路径不产生**的归属形状（任意 scope_kind / owner_id 组合，含会话域）。
    return DocumentFixture.insertWithScope(
        jdbc, UUID.randomUUID(), kbId, scopeKind, scopeId, owner, name, ROOT_ORG, "INTERNAL");
  }

  private UUID insertSegment(
      UUID docId, UUID kbId, String scopeKind, UUID scopeId, UUID owner, String text, int ordinal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, owner_id, ordinal,"
            + " title, title_path, content, search_text, content_hash, char_count, is_table,"
            + " is_code, oversized_risk, enabled, embedding_status, created_at, updated_at,"
            + " classification)"
            + " values (?, ?, ?, ?, ?, ?, ?, '夹具', '第一章', ?, ?, ?, ?, false, false, false,"
            + " true, 'EMBEDDED', now(), now(),"
            // ★ 密级从文档继承（V22 的列是 not null）：夹具也走同一条规则，别自己另造一份
            + " (select classification from kb_document where id = ?))",
        id,
        docId,
        kbId,
        scopeKind,
        scopeId,
        owner,
        ordinal,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
    return id;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
