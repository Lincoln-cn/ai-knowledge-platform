package cn.lincoln.aiplatform.conversation.domain.stream;

import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.DURATION_MS;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.assembler;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.kinds;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.refs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ★★ <strong>判据②：恰好一次 {@code done}</strong> + <strong>判据④：三态终态</strong> （ROADMAP §4.7.3 第 17 周 ·
 * 四条终止路径）。
 *
 * <h3>为什么这两条必须一起测</h3>
 *
 * <p>"恰好一次"与"取哪个状态"是同一件事的两面：四条路径都通到 {@link StreamAssembler#finish}，
 * 而它们各自很容易出现的坏味是"在收尾之前自己先发了一条终态"（定时器那条路径尤其容易：超时回调直接 {@code
 * options.onDone(...)}）。那种实现下，四条路径单独看都是"有一次 done"， 只有把"路径 → 状态"逐条摆出来，才能看出谁发的那一条算数。
 *
 * <h3>★ 四条路径在真实链路上分别是谁触发的</h3>
 *
 * <ul>
 *   <li>{@link StreamTerminationPath#COMPLETE} —— 上游流正常结束（finish_reason = stop）
 *   <li>{@link StreamTerminationPath#ABORT} —— 用户点"停止"（显式接口，{@code 03 §1.5}）
 *   <li>{@link StreamTerminationPath#DISCONNECT} —— 输出流异常 / 半途断流（{@code 03 §1.5} 的兜底那条）
 *   <li>{@link StreamTerminationPath#IDLE_TIMEOUT} —— 定时器到期（首字 60s / 总时长上限，{@code 03 §1.6}）
 * </ul>
 *
 * <p>★ 本批<strong>不测</strong>"谁在什么时候调用 finish"（那需要线程与时钟，属第 17 周的传输层）。
 * 这里测的是：<strong>无论哪条路径进来，出口都只有一条、状态都只有三个取值之一</strong>。
 */
class StreamDoneTest {

  /** 四条路径：终态都必须有，且都排在最后一条。 */
  @ParameterizedTest
  @EnumSource(StreamTerminationPath.class)
  void everyPathProducesExactlyOneDoneAtTheEnd(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assertThat(assembler.finish(path, DURATION_MS)).as("路径 %s：第一次收尾必须真的产出 done", path).isTrue();

    List<EventKind> kinds = kinds(assembler.events());
    assertThat(kinds).as("路径 %s：done 恰好一条", path).containsOnlyOnce(EventKind.DONE);
    assertThat(kinds.get(kinds.size() - 1))
        .as("路径 %s：done 必须是最后一条事件", path)
        .isEqualTo(EventKind.DONE);
    assertThat(StreamFixture.doneCount(assembler.events()))
        .as("路径 %s：done 的条数", path)
        .isEqualTo(1L);
    assertThat(assembler.settled()).as("路径 %s：收尾后装配器必须处于已终态", path).isTrue();
  }

  /**
   * ★ 四条路径各自的状态映射（契约注释逐字）。
   *
   * <p>{@code COMPLETE → GENERATED} · {@code ABORT → STOPPED} · {@code DISCONNECT / IDLE_TIMEOUT →
   * FAILED}。
   */
  @ParameterizedTest
  @EnumSource(StreamTerminationPath.class)
  void everyPathMapsToTheContractStatus(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(path, DURATION_MS);

    assertThat(assembler.done().status())
        .as("路径 %s 的终态（到期值见 StreamTerminationPath#status）", path)
        .isEqualTo(path.status());
  }

  /**
   * ★★ <strong>"恰好一次"的核心读数：重复收尾返回 {@code false} 且不产出第二条 {@code done}</strong>。
   *
   * <p>★ 这是<strong>竞争</strong>的形状：用户点"停止"的同时连接也断了；或超时定时器与上游流结束同时到达。 处置刻意是"先到的那条算数，后到的返回
   * false"而不是抛异常 —— 抛异常会把一条完全正常的竞态变成 500， 而调用方本来就需要一个"是不是我收的尾"的返回值。
   */
  @Test
  void aSecondFinishIsIgnoredAndKeepsTheFirstStatus() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assertThat(assembler.finish(StreamTerminationPath.ABORT, DURATION_MS)).as("先到的是用户停止").isTrue();

    int eventsAfterFirstFinish = assembler.events().size();

    assertThat(assembler.finish(StreamTerminationPath.IDLE_TIMEOUT, 999L))
        .as("★ 后到的超时必须什么都不做（返回 false）")
        .isFalse();
    assertThat(assembler.events()).as("★ 第二条终态不许出现 —— 事件序列一条都不能多").hasSize(eventsAfterFirstFinish);
    assertThat(assembler.done().status())
        .as("★ 状态必须停在先到的那条路径上（STOPPED），而不是被后到的超时改成 FAILED")
        .isEqualTo(StreamStatus.STOPPED);
  }

  /** 失败两条路径：{@code error} 必须在 {@code done} <strong>之前</strong>，且 {@code done} 仍然要到。 */
  @ParameterizedTest
  @EnumSource(
      value = StreamTerminationPath.class,
      names = {"DISCONNECT", "IDLE_TIMEOUT"})
  void failurePathsEmitErrorBeforeDone(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(path, DURATION_MS);

    List<EventKind> kinds = kinds(assembler.events());
    assertThat(kinds)
        .as("路径 %s：出错仍要走 done 收尾（契约原文：error 不是终态，done 才是）", path)
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.ERROR, EventKind.DONE);
    assertThat(assembler.done().status()).isEqualTo(StreamStatus.FAILED);
  }

  /**
   * ★ 非失败路径<strong>不许</strong>夹一条 {@code error} —— 防"顺手给所有路径都发一条错误提示"。
   *
   * <p>被停止（{@code ABORT}）是<strong>用户意图</strong>，不是故障：{@code 03 §1.5} 专门说过
   * "显式停止表达的是用户意图，连接断开只是故障信号。两者要区分"。若停止也报 error，界面上会出现一条 "出错了，请重试"的横幅，而用户只是按了停止。
   */
  @ParameterizedTest
  @EnumSource(
      value = StreamTerminationPath.class,
      names = {"COMPLETE", "ABORT"})
  void nonFailurePathsNeverEmitError(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(path, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("路径 %s 不是故障 ⇒ 不许有 error 帧", path)
        .doesNotContain(EventKind.ERROR);
  }

  /**
   * {@code ABORT} 的终态是 {@code STOPPED} 而<strong>不是</strong> {@code GENERATED}（"被停了"与"生成完了"必须可分）。
   */
  @Test
  void abortIsDistinguishableFromComplete() {
    StreamAssembler aborted = assembler();
    aborted.begin();
    aborted.references(refs());
    aborted.finish(StreamTerminationPath.ABORT, DURATION_MS);

    StreamAssembler completed = assembler();
    completed.begin();
    completed.references(refs());
    completed.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(aborted.done().status()).isEqualTo(StreamStatus.STOPPED);
    assertThat(completed.done().status()).isEqualTo(StreamStatus.GENERATED);
    assertThat(aborted.done().status())
        .as("★ 前端靠这个区分'生成完了'与'被停了'（03 §1.1 的坑：停止后状态语义不清）")
        .isNotEqualTo(completed.done().status());
  }

  /** 还没收尾时 {@code done()} 是 {@code null}（"没有终态"这件事本身可被观测）。 */
  @Test
  void doneIsNullUntilTheStreamSettles() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assertThat(assembler.done()).isNull();
    assertThat(assembler.settled()).isFalse();
  }

  /** {@code durationMs} 为负 ⇒ 非法输入当场抛（"耗时"没有负数这个合法取值）。 */
  @Test
  void negativeDurationIsRefused() {
    StreamAssembler assembler = assembler();
    assertThatThrownBy(() -> assembler.finish(StreamTerminationPath.COMPLETE, -1L))
        .as("负数耗时只能是实现算错了")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("durationMs");
  }
}
