package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 会话密级的<strong>写侧</strong>真栈读数（Issue {@code #108} · 择一 (a) 库侧单调）—— 真 PostgreSQL + 真 Flyway，不是一个字都
 * mock。
 *
 * <h3>★★ 这条账在验什么（以及为什么必须"真并发"）</h3>
 *
 * <p>载体 {@code cv_conversation.max_classification} 的口径是 <strong>"会话密级 = 历史引用的 max"</strong>（{@code
 * 13 §2.2}）。而"用 {@code save()} 维护它"是<strong>读 → 改 →
 * 更新</strong>：<strong>同事务之外</strong>的两笔抬升各自读到同一个现值、 各自提交 ⇒ 后提交的那一次用<strong>陈旧现值</strong>覆盖 ⇒
 * 会话密级滞后于真实 max（不报错、不留痕）。 所以判据只能是"<strong>两条线程真的同时抬升</strong>，终值恒为两者的
 * max"，而且<strong>反向顺序也要跑</strong> —— 见 {@link #concurrentRaisesEndAtTheMaximum()} 与 {@link
 * #concurrentRaisesInTheReverseOrderAlsoEndAtTheMaximum()}。
 *
 * <h3>★★ 四个读数各自钉什么（坏掉的症状完全不同）</h3>
 *
 * <ol>
 *   <li><strong>并发（真）</strong>：16 轮 × 两条线程 + {@link CyclicBarrier} 对齐起跑 ⇒ 每轮的终值都必须是 {@code max}。★ 第
 *       2 个用例把两档对调 —— 只跑一个顺序的话，"恰好对了的那一次"会被当成通过；
 *   <li><strong>库侧那条语句的语义</strong>：只升 / 不降 / 相等幂等 / {@code null} 是"不变" / 会话不在 ⇒ {@code
 *       false}（顺序用例，不冒充并发）；
 *   <li><strong>领域方法与 SQL 的单调口径一致</strong>：两侧各自抬升<strong>同一序列</strong>（16 对 {@code (现值, 本轮)}）⇒
 *       终值与"是否真的抬升"都必须相同 —— 这是择一 (a) 的<strong>代价</strong>（口径要在 SQL 里再写一遍） 的处置读数；
 *   <li><strong>{@code #108} 正文那条示意 SQL 为什么不能照抄</strong>：{@code greatest} 比的是<strong>字典序</strong>，
 *       而它与密级次序不一致（PostgreSQL 实测）。
 * </ol>
 *
 * <h3>★★ 金丝雀（本类的核心可证伪性）</h3>
 *
 * <p>把 {@code ConversationRepositoryAdapter#raiseClassification} 改回"{@code findById} → 领域方法 →
 * {@code save}"（即 {@code #108} 里那条坏路径）⇒ <strong>本类第 1、2 条用例必须变红</strong> （终值停在"最后提交的那一档"而不是 max）。★
 * 它们**不会**在语义用例（第 2、3 条）上变红 —— 那正是这条账的性质：单线程全绿、并发才错。
 *
 * <p>★ 另有一条<strong>反向</strong>读数 {@link #residualStaleEntityWriteBackIsStillPossible()}： 老的 {@code
 * save()} 写法今天仍能把陈旧实体写回去（本批没封死，两条封死路线都牵动既有领域单测）—— 它写下来是为了"谁封死了它会变红"，而不是把它当成口径（详见交付报告"待 Lead 裁决"）。
 */
@SpringBootTest
@Testcontainers
class ConversationClassificationWriteTest {

  /** 并发轮数。★ 太少会让"丢更新"这种竞态偶发地逃过（金丝雀就成了掷骰子）。 */
  private static final int ROUNDS = 16;

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 夹具里的"我"。★ 固定值：归属列断言的就是它（与 {@code ConversationPersistenceTest} 同口径）。 */
  private static final UUID CURRENT_USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Autowired JdbcTemplate jdbc;

  @Autowired ConversationRepository conversations;

  // ────────────────────────── ① 真并发：终值 = max ──────────────────────────

  /**
   * ★★ 两条线程<strong>同时</strong>抬升同一会话（机密 vs 秘密）⇒ 终值必须是 max。
   *
   * <p>★ 为什么是"两条线程 + {@link CyclicBarrier} + 多轮"而不是"顺序调用两次"：顺序调用<strong>永远</strong>是对的
   * （第二次读得到第一次的值），它证明不了任何事。丢更新只在<strong>两笔事务重叠</strong>时发生。
   */
  @Test
  void concurrentRaisesEndAtTheMaximum() throws Exception {
    assertConcurrentRaisesEndAtTheMaximum(
        ClassificationLevel.CONFIDENTIAL, ClassificationLevel.SECRET);
  }

  /**
   * ★★ 反向顺序：把两档对调（秘密 vs 机密）⇒ 仍然必须是 max。
   *
   * <p>★ 单独一条用例的理由：{@code #108} 正文里那个"恰好对了"的例子就是<strong>顺序凑巧</strong> —— 只跑一个顺序，等于只验了"恰好对了的那一次"。
   */
  @Test
  void concurrentRaisesInTheReverseOrderAlsoEndAtTheMaximum() throws Exception {
    assertConcurrentRaisesEndAtTheMaximum(
        ClassificationLevel.SECRET, ClassificationLevel.CONFIDENTIAL);
  }

  // ────────────────────────── ② 库侧那条语句的语义（顺序） ──────────────────────────

  /**
   * 库侧那条单调 UPDATE 的四条语义：只升 / 不降 / 相等幂等 / 无引用（{@code null}）不变。
   *
   * <p>★ 与领域侧同源（{@code ConversationClassificationTest} 的 18 条覆盖）—— 这里是<strong>落库那一侧</strong>
   * 的读数：那四条必须由<strong>语句本身</strong>保证，而不是靠调用方记得先比较。
   *
   * <p>★ 顺带钉两条边界：{@code refsMax = PUBLIC}（没有更低的档 ⇒ 不可能抬升，走的是适配器那个空集合早退） 与会话不存在（{@code
   * false}，而不是抛异常或静默算成功）。
   */
  @Test
  void databaseStatementRefusesToDowngradeAndIsIdempotent() {
    UUID id = newConversation();

    assertThat(conversations.raiseClassification(id, ClassificationLevel.CONFIDENTIAL))
        .as("PUBLIC → 机密：必须真的抬升（返回 true 表示'那一行被改了'）")
        .isTrue();
    assertThat(classificationOfRow(id)).isEqualTo("CONFIDENTIAL");

    assertThat(conversations.raiseClassification(id, ClassificationLevel.CONFIDENTIAL))
        .as("相等幂等：同一档再来一次不算抬升（否则每一次重复引用都会报一次'抬升了'）")
        .isFalse();
    assertThat(conversations.raiseClassification(id, ClassificationLevel.SECRET))
        .as("★★ 降密必须一行都不改 —— 这一条就是'只升不降'在库侧的读数")
        .isFalse();
    assertThat(conversations.raiseClassification(id, ClassificationLevel.PUBLIC))
        .as("PUBLIC 是最低档：没有任何现值能严格低于它 ⇒ 也走'不改'（空集合早退那条路）")
        .isFalse();
    assertThat(conversations.raiseClassification(id, null))
        .as("★ 本轮没有引用（null）= 空集合的 max 是单位元 ⇒ 不变，且不抛异常（那是正常路径）")
        .isFalse();
    assertThat(classificationOfRow(id))
        .as("★★ 上面四次之后库里必须仍停在机密：这里如果动了，就是一次静默降密")
        .isEqualTo("CONFIDENTIAL");

    assertThat(
            conversations.raiseClassification(UUID.randomUUID(), ClassificationLevel.CONFIDENTIAL))
        .as("会话不存在 ⇒ false（一行都没改），而不是'报成功'—— 调用方要能分辨这两种")
        .isFalse();
  }

  // ────────────────────────── ③ 领域方法与 SQL 的口径一致（择一 (a) 的代价处置） ──────────────────────────

  /**
   * ★★ <strong>领域方法与库侧 UPDATE 的单调口径逐对一致</strong>（{@code #108} 判据 2）。
   *
   * <p>择一 (a) 的代价是"口径要在 SQL 里再写一遍"——它的处置就是这条用例：两侧各自从 {@code PUBLIC} 抬升到 {@code current}、再抬升到 {@code
   * refs}（16 对 {@code (现值, 本轮)} 全覆盖），然后比对 <strong>终值</strong>与<strong>"是否真的抬升了"</strong>。两者都必须相同。
   *
   * <p>★ 它能红在哪：把那条 SQL 的 {@code where … in (严格更低的档)} 改坏（去掉、写成 {@code !=}、或把集合取反） ⇒ 某一对的结果与领域侧分叉 ⇒
   * 立刻红。★ 领域侧那一半也不是"念一遍代码"：它真的写到库里再从库里读回来。
   */
  @ParameterizedTest
  @EnumSource(ClassificationLevel.class)
  void domainRuleAndDatabaseStatementAgreeOnEverySequence(ClassificationLevel current) {
    for (ClassificationLevel refs : ClassificationLevel.values()) {
      UUID viaStatement = newConversation();
      UUID viaDomain = newConversation();

      // ── 库侧：两条 UPDATE（这条路径没有实体）──
      if (current != ClassificationLevel.PUBLIC) {
        assertThat(conversations.raiseClassification(viaStatement, current))
            .as("前置：库侧先把现值抬到 %s", current)
            .isTrue();
      }
      boolean raisedByStatement = conversations.raiseClassification(viaStatement, refs);

      // ── 领域侧：同一序列，但走"内存里的领域方法 + save"（顺序调用，与并发无关）──
      Conversation entity = conversations.findById(viaDomain).orElseThrow();
      if (current != ClassificationLevel.PUBLIC) {
        assertThat(entity.raiseClassification(current)).as("前置：领域侧先把现值抬到 %s", current).isTrue();
        conversations.save(entity);
      }
      boolean raisedByDomain = entity.raiseClassification(refs);
      conversations.save(entity);

      assertThat(classificationOfRow(viaStatement))
          .as("现值 %s、本轮 %s ⇒ 两侧的终值必须相同", current, refs)
          .isEqualTo(classificationOfRow(viaDomain))
          .isEqualTo(current.max(refs).name());
      assertThat(raisedByStatement)
          .as("现值 %s、本轮 %s ⇒ '是否真的抬升'两侧也必须相同（相等幂等 / 下调在这两侧都必须是 false）", current, refs)
          .isEqualTo(raisedByDomain)
          .isEqualTo(refs.ordinal() > current.ordinal());
    }
  }

  // ────────────────────────── ④ 那条示意 SQL 为什么不能照抄 ──────────────────────────

  /**
   * ★★ {@code #108} 正文里的 {@code greatest(max_classification, ?)} <strong>不能照抄</strong>：它比的是
   * <strong>字典序</strong>，而四个机器码的字典序与密级次序<strong>不一致</strong>。
   *
   * <p>这一条把"为什么本实现不比较字符串"变成<strong>库里的读数</strong>：{@code greatest('CONFIDENTIAL', 'SECRET')} 在
   * PostgreSQL 上返回 {@code 'SECRET'} ⇒ 照抄那句 SQL 会在"机密 + 秘密"这一次上<strong>静默降密</strong> （而它恰好是 {@code
   * #108} 正文举的那个例子）。★ 正确的那一条（按"严格更低的档"判定）在同一序列上停在机密。
   */
  @Test
  void theLiteralGreatestWouldDowngradeSoTheStatementDoesNotUseIt() {
    assertThat(
            jdbc.queryForObject(
                "select greatest('CONFIDENTIAL'::text, 'SECRET'::text)", String.class))
        .as(
            "★ 字典序不是密级次序：CONFIDENTIAL < SECRET 按字典序成立 ⇒ greatest 会把机密写成秘密"
                + "（所以库侧那条语句不比较字符串，而是按'严格更低的档'判定）")
        .isEqualTo("SECRET");

    UUID id = newConversation();
    assertThat(conversations.raiseClassification(id, ClassificationLevel.CONFIDENTIAL)).isTrue();
    assertThat(conversations.raiseClassification(id, ClassificationLevel.SECRET))
        .as("正确实现：机密之后再报'秘密' ⇒ 一行都不改")
        .isFalse();
    assertThat(classificationOfRow(id))
        .as("★ 同一序列按本实现走 ⇒ 停在机密（与上面那条 greatest 的读数形成对照）")
        .isEqualTo("CONFIDENTIAL");
  }

  // ────────────────────────── ⑤ 没封死的那一条（写下来，免得被读成已封死） ──────────────────────────

  /**
   * ★★ <strong>当前事实：老的 {@code save()} 写法仍能把陈旧实体写回去</strong>（本批<strong>没有</strong>封死它）。
   *
   * <p>这条读数是刻意反着写的，理由与 {@code caliber} 那一族"钉住当前没做这件事"的用例同源： 本批把"抬升"的<strong>新入口</strong>做成库侧一条
   * UPDATE（不接收实体 ⇒ 那条路不可能写陈旧值）， 但 {@code save(Conversation)} 仍然会写 {@code max_classification} 这一列 ⇒
   * 调用方若沿用旧处方 （{@code findById} → 领域方法 → {@code save}）就<strong>原样把丢更新搬回来</strong>。
   *
   * <p>★ 为什么本批不封死：两条封死路线都牵动既有领域单测（① 列加 {@code updatable = false} —— 领域方法就再也落不了库，{@code
   * ConversationPersistenceTest} 里两条"抬升落库"的读数要改成走新入口， 且内存对象会与库分叉；② 去掉实体上的可变字段——那要重写 18 条领域单测）。属**待
   * Lead 裁决**，见交付报告。
   *
   * <p>★★ 这条断言的价值在于<strong>它会变红</strong>：谁把 {@code save()} 这条路封死了（两条路线之一）， 它立刻红 —— 那时把这条读数连同 {@code
   * Conversation}/{@code ConversationRepository} 的类注释一起改掉。
   */
  @Test
  void residualStaleEntityWriteBackIsStillPossible() {
    UUID id = newConversation();

    // ① 一笔事务读到现值（PUBLIC）并把它留在手上 —— 这就是"陈旧"
    Conversation stale = conversations.findById(id).orElseThrow();
    // ② 另一笔事务把会话抬到机密（新的那条路：库侧单调 UPDATE）
    assertThat(conversations.raiseClassification(id, ClassificationLevel.CONFIDENTIAL)).isTrue();
    // ③ 陈旧的那一份按"内存里的现值"判定 ⇒ 它认为自己可以抬升
    assertThat(stale.raiseClassification(ClassificationLevel.SECRET))
        .as("★ 领域守卫只看内存里的现值（它不知道库里已经升到机密了）⇒ 返回 true")
        .isTrue();
    conversations.save(stale);

    assertThat(classificationOfRow(id))
        .as(
            "★★ 当前事实（不是口径）：库里被写成了 SECRET，而真实 max 是 CONFIDENTIAL"
                + " —— 旧的 save() 处方仍能把丢更新搬回来。谁封死了 save() 这条路，这条断言会红")
        .isEqualTo("SECRET");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 两条线程各自一个事务、用闸门对齐起跑，然后断言每一轮的终值都是两者的 max。
   *
   * @param first 第一条线程抬到的档
   * @param second 第二条线程抬到的档（★ 与 {@code first} 对调即"反向顺序"）
   */
  private void assertConcurrentRaisesEndAtTheMaximum(
      ClassificationLevel first, ClassificationLevel second) throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      UUID id = newConversation();
      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        CyclicBarrier gate = new CyclicBarrier(2);
        Future<Boolean> firstTask = pool.submit(() -> raiseAfterGate(gate, id, first));
        Future<Boolean> secondTask = pool.submit(() -> raiseAfterGate(gate, id, second));
        boolean firstRaised = firstTask.get(30, TimeUnit.SECONDS);
        boolean secondRaised = secondTask.get(30, TimeUnit.SECONDS);

        assertThat(firstRaised | secondRaised)
            .as(
                "第 %d 轮：两次抬升（%s / %s）至少有一次真的落到库上（都为 false 说明这一轮根本没写，" + "那下面的终值断言就什么都没验）",
                round, first, second)
            .isTrue();
      }

      assertThat(classificationOfRow(id))
          .as(
              "★★ 第 %d 轮：两条线程真并发抬升同一会话（%s 与 %s）之后，终值必须是两者的 max" + "（这里若停在较低的那一档，就是一次丢更新 —— 而且它不报错）",
              round, first, second)
          .isEqualTo(first.max(second).name());
    }
  }

  /** 等闸门对齐（两条线程同时起跑），再走库侧那条抬升。 */
  private boolean raiseAfterGate(CyclicBarrier gate, UUID id, ClassificationLevel level) {
    try {
      gate.await(30, TimeUnit.SECONDS);
    } catch (Exception failed) {
      throw new IllegalStateException("并发闸门没能对齐", failed);
    }
    return conversations.raiseClassification(id, level);
  }

  /** 建一个会话（从 PUBLIC 起）并返回它的 id。 */
  private UUID newConversation() {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, CURRENT_USER, null, ""));
    return id;
  }

  /** 纯 SQL 读那一列（★ 不经过领域对象：断言要钉的是<strong>库里那一行</strong>）。 */
  private String classificationOfRow(UUID id) {
    return jdbc.queryForObject(
        "select max_classification from cv_conversation where id = ?::uuid", String.class, id);
  }
}
