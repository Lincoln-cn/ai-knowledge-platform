package cn.lincoln.aiplatform.conversation.domain.stream;

import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.DURATION_MS;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.assembler;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.deltaTexts;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.drain;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.kinds;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.refs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 事件序列的<strong>整体形状</strong>：一次正常问答从 {@code meta} 到 {@code done} 的逐条读数。
 *
 * <p>契约顺序（{@code 10 §8.3} 与 {@code 03 §1.2} 同口径）：
 *
 * <pre>meta → refs → delta* → artifact → done</pre>
 *
 * <p>本批<strong>不产出 {@code artifact}</strong>（suggestions 属 V1.1，{@code 03 §1.2} 表里标注"生成过程中或结束时"），
 * 所以正常路径的读数里没有它 —— 这条"没做"写在这里，而不是留给读者从缺口里猜。
 *
 * <h3>★ 为什么形状要写成"整条序列逐项相等"</h3>
 *
 * <p>逐条断言（{@code refs 在 delta 前} · {@code done 只有一条} · …）各自只证明自己那一条。
 * 而"多出一条谁都没想到的事件"这种坏味，只有<strong>整条序列比对</strong>能抓到 —— 前端 {@code emit} 的 {@code default: break}
 * 会安静地把它吃掉，页面不会有任何异常。
 *
 * <h3>★ 三个退化情形（形状不许塌）</h3>
 *
 * <p>"没有引用" / "没有 delta" 都不是异常：零命中的答话、以及"检索完就失败"都是真实路径。 它们共同的要求是：<strong>顺序还在</strong>（{@code refs}
 * 仍然先于 {@code done}，{@code done} 仍然只有一条）。
 */
class StreamAssemblerSequenceTest {

  /**
   * ★ <strong>金丝雀靶子</strong>：一次正常问答的完整事件序列。
   *
   * <p>把 {@code StreamAssembler#flushPendingBeforeFirstDelta} 里的缓冲拿掉（改成 delta 直接 emit）， 本用例必须变红 ——
   * 这是"顺序保证真的在起作用"的唯一证据（"修闸门"与"把闸门改绿"读数一模一样， 唯一的区别是<strong>被测对象坏掉时它会不会红</strong>）。
   */
  @Test
  void normalAnswerProducesTheExactContractSequence() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    drain(assembler, ModelOutputChunk.generated(new String[] {"根据", "文档规定", "……"}, null));
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("★ 正常路径的完整形状：meta → refs → delta* → done（artifact 本批不产出）")
        .containsExactly(
            EventKind.META,
            EventKind.REFS,
            EventKind.DELTA,
            EventKind.DELTA,
            EventKind.DELTA,
            EventKind.DONE);
  }

  /**
   * 逐条读数（与上一条同一次装配，但把每条事件的<b>内容</b>也钉住）。
   *
   * <p>★ 与上一条分开写是刻意的：形状（类型序列）与内容（每条的 payload）是两件会各自坏掉的事， 混在一个断言里失败信息只能告诉你"序列不对"。
   */
  @Test
  void eachEventCarriesThePayloadOfItsSource() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    drain(assembler, ModelOutputChunk.generated(new String[] {"根据", "文档规定", "……"}, null));
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    List<StreamEvent> events = assembler.events();
    assertThat(((StreamEvent.StreamMetaEvent) events.get(0)))
        .as("meta 带 messageId / traceId / model（契约 StreamMeta）")
        .isEqualTo(
            new StreamEvent.StreamMetaEvent(
                StreamFixture.MESSAGE_ID, StreamFixture.TRACE_ID, StreamFixture.MODEL));
    assertThat(((StreamEvent.References) events.get(1)).refs())
        .as("refs 带的就是检索结果本身，且顺序不变")
        .isEqualTo(refs());
    assertThat(deltaTexts(events))
        .as("每个文本块各一条 delta，顺序与假模型一致")
        .containsExactly("根据", "文档规定", "……");
    assertThat(((StreamEvent.StreamDoneEvent) events.get(5)).status())
        .as("正常写完的终态")
        .isEqualTo(StreamStatus.GENERATED);
  }

  /** 退化①：<strong>零命中也走完整形状</strong> —— 一条空的 {@code refs} 仍然排在 {@code done} 之前。 */
  @Test
  void zeroHitAnswerKeepsTheWholeShape() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(List.of());
    assembler.delta("知识库里没有找到相关内容。");
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("零命中 ⇒ refs 是空表，但这一条事件必须在（它是'检索过了'的证据）")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA, EventKind.DONE);
  }

  /** 退化②：<strong>一个字都没生成</strong>（模型直接结束）⇒ 形状仍是 {@code refs → done}，且状态是 {@code GENERATED}。 */
  @Test
  void answerWithNoDeltasStillTerminatesCleanly() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("没有 delta 不等于失败 —— refs 仍然在 done 之前")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DONE);
    assertThat(assembler.text()).as("正文是空串，不是 null").isEmpty();
  }

  /** 退化③：<strong>一个字都没生成就没引用</strong>（检索还没回来就收尾）⇒ 补一条空 refs，形状不塌。 */
  @Test
  void terminationWithoutAnyRefsStillEmitsAnEmptyRefsSlot() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("一次问答天然带着'检索了什么'这个答案：没人给过就补空表，而不是让 refs 槽位整个消失")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DONE);
  }

  /** 用量块 <strong>不进事件序列</strong>：它汇总进 {@code done.tokens}（{@code 10 §8.4}）。 */
  @Test
  void usageChunksDoNotBecomeEvents() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    drain(assembler, ModelOutputChunk.generated(new String[] {"根据"}, new TokenUsage(100, 20)));
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(kinds(assembler.events()))
        .as("用量不是 wire 上的事件 —— 多一条 {kind: USAGE} 的话前端会安静地把它吃掉（default: break）")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA, EventKind.DONE);
    assertThat(assembler.tokens()).as("但它的读数必须进 done.tokens").isEqualTo(new TokenUsage(100, 20));
  }

  /**
   * 收尾之后装配器<strong>关门</strong>：再来 chunk 直接抛（半途断流的 worker 循环必须靠 {@link StreamAssembler#settled()}
   * 退出）。
   */
  @Test
  void assemblerRefusesInputAfterDone() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(StreamTerminationPath.ABORT, DURATION_MS);

    assertThatThrownBy(() -> assembler.delta("停止之后还来的文本"))
        .as("done 之后不许再产出任何事件 —— 一条流里 done 是最后一条")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("done 之后");
  }

  /** 读出去的事件列表是<strong>快照</strong>：调用方改不动装配器内部的状态。 */
  @Test
  void eventsSnapshotCannotBeMutated() {
    StreamAssembler assembler = assembler();
    assembler.begin();

    assertThatThrownBy(() -> assembler.events().add(new StreamEvent.Heartbeat("x")))
        .as("events() 必须给不可变快照 —— 否则调用方能悄悄改写'已经发出去了什么'这份账")
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
