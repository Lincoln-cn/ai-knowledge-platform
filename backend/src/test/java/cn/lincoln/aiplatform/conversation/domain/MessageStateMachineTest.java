package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ★★ 消息的<strong>三态状态机</strong>（契约 {@code MessageStatus}）—— 纯领域单测，不起 Spring、不起容器。
 *
 * <p>契约：{@code GENERATING → GENERATED | STOPPED | FAILED}，★ <strong>终态不可再转</strong>。
 *
 * <h3>为什么这一条必须由领域层挡、且必须由单测钉住</h3>
 *
 * <p>把它交给调用方自觉（"记得先判断状态"）有三个不会报错的坏结局，它们的<strong>读数完全一样</strong>：
 *
 * <ul>
 *   <li>"已完成的答案又长出一截"（迟到的 delta 落到终态消息上）；
 *   <li>"已停止的消息被改成失败"（两条收尾路径竞态，后到的覆盖先到的）；
 *   <li>"用户消息也进了生成态"（界面上永远转圈，因为没有任何任务会去收尾它）。
 * </ul>
 *
 * <p>★ 库层表达不了这一条：{@code CHECK} 不能跨行、也不能比较新旧值 —— 所以要有一个<strong>不需要容器</strong> 的类来钉它。把它塞进需要
 * Testcontainers 的类里，会让"状态机被绕开"这件事<strong>只在容器起得来时才被检查</strong>， 而一个能因环境整类跳过的闸门等于没有闸门（与 {@code
 * ConversationClassificationTest} 同一条判断）。
 *
 * <p>★ 与它分工的另外两个类：累积 / 停止保留内容在 {@code MessageContentTest}； 库层的取值域与 CHECK 在 {@code
 * MessagePersistenceTest}。
 */
class MessageStateMachineTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0a000000-0000-4000-8000-000000000001");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("0b000000-0000-4000-8000-000000000002");

  // ────────────────────────── ① 四条合法转移各一条用例 ──────────────────────────

  /** 助手消息出生就是 {@code GENERATING} —— 状态机的起点（契约里没有 PENDING 这个取值）。 */
  @Test
  void assistantMessageStartsGenerating() {
    assertThat(assistant().status())
        .as("★ 助手消息的初值必须是 GENERATING：一行'已经开始生成、还没有第一个字'的消息就是'生成中'，不是未知态")
        .isEqualTo(MessageStatus.GENERATING);
  }

  /** 合法转移 ①：{@code GENERATING → GENERATED}（正常写完）。 */
  @Test
  void generatingToGeneratedIsLegal() {
    Message message = assistant();
    message.appendText("答");

    message.complete(new TokenUsage(10, 20), 1234L);

    assertThat(message.status())
        .as("GENERATING → GENERATED 是正常写完那条路径")
        .isEqualTo(MessageStatus.GENERATED);
    assertThat(message.content()).as("收尾不改正文").isEqualTo("答");
    assertThat(message.durationMs()).as("耗时在收尾那一刻定下来").isEqualTo(1234L);
  }

  /** 合法转移 ②：{@code GENERATING → STOPPED}（用户主动停止）。 */
  @Test
  void generatingToStoppedIsLegal() {
    Message message = assistant();
    message.appendText("半");

    message.stop(500L);

    assertThat(message.status())
        .as("GENERATING → STOPPED 是用户主动停止那条路径（03 §1.5 的显式停止）")
        .isEqualTo(MessageStatus.STOPPED);
    assertThat(message.durationMs()).isEqualTo(500L);
  }

  /** 合法转移 ③：{@code GENERATING → FAILED}（连接断开 / 闲置超时 / 上游报错）。 */
  @Test
  void generatingToFailedIsLegal() {
    Message message = assistant();
    message.appendText("半");

    message.fail("连接中断", "网络或服务异常，可重试", 900L);

    assertThat(message.status())
        .as("GENERATING → FAILED 是失败那条路径（断开 / 超时 / 上游报错都收在这里）")
        .isEqualTo(MessageStatus.FAILED);
    assertThat(message.failReason()).isEqualTo("连接中断");
    assertThat(message.failHint()).as("失败要给出'下一步'（契约 failHint）").isEqualTo("网络或服务异常，可重试");
  }

  /**
   * 合法转移 ④：用户消息出生即 {@code GENERATED}（{@code 02 §2.6} 的 MSG-I3）。
   *
   * <p>★ 为什么把"用户消息"也算一条转移：它不是没有状态，而是<strong>跳过了 GENERATING</strong> ——
   * 用户的提问不是"被生成"出来的，它在落库那一刻就已经完成。把它记成"生成中"会让界面永远转圈。
   */
  @Test
  void userMessageIsBornGenerated() {
    Message question = Message.user(UUID.randomUUID(), CONVERSATION_ID, "报销标准是多少？");

    assertThat(question.role()).isEqualTo(MessageRole.USER);
    assertThat(question.status())
        .as("★ MSG-I3：用户消息的状态只能是 GENERATED（它从来没有'生成中'这个阶段）")
        .isEqualTo(MessageStatus.GENERATED);
  }

  // ────────────────────────── ② 非法转移被拒 ──────────────────────────

  /**
   * ★★ <strong>终态不可再转</strong>：三个终态各自拒绝 {@code complete} / {@code stop} / {@code fail} 全部三种收尾。
   *
   * <p>★ 用 {@code @EnumSource} 遍历三个终态而不是写一条：只测"GENERATED 之后再 complete"的话， "GENERATED 之后
   * stop"与"STOPPED 之后再 stop"这两条路径就没有读数 —— 而它们各自对应一种真实的竞态 （用户点停止的同时模型正好写完 / 断开与超时同时到达）。
   */
  @ParameterizedTest
  @EnumSource(
      value = MessageStatus.class,
      names = {"GENERATED", "STOPPED", "FAILED"})
  void terminalStatesRejectEveryFurtherTransition(MessageStatus terminal) {
    Message message = settledAs(terminal);

    assertThatThrownBy(() -> message.complete(TokenUsage.zero(), 1L))
        .as("★ 终态 %s 之后再来一次 complete 必须被拒（否则'已完成的答案'会在库里被改写）", terminal)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("终态不可再转");
    assertThatThrownBy(() -> message.stop(1L))
        .as("★ 终态 %s 之后 stop 必须被拒（两条收尾路径竞态时，后到的那条不许覆盖先到的）", terminal)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("终态不可再转");
    assertThatThrownBy(() -> message.fail("迟到的失败", null, 1L))
        .as("★ 终态 %s 之后 fail 必须被拒（'已停止'不许被改写成'失败'——那是两种不同的界面）", terminal)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("终态不可再转");
  }

  /**
   * ★★ <strong>不能从 {@code GENERATED} 回到 {@code GENERATING}</strong> —— 迟到 delta 被挡。
   *
   * <p>这是"终态不可再转"最容易真实发生的一条：连接断开与模型流收尾是两条并发的路径， 收尾之后往往还会飘进来一两个
   * chunk。允许它们落下的话，<strong>库里那一段与用户看到的那一段就分叉了</strong> —— 而分叉的方向永远是"库里更长"（用户早就不看了）。
   */
  @Test
  void generatedStateRejectsLateChunks() {
    Message message = settledAs(MessageStatus.GENERATED);

    assertThatThrownBy(() -> message.appendText("迟到的一段"))
        .as("★ GENERATED 之后不许再追加文本 —— 这是'不能从 GENERATED 回到 GENERATING'那条不变量的可断言的形状")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("终态");
  }

  /**
   * ★ 用户消息一个生成动作都不接受（{@code role} 检查先于状态检查）。
   *
   * <p>★ 与"终态不可再转"分开断言：这两种错的<strong>修法不同</strong> —— 前者是"这条消息本来就不该进生成过程"（role 错），后者是"进晚了"（状态错）。
   * 混在一个用例里，红了也看不出该改哪一边。
   */
  @Test
  void userMessageRejectsEveryGenerationAction() {
    Message question = Message.user(UUID.randomUUID(), CONVERSATION_ID, "在吗");

    assertThatThrownBy(() -> question.appendText("不该有"))
        .as("MSG-I3 / MSG-I4：用户消息不参与生成，也不带引用")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只对助手消息合法");
    assertThatThrownBy(() -> question.complete(TokenUsage.zero(), 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只对助手消息合法");
    assertThatThrownBy(() -> question.stop(1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只对助手消息合法");
    assertThatThrownBy(() -> question.fail("不可能", null, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只对助手消息合法");
  }

  /**
   * ★ 失败<strong>必须给得出原因</strong>（输入非法 ⇒ {@code IllegalArgumentException}，不是状态错）。
   *
   * <p>★ 两类异常在这里分得很清：<b>状态不对</b>抛 {@code IllegalStateException}（上一条用例）， <b>参数不对</b>抛 {@code
   * IllegalArgumentException}。混成一种，调用方就只能"全部当成坏请求"处理 —— 而"我的状态机拒绝了我"与"我的输入不合法"需要完全不同的处置。
   */
  @Test
  void failureWithoutReasonIsRejectedAsIllegalInput() {
    Message message = assistant();

    assertThatThrownBy(() -> message.fail("  ", null, 1L))
        .as("一条没有原因的失败在界面上只有一个空白提示，而'为什么失败'是用户唯一能据此行动的信息")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(message.status()).as("拒绝之后状态不动（不是'失败了但没记原因'）").isEqualTo(MessageStatus.GENERATING);
  }

  /** ★ 负耗时与"一条消息收尾两次"都被挡：耗时是"从开始生成到收尾"的一个量。 */
  @Test
  void negativeDurationIsRejected() {
    assertThatThrownBy(() -> assistant().complete(TokenUsage.zero(), -1L))
        .as("负耗时只能来自'减了一下'的实现")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ────────────────────────── ③ "被拒绝" = "什么都没改" ──────────────────────────

  /**
   * ★★ <strong>"被拒绝"必须意味着"什么都没改"</strong>（半改状态比"抛得晚一点"更坏）。
   *
   * <p>★ 这一条是<strong>金丝雀逼出来的</strong>：把"终态不可再转"那道闸临时关掉之后，第二次 {@code complete} 撞在耗时的前置检查上抛了出来 ——
   * 而那一刻 {@code addUsage} <strong>已经生效</strong>， 于是对象停在"用量加了、状态还是生成中"的半改状态。调用方看到异常以为这次转移被拒了，而对象其实变了
   * —— 那正是本项目最贵的"看着没生效、其实生效了一半"。
   *
   * <p>⇒ 三个终态入口的顺序必须是"<strong>先全部检查、再一起改</strong>"。本用例钉的就是这个顺序。
   */
  @Test
  void rejectedTransitionLeavesNoHalfChangedState() {
    Message message = settledAs(MessageStatus.GENERATED);
    Long durationBefore = message.durationMs();
    TokenUsage tokensBefore = message.tokens();

    assertThatThrownBy(() -> message.complete(new TokenUsage(100, 100), 999L))
        .as("前提：终态之后 complete 被拒（见 terminalStatesRejectEveryFurtherTransition）")
        .isInstanceOf(IllegalStateException.class);

    assertThat(message.durationMs()).as("★ 被拒之后耗时不许变").isEqualTo(durationBefore);
    assertThat(message.tokens())
        .as("★★ 被拒之后用量不许变 —— addUsage 会改状态，它必须排在所有检查之后")
        .isEqualTo(tokensBefore);
    assertThat(message.status()).as("被拒之后状态当然不动").isEqualTo(MessageStatus.GENERATED);
  }

  /**
   * ★ 反方向：<strong>真的发生了</strong>的收尾当然要改状态（免得上面那条把"什么都不做"也判成对）。
   *
   * <p>★ 没有这一条，"把三个终态入口全改成空方法"也能让上面那条绿。
   */
  @Test
  void acceptedTransitionDoesChangeTheState() {
    Message message = assistant();
    message.appendText("答");

    message.complete(new TokenUsage(5, 6), 77L);

    assertThat(message.status()).isEqualTo(MessageStatus.GENERATED);
    assertThat(message.durationMs()).isEqualTo(77L);
    assertThat(message.tokens()).isEqualTo(new TokenUsage(5, 6));
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static Message assistant() {
    return new Message(MESSAGE_ID, CONVERSATION_ID, "");
  }

  /** 造一条已经收尾到指定终态的消息（走的是生产路径，不碰私有字段）。 */
  private static Message settledAs(MessageStatus terminal) {
    Message message = assistant();
    message.appendText("已生成的一段内容");
    switch (terminal) {
      case GENERATED -> message.complete(new TokenUsage(1, 2), 10L);
      case STOPPED -> message.stop(10L);
      case FAILED -> message.fail("原始失败原因", null, 10L);
      default -> throw new IllegalArgumentException("本夹具只造终态，收到：" + terminal);
    }
    return message;
  }
}
