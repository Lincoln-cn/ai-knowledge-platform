package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code cv_message} 的真栈读数（迁移 {@code V33}）—— <strong>真 PostgreSQL + 真 Flyway</strong>， 不是一个字都 mock。
 *
 * <h3>这个类钉五件事，它们坏掉的症状完全不同（所以分开断言、各自给可读原因）</h3>
 *
 * <ol>
 *   <li><strong>迁移接上了、而且没碰坏上一版</strong>（{@link #migrationIsAppliedAfterExistingChain()}）： {@code
 *       version='33'} 成功；★ 同时断言 {@code version='32'} 也成功 —— 那是"没碰坏上一版"的
 *       <strong>回归读数</strong>。两条一起断言的道理：V33 没跑 ⇒ 表根本不存在（整类起不来，看起来像"代码有问题"）； 而改动更早的迁移 ⇒ Flyway
 *       checksum 不匹配、后端起不来（看起来像"环境有问题"）。
 *   <li><strong>库层那四道闸真的在闸</strong>（取值域 / MSG-I3 / token 非负 / 失败必有原因）—— {@code 02 §2.6}
 *       的不变量在库层兜的那一半。★ 它们必须落在库层：要挡的是"绕过领域层的一切写入路径" （将来的 flush 循环、重生成、管理脚本），而领域层的方法只在应用进程里生效。
 *   <li><strong>引用快照真的落进 {@code jsonb} 并能原样读回</strong>（{@link #referencesSnapshotRoundTrips()}）——
 *       {@code A4 引用可展开溯源}的载体。★ 断言同时走<strong>领域端口</strong>与<strong>纯 SQL</strong>：
 *       前者证明链路通，后者证明"库里真有那些字段"（前者可能被 Hibernate 的一级缓存满足）。
 *   <li><strong>"停止保留内容"在库里也成立</strong>（{@link #stoppedMessageKeepsItsTextInTheDatabase()}）——
 *       {@code A5} 判据的持久化侧读数。领域侧的那一条在纯单测 {@code MessageContentTest}。
 *   <li><strong>tokens 两列真的写进去了，失败路径上也计</strong>（{@link
 *       #failedRunStillCountsTokensItAlreadySpent()}）。
 * </ol>
 *
 * <p>★ 为什么走 {@code MessageRepository}（领域端口）而不是 {@code JpaMessageRepository}：端口 + 适配器
 * 是这一批的交付物之一，走端口才证明"领域接口 → 适配器 → 表"这条链是通的。 ★ 也<strong>不走 HTTP</strong>：本批刻意不加端点、不改契约。
 *
 * <h3>★ 事务：这里刻意<strong>不开</strong>测试级事务</h3>
 *
 * <p>与 {@code ConversationPersistenceTest} 同一条口径 —— 每一次仓储调用都在<strong>自己的事务</strong>里提交，
 * 所以下面每一条"重新读回来"都是真的从库里读（Hibernate 的一级缓存不跨事务存活）。 ★
 * 生产链路里"消息落库"与"会话密级抬升"必须<strong>同一个事务</strong>（{@code 02 §5.2}）， 那个边界属用例层（第 17 周）的活，本批不预支。
 */
@SpringBootTest
@Testcontainers
class MessagePersistenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 夹具里的那个会话。★ 固定值：归属列断言的就是它，随机值会让"存进去的是哪个会话"无法核对。 */
  private static final UUID CONVERSATION = UUID.fromString("1a000000-0000-4000-8000-0000000000aa");

  @Autowired JdbcTemplate jdbc;

  @Autowired MessageRepository messages;

  // ────────────────────────── ① 迁移接上了，且没碰坏上一版 ──────────────────────────

  /**
   * {@code version='33'} 成功（本批的迁移真的跑了）+ {@code version='32'} 成功（上一版没被碰坏）。
   *
   * <p>★ 断言"成功"而不是"存在一行"：{@code flyway_schema_history} 里失败的行也会留下记录， 只看"在不在"的话，一次失败迁移会被读成"跑过了"。
   */
  @Test
  void migrationIsAppliedAfterExistingChain() {
    assertThat(successOf("33"))
        .as("Flyway 里没有 version=33 的成功记录 ⇒ conversation/V33 没被执行（cv_message 这张表不在）")
        .isEqualTo(Boolean.TRUE);
    assertThat(successOf("32"))
        .as(
            "★ V32（cv_conversation + 会话累计密级）的成功记录不见了 —— 本批一个字都不许改更早的迁移"
                + "（改了 checksum 就对不上，后端起不来）。这一条是'没碰坏上一版'的回归读数")
        .isEqualTo(Boolean.TRUE);
  }

  // ────────────────────────── ② 库层那四道闸 ──────────────────────────

  /**
   * ★ <strong>取值域</strong>：契约那四个之外的一切状态被库层拒绝（含设计册那套五态里的取值）。
   *
   * <p>★ 特别把 {@code 'DONE'} / {@code 'STREAMING'} 放进非法清单：它们是 {@code 02 §5.2} 注释里那套 取值 ——
   * 本表<strong>不认</strong>（契约优先）。这一条同时钉住"没有两套取值域并存"。
   */
  @Test
  void unknownStatusValuesAreRejectedByCheckConstraint() {
    for (String illegal :
        List.of("DONE", "STREAMING", "PENDING", "ERROR", "generating", "生成中", "")) {
      assertThatThrownBy(() -> insertAssistantBySql(illegal))
          .as("★ 非法状态 '%s' 必须被 chk_msg_status 拒绝 —— 前端 onDone 收到认不出的终态就停在'生成中'", illegal)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  /**
   * ★ <strong>MSG-I3</strong>：用户消息不许是 {@code GENERATING}（库层兜的那一道）。
   *
   * <p>★ 一条"生成中"的用户消息在界面上<strong>永远转圈</strong>：没有任何生成任务会去收尾它 （用户消息不是模型生成的），而它不会报错。
   */
  @Test
  void userMessageCannotBeGeneratingInTheDatabase() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into cv_message (id, conversation_id, role, content, status,"
                        + " created_at, updated_at) values (?::uuid, ?::uuid, 'USER', '问一句',"
                        + " 'GENERATING', now(), now())",
                    UUID.randomUUID(),
                    CONVERSATION))
        .as("★ MSG-I3：用户消息的状态只能是 GENERATED —— '生成中'的用户消息不会被任何任务收尾，界面永远转圈")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** ★ token 两个分量都拒绝负数（token 是只增的计数器）。 */
  @Test
  void negativeTokenCountsAreRejectedByCheckConstraint() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into cv_message (id, conversation_id, role, content, status,"
                        + " prompt_tokens, created_at, updated_at) values (?::uuid, ?::uuid,"
                        + " 'ASSISTANT', '', 'GENERATED', -1, now(), now())",
                    UUID.randomUUID(),
                    CONVERSATION))
        .as("★ 负数 token 只能来自'减了一下'的实现")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** ★ {@code FAILED} 必须给得出原因（与 {@code kb_document} 的 {@code chk_failure} 同一条口径）。 */
  @Test
  void failedWithoutReasonIsRejectedByCheckConstraint() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into cv_message (id, conversation_id, role, content, status,"
                        + " created_at, updated_at) values (?::uuid, ?::uuid, 'ASSISTANT', '',"
                        + " 'FAILED', now(), now())",
                    UUID.randomUUID(),
                    CONVERSATION))
        .as("★ 一条没有原因的失败在界面上只有一个空白提示，而'为什么失败'是用户唯一能据此行动的信息")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * 消息列表索引在（{@code idx_msg_conv}，与 {@code 02 §5.2} 同名同列序）。
   *
   * <p>★ 索引<strong>少一个不会报错</strong>，只会让会话窗口的读取在量产数据上变慢 —— 而那时候没人会把它与本批的迁移联系起来。★ 列序 ({@code
   * conversation_id, created_at}) 逐字照 DDL： 反了的话这个索引对"按会话取窗口"这条查询就白建。
   */
  @Test
  void messageListIndexExistsWithTheDocumentedColumnOrder() {
    assertThat(
            jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = current_schema()"
                    + " and tablename = 'cv_message' and indexname = 'idx_msg_conv'",
                String.class))
        .as("★★ idx_msg_conv 不在（或列序不对）⇒ 会话窗口读取走全表扫（不报错，只是越来越慢）")
        .contains("conversation_id, created_at");
  }

  // ────────────────────────── ③ 引用快照往返（A4 的载体） ──────────────────────────

  /**
   * ★★ <strong>引用快照随消息落库、逐字段读回</strong>（{@code A4 引用可展开溯源}）。
   *
   * <p>三条断言各自针对一种静默坏法：
   *
   * <ol>
   *   <li>走<strong>领域端口</strong>读回来逐字段相等 ⇒ 整条链（领域 → 适配器 → {@code jsonb} → 领域）是通的；
   *   <li>走<strong>纯 SQL</strong> 再读一次 {@code references} 那一列 ⇒ 证明库里真有这些字段 （只走端口的话，Hibernate 的一级缓存
   *       / 内存对象也能让这条断言变绿）；
   *   <li>{@code index} <strong>从 1 连续递增</strong>（契约要求）在库里也是这个形状。
   * </ol>
   */
  @Test
  void referencesSnapshotRoundTrips() {
    UUID id = UUID.randomUUID();
    List<SourceRef> refs =
        List.of(
            new SourceRef(
                1,
                "第一章 总则",
                "报销管理规定.pdf",
                "制度库",
                0.93,
                1,
                "第一条 为规范…",
                "aa000000-0000-4000-8000-000000000001",
                "bb000000-0000-4000-8000-000000000001",
                "cc000000-0000-4000-8000-000000000001"),
            new SourceRef(
                2,
                "第三章 报销标准",
                "报销管理规定.pdf",
                "制度库",
                0.88,
                null,
                null,
                "aa000000-0000-4000-8000-000000000002",
                "bb000000-0000-4000-8000-000000000001",
                "cc000000-0000-4000-8000-000000000001"),
            new SourceRef(
                3, "附件 A 住宿标准表", "报销管理规定.pdf", "制度库", 0.71, null, null, null, null, null));

    Message saved = new Message(id, CONVERSATION, "");
    saved.appendText("根据《报销管理规定》第三条");
    saved.bindReferences(refs);
    saved.complete(new TokenUsage(120, 45), 3400L);
    messages.save(saved);

    // ① 走领域端口读回来：逐字段相等（含顺序与 index）
    Message reloaded = messages.findById(id).orElseThrow(() -> new AssertionError("刚存的消息读不回来"));
    assertThat(reloaded.references())
        .as("★★ 引用快照必须逐字段相等地读回来（含 index）—— 它是 A4『引用可展开溯源』的载体")
        .containsExactlyElementsOf(refs);

    // ② 走纯 SQL 读那一列：证明它真的在库里，而不是被内存满足
    String rawJson =
        jdbc.queryForObject(
            "select reference_snapshot::text from cv_message where id = ?::uuid", String.class, id);
    assertThat(rawJson)
        .as("★ 库里的 reference_snapshot 列必须真有那 10 个字段的名字（只走端口的话，一条 @Transient 也能让它绿）")
        .contains("\"index\"")
        .contains("\"docName\"")
        .contains("\"kbName\"")
        .contains("\"segmentId\"")
        .contains("\"documentId\"")
        .contains("\"kbId\"")
        .contains("第一章 总则");

    // ③ index 从 1 连续递增（契约口径）
    assertThat(reloaded.references()).extracting(SourceRef::index).containsExactly(1, 2, 3);
  }

  /** ★ 零命中（空列表）与"引用从没到过"（{@code null}）在库里是<strong>两个不同的值</strong>。 */
  @Test
  void zeroHitReferencesAreDistinctFromNeverArrivedReferences() {
    UUID withEmpty = UUID.randomUUID();
    UUID withoutAny = UUID.randomUUID();

    Message empty = new Message(withEmpty, CONVERSATION, "");
    empty.bindReferences(List.of());
    empty.complete(TokenUsage.zero(), 1L);
    messages.save(empty);

    Message untouched = new Message(withoutAny, CONVERSATION, "");
    untouched.complete(TokenUsage.zero(), 1L);
    messages.save(untouched);

    assertThat(messages.findById(withEmpty).orElseThrow().references())
        .as("零命中是明确的空列表（检索跑过了、一条都没中）")
        .isEmpty();
    assertThat(messages.findById(withoutAny).orElseThrow().references())
        .as("★ '引用从没到过'必须是 null —— 与 [] 糊成一个值会让'检索没跑起来'与'零命中'不可区分")
        .isNull();
  }

  // ────────────────────────── ④ 停止保留内容（A5 的持久化侧） ──────────────────────────

  /**
   * ★★ <strong>{@code GENERATING → STOPPED} 之后，库里那一行的 {@code content} 仍是已累积的那一段</strong>。
   *
   * <p>★ 分三步写库（跟真实链路一样：生成中的周期性 flush 会不断写这一行），最后一步是停止： 断言取的是<strong>纯 SQL 读出来的正文</strong>，不是内存对象 ——
   * "停止把内容清空"这种实现 在内存断言下也可能蒙混过关（若它清的是另一个字段）。
   */
  @Test
  void stoppedMessageKeepsItsTextInTheDatabase() {
    UUID id = UUID.randomUUID();
    Message message = new Message(id, CONVERSATION, "");
    message.appendText("根据《报销管理规定》第 3 条，");
    messages.save(message);
    message.appendText("住宿费标准为每人每天 500 元");
    messages.save(message);

    message.stop(2100L);
    messages.save(message);

    assertThat(contentOfRow(id))
        .as("★★ A5：用户点停止之后，库里那一行的正文必须还是已生成的那一段（flush → STOPPED，内容保留）")
        .isEqualTo("根据《报销管理规定》第 3 条，住宿费标准为每人每天 500 元");
    assertThat(statusOfRow(id)).as("状态必须是 STOPPED（一个一等终态，不是 FAILED 的变体）").isEqualTo("STOPPED");
    assertThat(messages.findById(id).orElseThrow().content())
        .as("★ 走领域端口读回来也是同一段（否则'保留'只发生在库里、界面读不到）")
        .isEqualTo("根据《报销管理规定》第 3 条，住宿费标准为每人每天 500 元");
  }

  /** ★ 失败路径同样保留内容，且原因 / 提示都落库（契约 {@code failReason} + {@code failHint}）。 */
  @Test
  void failedMessageKeepsItsTextAndItsReason() {
    UUID id = UUID.randomUUID();
    Message message = new Message(id, CONVERSATION, "");
    message.appendText("半句没说完的答案");

    message.fail("连接中断", "网络或服务异常，可重试", 900L);
    messages.save(message);

    Message reloaded = messages.findById(id).orElseThrow();
    assertThat(reloaded.content()).as("★ 失败也保留已生成内容").isEqualTo("半句没说完的答案");
    assertThat(reloaded.status()).isEqualTo(MessageStatus.FAILED);
    assertThat(reloaded.failReason()).isEqualTo("连接中断");
    assertThat(reloaded.failHint())
        .as("★ 契约 ChatMessage.failHint 的载体 —— 只落 fail_reason 的话'下一步怎么办'无处可存")
        .isEqualTo("网络或服务异常，可重试");
  }

  // ────────────────────────── ⑤ tokens ──────────────────────────

  /**
   * ★★ <strong>失败路径上已经发生的调用仍计入，且 {@code total = prompt + completion}</strong>。
   *
   * <p>★ 断言<strong>纯 SQL 读出来的两列</strong>：这是成本账的真源。走领域端口只证明值对象算得对， 证明不了那两列真的写进去了。
   */
  @Test
  void failedRunStillCountsTokensItAlreadySpent() {
    UUID id = UUID.randomUUID();
    Message message = new Message(id, CONVERSATION, "");
    message.addUsage(new TokenUsage(80, 12));
    message.addUsage(new TokenUsage(30, 4));
    message.fail("生成超时", "可稍后重试", 60000L);
    messages.save(message);

    Integer prompt = tokenOfRow(id, "prompt_tokens");
    Integer completion = tokenOfRow(id, "completion_tokens");

    assertThat(prompt).as("★ 失败之前那两次调用（查询理解 / 多跳判定）必须仍然记账").isEqualTo(110);
    assertThat(completion).isEqualTo(16);
    assertThat(prompt + completion)
        .as("★★ total = prompt + completion（契约 done.tokens 的第三个字段由这两个分量导出）")
        .isEqualTo(126);
    assertThat(messages.findById(id).orElseThrow().tokens().total())
        .as("★ 读回来的领域对象给出的总和必须与库里两列之和一致")
        .isEqualTo(126);
  }

  /** ★ "还没有发生模型调用" ⇒ 两列都是 {@code NULL}，不是 0（成本账的起点）。 */
  @Test
  void noModelCallYetLeavesBothTokenColumnsNull() {
    UUID id = UUID.randomUUID();
    Message message = new Message(id, CONVERSATION, "");
    messages.save(message);

    assertThat(tokenOfRow(id, "prompt_tokens")).as("★ '没调用'必须是 NULL，不是 0").isNull();
    assertThat(tokenOfRow(id, "completion_tokens")).isNull();
    assertThat(messages.findById(id).orElseThrow().tokens()).isNull();
  }

  // ────────────────────────── ⑥ 库层没盖住的那一条（写在这里免得被读成漏了） ──────────────────────────

  /**
   * ★ <strong>往返</strong>：一条完整的助手消息（生成 → 引用 → 完成）读回来逐字段对得上。
   *
   * <p>★ 这条是"整条链通不通"的粗读数；细的那几条各有自己的用例（引用 / 停止 / tokens）。 刻意把 {@code createdAt}
   * 也断言上：{@code @CreationTimestamp} 若没生效它就是 {@code null}， 而那会让"按时间排序的消息列表"整列排在同一个位置（不报错）。
   */
  @Test
  void completedMessageRoundTrips() {
    UUID id = UUID.randomUUID();
    Message message = new Message(id, CONVERSATION, "");
    message.appendText("答案正文");
    message.complete(new TokenUsage(10, 20), 1234L);

    messages.save(message);

    Message reloaded = messages.findById(id).orElseThrow();
    assertThat(reloaded.id()).isEqualTo(id);
    assertThat(reloaded.conversationId()).isEqualTo(CONVERSATION);
    assertThat(reloaded.content()).isEqualTo("答案正文");
    assertThat(reloaded.status()).isEqualTo(MessageStatus.GENERATED);
    assertThat(reloaded.durationMs()).isEqualTo(1234L);
    assertThat(reloaded.tokens()).isEqualTo(new TokenUsage(10, 20));
    assertThat(reloaded.createdAt())
        .as("★ @CreationTimestamp 必须真的写进库（为 null 会让列表排序静默失效）")
        .isNotNull();
    assertThat(reloaded.updatedAt()).isNotNull();
  }

  /** ★ 用户消息往返（MSG-I3 的另一半：它落库就是 {@code GENERATED}，且正文非空）。 */
  @Test
  void userMessageRoundTrips() {
    UUID id = UUID.randomUUID();
    messages.save(Message.user(id, CONVERSATION, "报销标准是多少？"));

    Message reloaded = messages.findById(id).orElseThrow();
    assertThat(reloaded.role().name()).isEqualTo("USER");
    assertThat(reloaded.status()).isEqualTo(MessageStatus.GENERATED);
    assertThat(reloaded.content()).isEqualTo("报销标准是多少？");
    assertThat(reloaded.references()).as("MSG-I4：用户消息不带引用").isNull();
  }

  /** ★ 按会话列出：一个会话的多条消息按时间升序读回（下一批的消息列表端点会要这个序）。 */
  @Test
  void messagesAreListedByConversationInChronologicalOrder() {
    UUID conversation = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      Message message = new Message(UUID.randomUUID(), conversation, "");
      message.appendText("第 " + i + " 条");
      message.complete(TokenUsage.zero(), i);
      messages.save(message);
      // ★ 让三条消息的 created_at 落在不同的毫秒上：库层时间戳的精度不足以区分同一毫秒内的三次写入，
      //   而这条用例断言的正是"按时间升序"
      sleepUntilTheNextMillisecond();
    }

    List<Message> listed = messages.listByConversation(conversation);

    assertThat(listed).as("★ 一个会话的消息必须都能按会话读回来（下一批的消息列表端点靠它）").hasSize(3);
    assertThat(listed).extracting(Message::content).containsExactly("第 0 条", "第 1 条", "第 2 条");
    assertThat(messages.listByConversation(UUID.randomUUID())).as("别的会话读不到这些消息").isEmpty();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 用<strong>纯 SQL</strong>插一条助手消息（绕过 Java），目的是把库层那几道闸单独拎出来测。
   *
   * <p>★ 走 {@code MessageRepository.save} 也能撞上 CHECK，但那时异常会裹在 Hibernate 的 {@code flush}
   * 里、形态不确定；库层那几条断言要的是"库层的拒绝"这一个事实， 所以这里直接用 {@code JdbcTemplate}（与 {@code
   * ConversationPersistenceTest} 同一条做法）。
   */
  private void insertAssistantBySql(String status) {
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status, created_at,"
            + " updated_at) values (?::uuid, ?::uuid, 'ASSISTANT', '', ?, now(), now())",
        UUID.randomUUID(),
        CONVERSATION,
        status);
  }

  /** 只读 {@code flyway_schema_history} 的那一格；没有这一版就返回 null（而不是抛异常 —— 断言消息要能自己开口）。 */
  private Boolean successOf(String version) {
    List<Boolean> rows =
        jdbc.queryForList(
            "select success from flyway_schema_history where version = ?", Boolean.class, version);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String contentOfRow(UUID id) {
    return jdbc.queryForObject(
        "select content from cv_message where id = ?::uuid", String.class, id);
  }

  private String statusOfRow(UUID id) {
    return jdbc.queryForObject(
        "select status from cv_message where id = ?::uuid", String.class, id);
  }

  /** 睡过一个毫秒边界；被打断就恢复中断位并抛出（不吞）。 */
  private static void sleepUntilTheNextMillisecond() {
    try {
      Thread.sleep(2L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待毫秒边界时被中断", e);
    }
  }

  private Integer tokenOfRow(UUID id, String column) {
    return jdbc.queryForObject(
        "select " + column + " from cv_message where id = ?::uuid", Integer.class, id);
  }
}
