package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
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
 * {@code cv_conversation} 的真栈读数（Issue {@code #27} · 迁移 {@code V32}）—— <strong>真 PostgreSQL + 真
 * Flyway</strong>，不是一个字都 mock。
 *
 * <h3>这个类钉三件事，它们坏掉的症状完全不同（所以分开断言、各自给可读原因）</h3>
 *
 * <ol>
 *   <li><strong>迁移接上了、而且没碰坏上一版</strong>（{@link #migrationIsAppliedAfterExistingChain()}）：{@code
 *       version='32'} 成功；★ 同时断言 {@code version='31'} 也成功 —— 那是"没碰坏上一版"的<strong>回归读数</strong>。 ★
 *       两条一起断言的道理：V32 没跑 ⇒ 表根本不存在（整类起不来，看起来像"代码有问题"）； 而改动更早的迁移 ⇒ Flyway checksum
 *       不匹配、后端起不来（看起来像"环境有问题"）。
 *   <li><strong>列的不变量</strong>（非空 / 取值受 CHECK 约束 / 默认档）—— {@code 02 §5.2} 与 {@code 13 §2.2} 给这一列的
 *       I1–I3。★ 这三条<strong>必须落在库层</strong>：它们要挡住的是"绕过领域层的一切写入路径"， 而领域层的方法只在应用进程里生效。
 *   <li><strong>领域抬升真的写得进、读得回</strong>（{@link #raisedClassificationRoundTrips()}）：{@link
 *       Conversation#raiseClassification} 是"只升不降"的守卫，而它必须与库层那一列接上 —— 否则守卫守的是一个内存里的字段。
 * </ol>
 *
 * <p>★ 为什么走 {@code ConversationRepository}（领域端口）而不是 {@code JpaConversationRepository}： 端口 +
 * 适配器是这一批的交付物之一， 走端口才证明"领域接口 → 适配器 → 表"这条链是通的。 ★ 也<strong>不走 HTTP</strong>：本批刻意不加端点、不改契约（见交付报告）。
 *
 * <p>★ I4（单调递增）<strong>不在这里</strong> —— 库层表达不了它（{@code CHECK} 不能跨行比较），它在纯单测 {@code
 * ConversationClassificationTest} 里。把方向性的不变量塞进需要容器的类，会让它"只在容器起得来时才被检查"。
 */
@SpringBootTest
@Testcontainers
class ConversationPersistenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 夹具里的"我"。★ 固定值：归属列断言的就是它，随机值会让"存进去的是不是这个人"无法核对。 */
  private static final UUID CURRENT_USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Autowired JdbcTemplate jdbc;

  @Autowired ConversationRepository conversations;

  // ────────────────────────── ① 迁移接上了，且没碰坏上一版 ──────────────────────────

  /**
   * {@code version='32'} 成功（本批的迁移真的跑了）+ {@code version='31'} 成功（上一版没被碰坏）。
   *
   * <p>★ 断言"成功"而不是"存在一行"：{@code flyway_schema_history} 里失败的行也会留下记录。 只看"在不在"的话，一次失败迁移会被读成"跑过了"。
   */
  @Test
  void migrationIsAppliedAfterExistingChain() {
    assertThat(successOf("32"))
        .as("Flyway 里没有 version=32 的成功记录 ⇒ conversation/V32 没被执行（cv_conversation 这张表不在）")
        .isEqualTo(Boolean.TRUE);
    assertThat(successOf("31"))
        .as(
            "★ V31（knowledge 问答对留缝）的成功记录不见了 —— 本批一个字都不许改更早的迁移"
                + "（改了 checksum 就对不上，后端起不来）。这一条是'没碰坏上一版'的回归读数")
        .isEqualTo(Boolean.TRUE);
  }

  // ────────────────────────── ② 列的不变量（库层那三道闸） ──────────────────────────

  /**
   * I1 + I2：{@code max_classification} 非空（{@code nullable = 'NO'}）且默认档是 {@code PUBLIC}。
   *
   * <p>★ 两条一起断言的道理：{@code NOT NULL} 是"这个问题永远有答案"，默认值是"新会话的答案是 PUBLIC"。 少任何一个，症状都不同 —— 少非空 ⇒
   * "还没抬升过"与"忘了写"长得一模一样；少默认 ⇒ 每条 INSERT 都必须显式带上它， 而漏带的那一次是插入报错（好的那种）还是静默落一个空值（坏的那种）取决于列定义。
   */
  @Test
  void maxClassificationIsNotNullAndDefaultsToPublic() {
    assertThat(columnNullable("max_classification"))
        .as("★★ max_classification 必须 NOT NULL —— 可空会让'还没抬升过'和'忘了写'长得一模一样")
        .isEqualTo("NO");
    assertThat(columnDefault("max_classification"))
        .as("默认档必须是 'PUBLIC'（新会话没有引用过任何 chunk ⇒ 累计值就是最低档，02 §2.6）")
        .contains("PUBLIC");
  }

  /**
   * ★ <strong>省略这一列的 INSERT 落下来的就是 {@code 'PUBLIC'}</strong> —— 直接读库，不经过 Java。
   *
   * <p>★ 为什么不只用 {@code information_schema}：那个视图说的是"列上写了什么默认值"，
   * 而这里要的是"<strong>真的没写它的时候落下来什么</strong>"。 中间隔着一次解析与一次赋值，而"默认值没生效"这件事<strong>不会报错</strong>。
   */
  @Test
  void omittedColumnFallsBackToPublicInTheDatabase() {
    UUID id = insertRowBySql(null);

    assertThat(classificationOfRow(id))
        .as("★★ 建一个会话时不写 max_classification ⇒ 库必须落 'PUBLIC'（新会话的累计密级）")
        .isEqualTo("PUBLIC");
  }

  /**
   * I1 的写入侧：显式传 {@code null} ⇒ <strong>被库拒绝</strong>。
   *
   * <p>★ "有默认值"与"允许显式写 null"是两件事：允许后者的话，一条 {@code insert … max_classification = null} 会把
   * "这个会话看过什么"变成未知 —— 而未知在权限判定里是最危险的取值（它既不是"看不见"也不是"看得见"， 各读点会各自解释）。
   */
  @Test
  void explicitNullIsRejectedByTheDatabase() {
    assertThatThrownBy(() -> insertRowBySql("null"))
        .as("★ 显式写 null 必须被库拒绝（NOT NULL）—— 未知的会话密级是最危险的取值：各读点会各自解释它")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * I3：四个机器码之外的任何取值都被 CHECK 拒绝。
   *
   * <p>★ 这一条挡的是"将来有人加第五档却只改了 Java 枚举"：那种错的症状<strong>不是报错</strong>， 而是比较运算悄悄变味（{@code
   * ClassificationLevel} 的类注释把这条写成了"顺序是语义的一部分"）。 库层与枚举必须同时改 —— 而"忘了改另一处"这件事，只有两边各有一条断言时才抓得住。
   *
   * <p>★ 顺带断言一条<strong>大小写</strong>：{@code 'public'} 也必须被拒。机器码是 SCREAMING_SNAKE_CASE（{@code 14
   * §2.1}），而"大小写不敏感地落库"会让同一个档在库里出现两种写法、让等值比较漏掉一半。
   */
  @Test
  void unknownClassificationValuesAreRejectedByCheckConstraint() {
    for (String illegal : List.of("TOP_SECRET", "public", "机密", "")) {
      assertThatThrownBy(() -> insertRowBySql("'" + illegal + "'"))
          .as("★ 非法密级 '%s' 必须被 chk_conv_max_classification 拒绝（四个机器码之外的一切取值）", illegal)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  /**
   * 会话列表索引在（{@code idx_conv_user}，与 {@code 02 §5.2} 同名）。
   *
   * <p>★ 为什么要读这一条：索引<strong>少一个不会报错</strong>，只会让"我的会话"在量产数据上变慢 —— 而那时候没人会把它与本批的迁移联系起来。★ 索引名逐字照
   * {@code 02 §5.2} 的 DDL，改名的代价是"文档里那句建索引的话失效"。
   */
  @Test
  void conversationListIndexExists() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = current_schema()"
                    + " and tablename = 'cv_conversation' and indexname = 'idx_conv_user'",
                Integer.class))
        .as("★★ idx_conv_user 不在 ⇒ 会话列表走全表扫（不报错，只是越来越慢）")
        .isEqualTo(1);
  }

  // ────────────────────────── ③ 领域抬升真的落到表上 ──────────────────────────

  /**
   * ★★ <strong>自己验自己：{@code max_classification} 真的是一列独立的数据</strong>（G1 的"能红"读数）。
   *
   * <p>前面每一条"库里仍是机密"的读数都建立在同一个前提上：那几行<strong>确实把值写在了库里</strong>。如果 Hibernate 哪天把它映射成
   * {@code @Transient}、或者适配器改成每次读都从别处算，那么"读回来是机密"这件事会由<strong>内存</strong>满足 ——
   * 而所有断言仍然全绿。这一条用<strong>纯 SQL</strong>改一行，再看仓库读出来的是什么：
   *
   * <ul>
   *   <li>读出来是 {@code CONFIDENTIAL} ⇒ 这一列是真数据源（绿）；
   *   <li>读出来还是 {@code SECRET} ⇒ 那些"往返"读数其实什么都没证明（红）。
   * </ul>
   *
   * <p>★ 为什么值得写：本项目最贵的病是"看着在做、实际没生效"，而它的判据是<strong>"被测对象坏掉时它会不会红"</strong> ——
   * 只能由一次刻意的、与生产写入路径无关的改动产生。
   */
  @Test
  void columnIsTheRealSourceOfTruth() {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, CURRENT_USER, null, ""));
    assertThat(classificationOfRow(id)).as("前提：新会话落库就是 PUBLIC").isEqualTo("PUBLIC");

    // ★ 绕过一切 Java 路径，直接改库里那一行。
    //   ★ 不需要先 flush：仓库的每一次调用都在自己的事务里（写完即提交），Hibernate 的一级缓存
    //     不跨事务存活 —— 所以下面那次 findById 只能从库里读。
    jdbc.update(
        "update cv_conversation set max_classification = 'CONFIDENTIAL' where id = ?::uuid", id);

    assertThat(
            conversations
                .findById(id)
                .orElseThrow(() -> new AssertionError("刚存的会话读不回来"))
                .maxClassification())
        .as("★★ 纯 SQL 改成 CONFIDENTIAL 之后，仓库读出来的必须也是它 —— 否则前面那些'往返'读数是由内存满足的，等于没验")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  /**
   * ★ 往返：建会话（{@code PUBLIC}）→ 抬到 {@code CONFIDENTIAL} → <strong>重新从库里读回来</strong>仍是机密。
   *
   * <p>★ 关键是"重新读"而不是"看着内存里那个对象"：后者证明不了任何落库的事。这里断言<strong>库里那一行</strong>的值 ——
   * 领域守卫守的必须是持久化的那一列，否则它守的是一个内存字段。
   */
  @Test
  void raisedClassificationRoundTrips() {
    UUID id = UUID.randomUUID();
    Conversation saved = conversations.save(new Conversation(id, CURRENT_USER, null, ""));
    assertThat(saved.maxClassification())
        .as("前提：新会话从 PUBLIC 起（否则'抬升'这件事没有起点）")
        .isEqualTo(ClassificationLevel.PUBLIC);

    saved.raiseClassification(ClassificationLevel.CONFIDENTIAL);
    conversations.save(saved);

    assertThat(
            conversations
                .findById(id)
                .orElseThrow(() -> new AssertionError("刚存的会话读不回来"))
                .maxClassification())
        .as("★★ 抬升必须落到 cv_conversation.max_classification 上（领域守卫守的是持久化的那一列）")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  /**
   * ★ 反向：尝试"降密"之后，<strong>库里那一行</strong>仍是机密。
   *
   * <p>这是本批最值钱的一条真栈读数 —— 纯单测证明"内存里的字段没被改"，而这一条证明"没有任何写路径把它改回去"： 走的是与生产完全同一条链（领域方法 → 适配器 →
   * 表），<strong>没有任何一处 SQL 直接写它</strong>。
   */
  @Test
  void loweringNeverReachesTheDatabase() {
    UUID id = UUID.randomUUID();
    Conversation conversation = conversations.save(new Conversation(id, CURRENT_USER, null, ""));
    conversation.raiseClassification(ClassificationLevel.SECRET);
    conversations.save(conversation);

    // ★ 一次正常路径的"回落"输入：本轮只引用了公开 chunk
    assertThat(conversation.raiseClassification(ClassificationLevel.PUBLIC))
        .as("★ 下调在领域层就不生效（详见 ConversationClassificationTest）")
        .isFalse();
    conversations.save(conversation);

    assertThat(classificationOfRow(id))
        .as("★★ 库里的会话必须仍停在 SECRET：这里如果变成 PUBLIC，就是一次静默降密（而且它已经是后续每一轮回答的输入）")
        .isEqualTo("SECRET");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 只读 {@code flyway_schema_history} 的那一格；没有这一版就返回 null（而不是抛异常 —— 断言消息要能自己开口）。 */
  private Boolean successOf(String version) {
    List<Boolean> rows =
        jdbc.queryForList(
            "select success from flyway_schema_history where version = ?", Boolean.class, version);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 用<strong>纯 SQL</strong>建一行（绕过 Java），目的是把库层那三道闸单独拎出来测。
   *
   * <p>★ {@code classificationSql} 传 {@code null} 表示"INSERT 里根本不出现这一列"（验默认档）； 传字符串（如 {@code "null"}
   * / {@code "'TOP_SECRET'"}）表示显式写它 —— 两种形状的语义完全不同，所以不合并成一个方法。
   */
  private UUID insertRowBySql(String classificationSql) {
    UUID id = UUID.randomUUID();
    String columns = "insert into cv_conversation (id, user_id, title, created_at, updated_at";
    String values = "values (?::uuid, ?::uuid, '', now(), now()";
    if (classificationSql != null) {
      columns += ", max_classification";
      values += ", " + classificationSql;
    }
    jdbc.update(columns + ") " + values + ")", id, CURRENT_USER);
    return id;
  }

  private String classificationOfRow(UUID id) {
    return jdbc.queryForObject(
        "select max_classification from cv_conversation where id = ?::uuid", String.class, id);
  }

  private String columnNullable(String column) {
    return jdbc.queryForObject(
        "select is_nullable from information_schema.columns"
            + " where table_name = 'cv_conversation' and column_name = ?",
        String.class,
        column);
  }

  private String columnDefault(String column) {
    return jdbc.queryForObject(
        "select column_default from information_schema.columns"
            + " where table_name = 'cv_conversation' and column_name = ?",
        String.class,
        column);
  }
}
