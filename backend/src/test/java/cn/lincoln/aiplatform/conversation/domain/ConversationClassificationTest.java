package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ★★ 会话密级<strong>只升不降</strong>（Issue {@code #27} · {@code 13 §2.2}）—— 纯领域单测，不起 Spring、不起容器。
 *
 * <h3>为什么这一条必须落在领域层、且必须由单测钉住</h3>
 *
 * <p>会话密级 = {@code max(该会话历史里所有引用过的 chunk)}：<strong>一旦引用过机密，整个会话就是机密的</strong>。 它是
 * <strong>跨轮累计</strong>的水位，所以它的不变量是一条<strong>方向</strong>（只能往上），而不是一个取值范围。
 *
 * <p>★ 方向的错法<strong>不会报错</strong>：写成 {@code maxClassification = refsMax}（而不是取 max），那么"本轮只引用了公开
 * chunk"这个<strong>完全正常</strong>的输入就会把会话静默降回公开 —— 而它是后续每一轮回答的输入，一次降密污染它之后的一切。 {@code
 * Conversation.raiseClassification} 把这条路径在领域层封死，本类就是那条封死的读数。
 *
 * <h3>★ 为什么是纯单测（不跟真库一起测）</h3>
 *
 * <p>这条不变量的载体是<strong>领域对象</strong>，库层表达不了它（{@code CHECK} 不能跨行比较）。把它塞进一个需要 Testcontainers
 * 的类里，会让"方向写反了"这件事<strong>只在容器起得来时才被检查</strong> —— 而一个能因为环境而整类跳过的闸门， 等于没有闸门。真库那一侧（非空 / CHECK / 默认档
 * / 往返）在 {@code ConversationPersistenceTest} 里，两者刻意分开。
 */
class ConversationClassificationTest {

  // ────────────────────────── ① 低 → 高：生效 ──────────────────────────

  /** 新会话的累计密级是 {@code PUBLIC} —— "还没引用过任何 chunk"是事实，不是占位。 */
  @Test
  void newConversationStartsAtPublic() {
    assertThat(newConversation().maxClassification())
        .as("新会话没有引用过任何 chunk ⇒ 累计密级必须是最低档（空引用集合的 max）")
        .isEqualTo(ClassificationLevel.PUBLIC);
  }

  /**
   * {@code PUBLIC → CONFIDENTIAL}：抬升生效，且返回值是 {@code true}。
   *
   * <p>★ 断言返回值而不是"读回来等于什么"：写入方要据此决定"值不值得写 / 要不要发领域事件"， 而"让调用方自己比较写前写后"等于把同一个口径再抄一遍 —— 抄错的那一次就是降密。
   */
  @Test
  void raisingToHigherLevelTakesEffect() {
    Conversation conversation = newConversation();

    assertThat(conversation.raiseClassification(ClassificationLevel.CONFIDENTIAL))
        .as("低密级会话引用到机密 chunk ⇒ 必须真的抬升（返回 true 表示'值变了、需要写库'）")
        .isTrue();
    assertThat(conversation.maxClassification()).isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  /**
   * 抬升是<strong>逐级</strong>的：每一档都要能抬上去。
   *
   * <p>★ 用 {@code @EnumSource} 遍历四档而不是写一条 {@code PUBLIC → CONFIDENTIAL}：只测"一步到顶"的话，
   * 一段把次序判反的实现（例如比较了字符串而不是序）在极端值上可能恰好是对的。
   */
  @ParameterizedTest
  @EnumSource(ClassificationLevel.class)
  void everyLevelCanBeRaisedTo(ClassificationLevel target) {
    Conversation conversation = newConversation();

    boolean raised = conversation.raiseClassification(target);

    assertThat(raised)
        .as("从 PUBLIC 抬到 %s：除 PUBLIC 自身外都必须生效（相等幂等那条见另一个用例）", target)
        .isEqualTo(target != ClassificationLevel.PUBLIC);
    assertThat(conversation.maxClassification()).isEqualTo(target);
  }

  // ────────────────────────── ② 高 → 低：不生效（本类的核心） ──────────────────────────

  /**
   * ★★ {@code CONFIDENTIAL → PUBLIC}：<strong>不生效</strong>，且会话仍停在机密。
   *
   * <p>这是本类存在的理由。若这条红了：会话密级可以被"本轮只引用了公开 chunk"这一个正常输入静默降回去 —— 不报错、不留痕迹，而后续每一轮回答都按低密处理。
   */
  @Test
  void loweringIsRefusedAndKeepsTheHigherLevel() {
    Conversation conversation = newConversation();
    conversation.raiseClassification(ClassificationLevel.CONFIDENTIAL);

    assertThat(conversation.raiseClassification(ClassificationLevel.PUBLIC))
        .as("★ 下调必须不生效（返回 false）——'一旦引用过机密，整个会话就是机密的'")
        .isFalse();
    assertThat(conversation.maxClassification())
        .as("★★ 下调之后会话必须仍停在机密：这里如果变成 PUBLIC，就是一次静默降密")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  /**
   * 逐对遍历：任何 {@code (现值, 本轮)} 组合的结果都必须是 {@code max(现值, 本轮)}，且"真的变了"等价于 "本轮更高"。
   *
   * <p>★ 为什么值得写成一个 16 组的笛卡尔积：这里只有两条规则（只升 / 相等幂等），而它们很容易被一次"补一个特例"改坏 —— 例如为了"升密后重算"（{@code 13 §2.4}
   * 第 2 步）加一个"显式设置"的重载。逐对比对能把那种改动立刻暴露出来： 结果必须恒等于 max，返回值必须恒等于"本轮严格更高"。
   */
  @ParameterizedTest
  @EnumSource(ClassificationLevel.class)
  void resultIsAlwaysTheMaximumOfCurrentAndRefs(ClassificationLevel current) {
    for (ClassificationLevel refs : ClassificationLevel.values()) {
      Conversation conversation = newConversation();
      conversation.raiseClassification(current);

      boolean raised = conversation.raiseClassification(refs);

      assertThat(conversation.maxClassification())
          .as("现值 %s、本轮 %s ⇒ 结果必须是两者的 max", current, refs)
          .isEqualTo(current.max(refs));
      assertThat(raised)
          .as("现值 %s、本轮 %s ⇒ 只有本轮严格更高时才算'抬升了'", current, refs)
          .isEqualTo(refs.ordinal() > current.ordinal());
    }
  }

  // ────────────────────────── ③ 相等：幂等 ──────────────────────────

  /**
   * 重复引用同一档 ⇒ 值不变、返回 {@code false}（幂等）。
   *
   * <p>★ 相等这一档单独测的理由：它是"只升不降"与"必须真的变了才写库"两条规则的交叉点。 写成 {@code >=} 的话值仍然对
   * ——但每一次重复引用都会报"抬升了"，让审计与领域事件收到一串假信号。
   */
  @ParameterizedTest
  @EnumSource(ClassificationLevel.class)
  void raisingToTheSameLevelIsIdempotent(ClassificationLevel level) {
    Conversation conversation = newConversation();
    conversation.raiseClassification(level);

    assertThat(conversation.raiseClassification(level)).as("相等幂等：重复引用同一档 %s 不算抬升", level).isFalse();
    assertThat(conversation.maxClassification()).isEqualTo(level);
  }

  // ────────────────────────── ④ 无引用：null 是"不变"，不是"归零" ──────────────────────────

  /**
   * {@code null}（本轮一个引用都没有）⇒ 会话密级<strong>不变</strong>，且不抛异常。
   *
   * <p>★ 语义：空引用集合的 max 是<strong>单位元</strong>，不是 {@code PUBLIC}。若把 {@code null} 当成最低档， 那么"检索无命中 / 拒答
   * / 用户消息"这些<strong>正常路径</strong>都会变成一条降密路径。
   *
   * <p>★ 为什么不抛异常：无引用是正常路径，对它报错只会逼调用方在每一处先判一次 —— 而那正是"口径散到各读点"的开始。
   */
  @Test
  void noReferencesLeavesTheLevelUnchanged() {
    Conversation conversation = newConversation();
    conversation.raiseClassification(ClassificationLevel.SECRET);

    assertThat(conversation.raiseClassification(null))
        .as("本轮无引用 ⇒ 不算抬升（也不许抛异常：那是正常路径，不是非法输入）")
        .isFalse();
    assertThat(conversation.maxClassification())
        .as("★ null 是'不变'，不是'归零'：这里如果变成 PUBLIC，就是一条由无命中触发的降密路径")
        .isEqualTo(ClassificationLevel.SECRET);
  }

  // ────────────────────────── ⑤ 边界：归属不可变、改密只有一条路 ──────────────────────────

  /**
   * ★ <strong>闸门的"能红"读数</strong>：守卫不许退化成"永远拒绝"。
   *
   * <p>本类前面所有用例的对立面是一个<strong>看起来也全绿</strong>的实现 —— 把 {@link Conversation#raiseClassification}
   * 写成"永远返回 {@code false}、永不改值"：那样"只升不降"确实成立（降密被挡住了），但<strong>抬升也一起没了</strong>， 而症状是"会话密级永远停在
   * {@code PUBLIC}"（一次静默的<strong>该升不升</strong>）。
   *
   * <p>★ 所以这条断言是必须的：它同时钉住"抬升确实发生"与"比较方向确实用的是密级次序"，
   * 让上一条用例（拒绝下调）不至于被读成"守卫只是把一切都拒了"。两者<strong>必须一起绿</strong>，单独任何一条都说明不了问题。
   */
  @Test
  void raiseGuardIsNotJustRefusingEverything() {
    // 前提：最高档确实比最低档高（顺序即语义，见 ClassificationLevel 的类注释）。
    // ★ 若哪天有人把枚举顺序改乱，这里先红 —— 而不是让"抬升"在一个错的次序上悄悄工作。
    assertThat(ClassificationLevel.PUBLIC.max(ClassificationLevel.CONFIDENTIAL))
        .as("前提：CONFIDENTIAL 必须比 PUBLIC 高（声明顺序即密级高低）")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);

    Conversation conversation = newConversation();
    assertThat(conversation.raiseClassification(ClassificationLevel.CONFIDENTIAL))
        .as("★ 守卫不许退化成'永远拒绝'：从 PUBLIC 抬到最高档必须生效（否则'只升不降'这条读数说明不了任何事）")
        .isTrue();
  }

  /**
   * 会话密级<strong>没有第二个写入口</strong>。
   *
   * <p>★ 这条断言防的是"补一个 {@code setMaxClassification} / {@code recount} 之类的重载"：{@code 13 §2.4} 的升密处理链第
   * 2 步是"重算受影响会话的密级"，而"重算"的正确写法是<strong>再走一次抬升</strong> （引用集合的 max 只会更高，降密那一半由"引用快照保留密级历史"承担，{@code
   * 13 §2.4} 降密第 2 步）。 一旦出现一个能直接赋值的方法，上面所有"只升不降"的读数都可以被绕过 —— 而这一次的代价是<strong>静默的越权</strong>。
   *
   * <p>★ 判据落在<strong>公开方法集合</strong>上（不是"我读过源码"）：新增任何 setter / change 方法都会让这条红。
   */
  @Test
  void classificationHasExactlyOneWritePath() {
    assertThat(Conversation.class.getMethods())
        .as("★ 会话密级的唯一写入口是 raiseClassification：出现别的 setter / change 方法就等于开了一条能绕过'只升不降'的路")
        .filteredOn(method -> method.getName().toLowerCase().contains("classification"))
        .extracting(Method::getName)
        .containsExactlyInAnyOrder("raiseClassification", "maxClassification");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static Conversation newConversation() {
    return new Conversation(
        UUID.randomUUID(), UUID.fromString("11111111-2222-4333-8444-555555555555"), null, "");
  }
}
