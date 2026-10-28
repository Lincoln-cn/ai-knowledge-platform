package cn.lincoln.aiplatform.conversation.domain.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 失败文案的唯一算法（{@code #224}）—— 线上那句「连接中断」与库里那句「生成失败：上游返回 HTTP 404…」必须同源。
 *
 * <h3>它钉的是什么</h3>
 *
 * <p>改之前：{@code StreamAssembler} 发常量「连接中断」，{@code ChatService.failReasonFor} 在有上游 detail 时落库
 * 「生成失败：…」 ⇒ <strong>同一个失败，用户看到的原因与运维查到原因不是一句话</strong>。现场证据是一台把 {@code baseModel}
 * 配错成桩里没有的模型的机器：库里写着「模型不存在」，界面上只有「连接中断」，用户唯一能做的动作是刷新重试。
 *
 * <p>现在两边都走 {@link StreamAssembler#failureText}，于是本类断的是<strong>这个函数的三档语义</strong>，
 * 以及"装配器发出去的那一帧确实是它算的"。
 *
 * <h3>★ 每条判据都能红</h3>
 *
 * <ul>
 *   <li>{@link #upstreamFailurePutsTheActionableReasonOnTheWire()} —— 把 {@code failureText} 里
 *       detail 那一段 改回常量，本用例红在「只有连接中断」；
 *   <li>{@link #userStopWithoutDetailStaysConnectInterrupted()} —— 反过来，把 detail 的判断去掉（无 detail
 *       也拼「生成失败：null」）， 本用例红；这一条是防"修 A3 把停止语义弄脏"；
 *   <li>{@link #finishEmitsWhatTheAlgorithmSays()} —— 把 {@code finish} 里的 {@code failureText}
 *       调用换回常量， 本用例红在「帧里的 reason 与函数返回值不一致」。
 * </ul>
 */
class StreamAssemblerFailureTextTest {

  private static final String UPSTREAM_404 = "上游返回 HTTP 404：模型 qwen2.5:1.5b 不存在";

  @Test
  @DisplayName("成功 / 用户停止（ABORT）没有失败文案")
  void successPathsCarryNoFailureText() {
    for (StreamTerminationPath path :
        List.of(StreamTerminationPath.COMPLETE, StreamTerminationPath.ABORT)) {
      StreamAssembler.FailureText text = StreamAssembler.failureText(path, null);

      assertThat(text.persistable()).as("%s 不该发 error 帧，也不该落失败原因", path).isFalse();
      assertThat(text.reason()).as("%s 的 reason 必须是 null（库层不许出现「成功但有原因」）", path).isNull();
      assertThat(text.hint()).as("%s 的 hint 也应该是 null", path).isNull();
    }
  }

  @Test
  @DisplayName("用户停止 / 写连接失败：仍然是「连接中断」，不带任何上游话术")
  void userStopWithoutDetailStaysConnectInterrupted() {
    StreamAssembler.FailureText text =
        StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, null);

    assertThat(text.reason()).as("★ 用户点停止时，原因就该是「连接中断」—— 上游的内部话术与他做的动作无关").isEqualTo("连接中断");
    assertThat(text.hint()).as("下一步是重试").isEqualTo("网络或服务异常，可重试");
  }

  @Test
  @DisplayName("空白 detail 视同没有 detail（不能被拼成「生成失败： 」）")
  void blankDetailIsTreatedAsNoDetail() {
    for (String blank : new String[] {"", "   ", "\n\n", "\t "}) {
      assertThat(StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, blank).reason())
          .as("detail=[%s] 应回落成「连接中断」", blank)
          .isEqualTo("连接中断");
    }
  }

  @Test
  @DisplayName("★ 上游失败：reason 带上可行动的原因，hint 指向去改配置")
  void upstreamFailurePutsTheActionableReasonOnTheWire() {
    StreamAssembler.FailureText text =
        StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, UPSTREAM_404);

    assertThat(text.reason())
        .as("★★ 必须带上上游那句原因（否则用户只看到「连接中断」，能做的只有刷新）")
        .isEqualTo("生成失败：" + UPSTREAM_404)
        .contains("HTTP 404")
        .isNotEqualTo("连接中断");
    assertThat(text.hint()).as("★ 下一步不是「重试」，是「去改模型配置」—— 网络口径的 hint 在这里会指错方向").contains("对话模型配置");
  }

  @Test
  @DisplayName("过长的上游 detail 被截断成一句话（不把整页 JSON 推给用户）")
  void longUpstreamDetailIsTruncatedToOneLine() {
    String noisy = "上游返回 HTTP 500：" + "x".repeat(400) + "\n{\"error\":\"boom\"}";

    String reason = StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, noisy).reason();

    assertThat(reason).as("换行必须被压掉 —— 一句话的原因不能变成多行").doesNotContain("\n");
    assertThat(reason.length())
        .as("总长 = 前缀 5 字 + 截断 160 字 + 省略号 1 字；超了说明没截")
        .isLessThanOrEqualTo("生成失败：".length() + 160 + 1);
    assertThat(reason).as("截断之后仍要能看出发生了什么").startsWith("生成失败：上游返回 HTTP 500：xxx");
    assertThat(reason).as("被截断时要留省略号，读的人才知道后面还有").endsWith("…");
  }

  @Test
  @DisplayName("闲置超时是第三档：既不是连接中断，也不是上游失败")
  void idleTimeoutKeepsItsOwnWording() {
    StreamAssembler.FailureText text =
        StreamAssembler.failureText(StreamTerminationPath.IDLE_TIMEOUT, UPSTREAM_404);

    assertThat(text.reason()).as("超时优先 —— 路径本身就是超时，不该被 detail 覆盖").isEqualTo("生成超时");
    assertThat(text.hint()).isEqualTo("可稍后重试；若持续超时请联系管理员");
  }

  @Test
  @DisplayName("★★ 装配器发出去的那一帧，就是算法算出来的那一份（同源）")
  void finishEmitsWhatTheAlgorithmSays() {
    StreamAssembler assembler = new StreamAssembler("message-1", null);

    assertThat(assembler.finish(StreamTerminationPath.DISCONNECT, 42, UPSTREAM_404)).isTrue();

    StreamEvent.StreamErrorEvent error = firstError(assembler.events());
    assertThat(error.reason())
        .as("★ 帧里的 reason 必须等于 failureText 的返回值（两处各写一份就会在这里分叉）")
        .isEqualTo(
            StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, UPSTREAM_404).reason());
    assertThat(error.hint())
        .isEqualTo(
            StreamAssembler.failureText(StreamTerminationPath.DISCONNECT, UPSTREAM_404).hint());
    assertThat(assembler.done()).as("error 之后仍然要有 done（契约：error 不是终态）").isNotNull();
  }

  @Test
  @DisplayName("不带上游原因的那个重载仍然只有「连接中断」（用户停止那条路不许被弄脏）")
  void finishWithoutDetailKeepsTheStopSemantics() {
    StreamAssembler assembler = new StreamAssembler("message-1", null);

    assembler.finish(StreamTerminationPath.DISCONNECT, 7);

    StreamEvent.StreamErrorEvent error = firstError(assembler.events());
    assertThat(error.reason()).as("★ 这是 #224 判据 4 的回归位：修可读原因不许改掉「停止」的语义").isEqualTo("连接中断");
    assertThat(error.hint()).isEqualTo("网络或服务异常，可重试");
  }

  private static StreamEvent.StreamErrorEvent firstError(List<StreamEvent> events) {
    return events.stream()
        .filter(StreamEvent.StreamErrorEvent.class::isInstance)
        .map(StreamEvent.StreamErrorEvent.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有产出 error 帧：" + events));
  }
}
