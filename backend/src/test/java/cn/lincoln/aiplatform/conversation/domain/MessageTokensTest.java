package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ★★ 消息上的 <strong>{@code tokens}</strong>：口径是 {@code 10 §8.4} 的"该次问答<strong>全部</strong>模型调用之和"，
 * 值对象是 {@link TokenUsage}（纯领域单测，不起 Spring、不起容器）。
 *
 * <p>契约那一侧是 {@code done.tokens = {prompt, completion, total}}，{@code ChatMessage} 那一侧只有 {@code
 * tokens: number}（契约把两个分量折叠成了一个数）—— ★ 本批按 <strong>{@code 02 §5.2} 的 {@code prompt_tokens} / {@code
 * completion_tokens} 两列</strong>落库，所以"总和"是<strong>读写时导出</strong>的， 而不是存下来的第三个分量。允许它独立存下来就等于允许
 * {@code total != prompt + completion}， 而那种不一致<strong>没有任何一处会报错</strong>。
 *
 * <h3>★ 为什么"没有调用"必须是 {@code null} 而不是 0</h3>
 *
 * <p>把"没调用"写成 0 会让成本统计<strong>偏低</strong>，而且偏低幅度随策略开关变化 （多跳开一次就多一次调用），那是最难查的一类账。所以本类专门有一条用例钉这个区别。
 *
 * <h3>★ 为什么"失败路径上已发生的调用仍计入"单独一条</h3>
 *
 * <p>{@code 10 §8.4} 的口径是"全部模型调用"，而失败路径上用户已经花掉的钱一样要记账 —— 只在成功路径上汇总会让"失败率高的策略"看起来更便宜，方向正好反了。
 */
class MessageTokensTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0e000000-0000-4000-8000-000000000005");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("0f000000-0000-4000-8000-000000000006");

  // ────────────────────────── ① total = prompt + completion ──────────────────────────

  /**
   * ★ {@code total = prompt + completion} —— 由 {@link TokenUsage#total()}
   * <strong>导出</strong>，不是第三个字段。
   *
   * <p>★ 用 {@code @CsvSource} 覆盖"两边都有 / 一边为 0"：只测 {@code (10, 20)} 的话， "把某个分量写死成 0"的实现照样绿。
   */
  @ParameterizedTest
  @CsvSource({"10, 20, 30", "0, 7, 7", "7, 0, 7", "0, 0, 0"})
  void totalIsDerivedFromTheTwoComponents(int prompt, int completion, int expectedTotal) {
    Message message = assistant();

    message.complete(new TokenUsage(prompt, completion), 1L);

    assertThat(message.tokens()).as("收尾之后必须读得到用量").isNotNull();
    assertThat(message.tokens().prompt()).isEqualTo(prompt);
    assertThat(message.tokens().completion()).isEqualTo(completion);
    assertThat(message.tokens().total())
        .as("★ total 必须由 prompt + completion 导出（独立取值等于允许两者不一致，而那不会报错）")
        .isEqualTo(expectedTotal);
  }

  /**
   * ★ "还没有发生模型调用" ⇒ {@code null}，<strong>不是</strong> {@code new TokenUsage(0, 0)}。
   *
   * <p>★ 这个区别是成本账的起点：{@code null} = 这一步没调用，{@code 0} = 调用了、用量为 0。 把两者糊成一个值会让统计偏低，偏低幅度随策略开关变化。
   */
  @Test
  void noCallAtAllIsNullNotZero() {
    Message message = assistant();

    assertThat(message.tokens()).as("★ 一条还没有任何模型调用的消息，其用量必须是 null（'没调用'与'用量为 0'是两个不同的答案）").isNull();
  }

  /** ★ {@code addUsage(null)} 是<strong>无操作</strong>（这一路没有报用量），不是"加 0 后归零"。 */
  @Test
  void addingNoUsageIsANoOp() {
    Message message = assistant();
    message.addUsage(new TokenUsage(4, 5));

    message.addUsage(null);

    assertThat(message.tokens().total()).as("null 用量不许把已累计的值清掉").isEqualTo(9);
  }

  // ────────────────────────── ② 多次调用求和（10 §8.4） ──────────────────────────

  /**
   * ★★ <strong>逐次 {@code addUsage} 与收尾时一次给出，必须得到同一个总数</strong>。
   *
   * <p>★ 这一条钉的是"合并"这件事本身：查询理解、多跳判定、生成是三次调用，调用方可能逐次报、 也可能在收尾时一次报。两种写法若得到不同的数，就说明有一处把"加上去"写成了"覆盖" ——
   * 而 {@link TokenUsage#plus} 存在的理由正是让这件事只有一个实现。
   */
  @Test
  void repeatedAdditionsAndOneFinalSumAgree() {
    Message incremental = assistant();
    incremental.addUsage(new TokenUsage(100, 0));
    incremental.addUsage(new TokenUsage(20, 5));
    incremental.complete(new TokenUsage(0, 300), 10L);

    Message single = assistant();
    single.complete(new TokenUsage(120, 305), 10L);

    assertThat(incremental.tokens())
        .as("★ 逐次报用量与一次报总和必须得到同一个值（'加上去'写成'覆盖'的实现会在这里露出）")
        .isEqualTo(single.tokens());
    assertThat(incremental.tokens().total()).as("全部调用之和").isEqualTo(425);
  }

  /**
   * ★★ <strong>失败路径上已发生的调用仍计入</strong>。
   *
   * <p>★ 场景就是真实的：查询理解与多跳判定已经花掉了 token，生成那一步失败了。 若不记账，失败率高的策略会显得更便宜。
   */
  @Test
  void usageRecordedBeforeFailureStillCounts() {
    Message message = assistant();
    message.addUsage(new TokenUsage(80, 12));
    message.addUsage(new TokenUsage(30, 4));

    message.fail("生成超时", "可稍后重试", 60000L);

    assertThat(message.status()).isEqualTo(MessageStatus.FAILED);
    assertThat(message.tokens()).as("★ 失败路径上已发生的调用必须仍计入（否则失败率高的策略看起来更便宜）").isNotNull();
    assertThat(message.tokens().prompt()).as("两次调用都发生在失败之前").isEqualTo(110);
    assertThat(message.tokens().completion()).isEqualTo(16);
    assertThat(message.tokens().total()).isEqualTo(126);
    assertThat(message.tokens().total())
        .as("total = prompt + completion 这条在失败路径上同样成立")
        .isEqualTo(message.tokens().prompt() + message.tokens().completion());
  }

  /**
   * ★ 停止路径上也一样：停止不是"这笔调用没发生过"。
   *
   * <p>★ 与失败分开断言，因为"停止"往往被当成"用户不想要这个答案了，那这次的账也不算了吧" —— 而模型调用已经真实发生、供应商那边已经计费了。
   */
  @Test
  void usageRecordedBeforeStopStillCounts() {
    Message message = assistant();
    message.addUsage(new TokenUsage(50, 20));

    message.stop(1500L);

    assertThat(message.tokens().total()).as("★ 停止不退还已经花掉的 token").isEqualTo(70);
  }

  /** ★ 终态之后仍允许补记用量（成本账的收口往往发生在 {@code fail} 之后）。 */
  @Test
  void usageCanStillBeAddedAfterFailure() {
    Message message = assistant();
    message.fail("上游 502", null, 100L);

    message.addUsage(new TokenUsage(9, 1));

    assertThat(message.tokens().total())
        .as("★ 失败之后补记用量是合法的：报告那一刻往往就在 fail 之后，而漏记一笔的账查不回来")
        .isEqualTo(10);
  }

  // ────────────────────────── ③ 值对象自己的口径 ──────────────────────────

  /** ★ {@link TokenUsage} 拒绝负分量（token 是只增的计数器）。 */
  @Test
  void negativeComponentsAreRejectedByTheValueObject() {
    assertThatThrownBy(() -> new TokenUsage(-1, 0))
        .as("负数只能来自'减了一下'的实现")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static Message assistant() {
    return new Message(MESSAGE_ID, CONVERSATION_ID, "");
  }
}
