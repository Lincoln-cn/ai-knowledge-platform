package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link Message#resume()} 的领域判据（{@code #371} 第 41 条 · {@code ChatMode.CONTINUE}）。
 *
 * <h3>★ 这个文件要挡住的是"看起来续写成功了、其实把前文弄丢了"</h3>
 *
 * <p>续写最容易出的两类错，都不报错： ① <b>把 content 当"重新生成"清空</b> —— 用户点"继续"，前半段回答消失； ② <b>把引用清空</b> ——
 * 后半段是同一批材料的延续，引用栏却空了。
 *
 * <p>所以这里的断言不是"调完之后状态是生成中"，而是<strong>逐项</strong>盯住"应当保留的东西还在" （正文 / 引用 / 赞踩 /
 * 用量）与"应当清掉的东西没了"（上一次的失败原因与耗时）。
 */
class MessageResumeTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0a000000-0000-4000-8000-000000000001");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("0b000000-0000-4000-8000-000000000002");

  /** 续写保住的四样东西：正文、引用、赞踩、用量。 */
  @Test
  void resumeKeepsWhatBelongsToTheSameAnswer() {
    Message message = stopped();
    message.bindReferences(List.of(ref()));
    message.rate(MessageFeedback.UP);
    message.addUsage(new TokenUsage(7, 11));

    message.resume();

    assertThat(message.status())
        .as("续写是从「已停止」重新回到生成中 —— 它是同一条消息的第二次生成")
        .isEqualTo(MessageStatus.GENERATING);
    assertThat(message.content()).as("★★ 正文必须保留：续写接着它往下写，清空就等于用户点「继续」把前半段删了").isEqualTo("前半段");
    assertThat(message.references())
        .as("★★ 引用必须保留：续写不重新检索，这一轮的来源还是那一批（bindReferences 的「只能绑一次」是领域兜底）")
        .hasSize(1);
    assertThat(message.feedback())
        .as("赞踩保留：续写没有换掉这段回答，用户对它的评价仍然成立（restart 清它是因为那条回答不存在了）")
        .isEqualTo(MessageFeedback.UP);
    assertThat(message.tokens())
        .as("用量保留：续写是新的一次调用，addUsage 本来就是累加语义")
        .isEqualTo(new TokenUsage(7, 11));
  }

  /** 续写清掉的是"上一次的结局"，否则它会与新的终态自相矛盾。 */
  @Test
  void resumeClearsThePreviousOutcomeOnly() {
    Message stopped = stopped();
    stopped.addUsage(new TokenUsage(3, 4));

    stopped.resume();

    assertThat(stopped.durationMs()).as("上一次的耗时说的是上一次的尝试：留着会让「这条回答花了多久」读成两次").isNull();
    assertThat(stopped.failReason()).as("上一次的失败原因同样不能留").isNull();
    assertThat(stopped.tokens()).as("反证：清的是结局，不是用量 —— 累计口径不受影响").isEqualTo(new TokenUsage(3, 4));
  }

  /** 只有「已停止」可以续写 —— 另外两个终态各有更合适的入口。 */
  @Test
  void onlyStoppedCanBeContinued() {
    Message generated = settledAs(MessageStatus.GENERATED);
    assertThatThrownBy(generated::resume)
        .as("★★ 已完整答完的回答不该能续写：那会变成让模型无话找话，且用户没有入口能停下来")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已停止");

    Message failed = settledAs(MessageStatus.FAILED);
    assertThatThrownBy(failed::resume)
        .as("★★ 失败的回答应当重新生成：接着错误提示往下写会得到一段自相矛盾的回答")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已停止");
  }

  /** 还在生成中时点「继续」会把两个生成线程压在同一行上。 */
  @Test
  void resumeRejectsAGeneratingMessage() {
    Message message = assistant();

    assertThatThrownBy(message::resume)
        .as("两个生成线程抢同一行会互相覆盖，而这在界面上只表现为「答案一半就停」")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("还在生成中");
  }

  /** 开场白不是模型生成的回答。 */
  @Test
  void resumeRejectsThePrologue() {
    Message prologue = Message.prologue(MESSAGE_ID, CONVERSATION_ID, "欢迎使用");

    assertThatThrownBy(prologue::resume)
        .as("开场白续写会把它变成模型输出 —— 而那是应用作者写的一句话")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("开场白");
  }

  /** 续写之后的增量接在原正文<strong>后面</strong>（不是覆盖）。 */
  @Test
  void textAppendedAfterResumeGoesToTheEnd() {
    Message message = stopped();

    message.resume();
    message.appendText("，接着写下去");

    assertThat(message.content()).as("★ 落库走的是「追加未落库的尾巴」，所以这里必须是拼接而不是覆盖").isEqualTo("前半段，接着写下去");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static Message assistant() {
    return new Message(MESSAGE_ID, CONVERSATION_ID, "");
  }

  /** 已经停止、正文是「前半段」的回答。 */
  private static Message stopped() {
    Message message = assistant();
    message.appendText("前半段");
    message.stop(10L);
    return message;
  }

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

  private static SourceRef ref() {
    return new SourceRef(
        1,
        "文档标题",
        "文档名",
        "知识库名",
        0.87,
        1,
        "这一段正文",
        "seg-1",
        "doc-1",
        "kb-1",
        ClassificationLevel.INTERNAL);
  }
}
