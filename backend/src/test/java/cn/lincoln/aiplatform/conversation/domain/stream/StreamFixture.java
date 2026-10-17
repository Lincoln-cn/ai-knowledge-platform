package cn.lincoln.aiplatform.conversation.domain.stream;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：把"从假模型喂 chunk 进装配器"这件事写成一处（五个测试类共用）。
 *
 * <p>★ 夹具只做搬运，<strong>不做断言</strong>：断言留在各测试类里，否则"哪个形状坏了"要看夹具才知道。
 */
final class StreamFixture {

  /** 固定的消息 id（断言 done 挂在哪条消息上）。 */
  static final String MESSAGE_ID = "msg-0001";

  static final String TRACE_ID = "trace-0001";

  static final String MODEL = "qwen2.5:7b";

  /** 一次"正常"的生成耗时（毫秒）。 */
  static final long DURATION_MS = 4210L;

  private StreamFixture() {}

  /** 新建一个还没发 meta 的装配器（`meta` 与 `messageId` 同值）。 */
  static StreamAssembler assembler() {
    return new StreamAssembler(MESSAGE_ID, new StreamMeta(MESSAGE_ID, TRACE_ID, MODEL));
  }

  /**
   * 两条引用（契约里 {@code index} 从 1 连续递增）。
   *
   * <p>★ 只填契约里<strong>有的</strong>字段：{@code 10 §8.2} 提过的 {@code subQuestion} / {@code round} /
   * {@code matchedQuery} <strong>不在</strong>冻结契约里，所以这里也不填（详见 {@code SourceRef} 的类注释）。
   *
   * <p>★ 密级（第 11 个分量，{@code #27}）给两档不同的值：这样"装配器只管搬运、不改引用内容"这条断言 在密级上也成立，且下游（会话累计密级）拿到的 max
   * 是这两档里更高的那个。
   */
  static List<SourceRef> refs() {
    return List.of(
        new SourceRef(
            1,
            "分段一",
            "文档一",
            "知识库",
            0.83,
            1,
            null,
            "seg-1",
            "doc-1",
            "kb-1",
            ClassificationLevel.INTERNAL),
        new SourceRef(
            2,
            "分段二",
            "文档二",
            "知识库",
            0.71,
            2,
            null,
            "seg-2",
            "doc-2",
            "kb-1",
            ClassificationLevel.SECRET));
  }

  /**
   * 按顺序把假模型的 chunk 喂进装配器，返回<strong>每个 chunk 之后</strong>的已产出事件数。
   *
   * <p>★ 返回值是"形状断言"的一部分：它证明装配器是<strong>边收边发</strong>的（每来一段文本就多一条 {@code delta}）， 而不是把整个流吞完再一次性吐出来
   * —— 后者同样是"refs 先于 delta"，但用户看到的是"转圈 5 秒然后整段答案出现"。
   *
   * @param assembler 装配器（调用前应当已经 {@code begin} 过）
   * @param chunks 假模型输出流
   * @return 每个 chunk 处理完之后的事件数
   */
  static List<Integer> drain(StreamAssembler assembler, List<ModelOutputChunk> chunks) {
    List<Integer> countsAfterEachChunk = new ArrayList<>();
    for (ModelOutputChunk chunk : chunks) {
      emit(assembler, chunk);
      countsAfterEachChunk.add(assembler.events().size());
    }
    return List.copyOf(countsAfterEachChunk);
  }

  /** 把一个 chunk 喂进装配器（{@link #drain} 的内部步骤）。 */
  private static void emit(StreamAssembler assembler, ModelOutputChunk chunk) {
    switch (chunk) {
      case ModelOutputChunk.Partial partial -> assembler.delta(partial.text());
      case ModelOutputChunk.Usage usage -> assembler.addUsage(usage.usage());
    }
  }

  /** 事件序列的类型投影 —— 「形状」断言都看这个，不看 payload。 */
  static List<EventKind> kinds(List<StreamEvent> events) {
    return events.stream().map(StreamEvent::kind).toList();
  }

  /** 取出事件序列里的全部 {@code delta} 文本。 */
  static List<String> deltaTexts(List<StreamEvent> events) {
    return events.stream()
        .filter(StreamEvent.Delta.class::isInstance)
        .map(event -> ((StreamEvent.Delta) event).text())
        .toList();
  }

  /** 第一条 {@code delta} 在序列里的下标（没有 delta 时返回 {@code -1}）。 */
  static int firstDeltaIndex(List<StreamEvent> events) {
    List<EventKind> kinds = kinds(events);
    return kinds.indexOf(EventKind.DELTA);
  }

  /** 唯一一条 {@code refs} 在序列里的下标（没有 refs 时返回 {@code -1}）。 */
  static int refsIndex(List<StreamEvent> events) {
    List<EventKind> kinds = kinds(events);
    return kinds.indexOf(EventKind.REFS);
  }

  /** 事件序列里的 {@code done} 条数。 */
  static long doneCount(List<StreamEvent> events) {
    return events.stream().filter(StreamEvent.StreamDoneEvent.class::isInstance).count();
  }
}
