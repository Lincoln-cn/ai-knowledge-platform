package cn.lincoln.aiplatform.conversation.domain.stream;

import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.MESSAGE_ID;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.assembler;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.deltaTexts;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.drain;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.firstDeltaIndex;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.kinds;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.refs;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.refsIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ★★ <strong>判据①：{@code refs} 先于第一个 {@code delta}</strong>（ROADMAP §4.7.3 第 17 周 · 首元素缓冲）。
 *
 * <h3>这条形状坏掉时用户看到什么</h3>
 *
 * <p>检索与生成是<strong>两条并发的流</strong>。很自然的写法是"chunk 到了就先发 {@code delta}"，于是： 正文里先出现 {@code
 * [1]}、而引用栏要等检索回来才出现 —— 用户在这段时间里点不动引用，等引用栏终于出来时
 * 正文已经渲染了一半。更糟的是当检索<strong>一直</strong>没回来时，整段答案没有任何引用， 而它的样子与"知识库里真的没有"一模一样（{@code 10 §5} 的"无引用三情形
 * A/B/C"就是为了区分这两者）。
 *
 * <h3>★ 为什么断言"下标"而不是只断言"顺序里 refs 在 delta 前面"</h3>
 *
 * <p>{@code kinds} 的整体相等（见 {@code StreamAssemblerSequenceTest}）能抓到"顺序错了"，
 * 但抓不到一种更隐蔽的变体：序列里<strong>出现了两条 {@code refs}</strong>（一条在首 delta 前、一条在后）。 那种流在"第一条 refs 在 delta
 * 之前"这个断言下<strong>仍然是绿的</strong>，而前端会把引用栏渲染两遍。 所以这里逐项断言：第一条 refs 的下标 <strong>等于 0</strong>（或 meta
 * 之后）、且 {@code refs} 恰好一条。
 */
class StreamDeltaOrderTest {

  /**
   * 正常路径：检索先完成 ⇒ 事件数在第一个 delta 之后就开始增长（证明是"边收边发"，不是攒完一次性吐）。
   *
   * <p>★ 注释里那个"检索先完成"是<strong>前提</strong>：本用例走的是 {@code begin → references → delta…} 的顺序。
   */
  @Test
  void refsComeFirstWhenRetrievalFinishesBeforeGeneration() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());

    List<Integer> countsAfterEachChunk =
        drain(assembler, ModelOutputChunk.generated("根据", "文档规定", "……"));

    assertThat(assembler.events()).hasSize(5);
    assertThat(countsAfterEachChunk)
        .as("每来一段文本就必须多一条事件 —— 攒完再发同样是'refs 先于 delta'，但用户看到的是转圈后整段蹦出来")
        .containsExactly(3, 4, 5);
    assertThat(refsIndex(assembler.events())).as("refs 的下标（meta 占了 0）").isEqualTo(1);
    assertThat(firstDeltaIndex(assembler.events())).as("第一个 delta 必须排在 refs 之后").isEqualTo(2);
  }

  /**
   * ★★ 核心断言：检索结果进缓冲、<strong>不影响顺序</strong> —— 无论 {@code references} 与第一个 {@code delta} 之间隔多久，{@code
   * refs} 都在第一个 {@code delta} 之前。
   *
   * <p>★ 这条<strong>不能</strong>写成"δ 之间插一个 sleep"：那会让测试依赖真实时间。 "隔多久"这件事在领域层是不存在的概念 ——
   * 装配器只看调用顺序。传输层的心跳才是时间的载体（见下一个用例）。
   */
  @Test
  void refsIsBufferedUntilTheFirstDeltaArrives() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());

    assertThat(kinds(assembler.events()))
        .as("references() 只进缓冲、不 emit —— 此刻还没有任何 delta，但 refs 也不该抢跑")
        .containsExactly(EventKind.META);

    assembler.delta("根据");

    assertThat(kinds(assembler.events()))
        .as("★ 第一次 delta 到来时才把 refs 冲出去，且 refs 必须排在它前面")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA);
  }

  /**
   * ★ 心跳（契约①的注释帧）可以出现在<strong>拿到 refs 之前</strong> —— 那正是"检索阶段"。
   *
   * <p>出处：{@code 03 §1.6} 与 {@code 10 §6.4}（"心跳逻辑必须覆盖检索阶段，不能只在生成阶段发"）。 判据是两件事同时成立：① 检索阶段确实能发出心跳；②
   * 发过心跳之后，{@code refs} 仍然在第一个 {@code delta} 之前。
   */
  @Test
  void heartbeatCanBeEmittedWhileWaitingForRefsAndDoesNotDisturbTheOrder() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.heartbeat();
    assembler.heartbeat();

    assertThat(kinds(assembler.events()))
        .as("检索阶段（还没拿到 refs）必须能发心跳，否则反向代理会在检索久一点时掐断连接")
        .containsExactly(EventKind.META, EventKind.HEARTBEAT, EventKind.HEARTBEAT);

    assembler.references(refs());
    assembler.delta("根据");

    assertThat(refsIndex(assembler.events())).as("refs 的下标").isEqualTo(3);
    assertThat(firstDeltaIndex(assembler.events()))
        .as("★ 心跳插队不算违约：refs 仍然必须早于第一个 delta")
        .isEqualTo(4);
  }

  /**
   * 零命中 ≠ 没检索：{@code references(List.of())} 仍然要发一条空的 {@code refs}，且仍在第一个 {@code delta} 之前。
   *
   * <p>★ 这两件事的可观测差别就是"知识库里没有"与"检索链路坏了"的区别（{@code 10 §5} 的 A/B/C）。
   */
  @Test
  void emptyRefsIsStillEmittedBeforeTheFirstDelta() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(List.of());
    assembler.delta("知识库里没有相关内容。");

    assertThat(kinds(assembler.events()))
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA);
    assertThat(((StreamEvent.References) assembler.events().get(1)).refs())
        .as("空的 refs 事件带的是一张空表（不是 null）")
        .isEmpty();
  }

  /**
   * ★★ 闸门（"该拒绝的要拒绝"那一半）：<strong>从来没有</strong>检索结果时，第一个 {@code delta} 直接抛， 而不是安静地当成"零引用"。
   *
   * <p>★ 为什么这属于判据①而不是"多写一条边界"：若这里退化成"当成零引用来处理"，那么"检索链路整条没跑起来" 就会<strong>安静地</strong>答完一整段没有引用的正文 ——
   * 用户分不出它与"知识库里真的没有"。 而"报红会有人修，少了一条没人注意到"，正是本项目最怕的那类问题。
   */
  @Test
  void deltaBeforeAnyRetrievalResultIsRefusedLoudly() {
    StreamAssembler assembler = assembler();
    assembler.begin();

    assertThatThrownBy(() -> assembler.delta("根据"))
        .as("没有检索结果就吐字 ⇒ 必须抛，而不是当成'零引用'")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("第一个 delta 之前必须已经拿到检索结果");

    assertThat(kinds(assembler.events()))
        .as("★ 抛出去之后不许留下半条 delta —— 否则'报错了但正文已经出去一半'")
        .containsExactly(EventKind.META);
  }

  /**
   * 迟到（第一个 {@code delta} 之后才给）的引用：<strong>不当场发</strong>（那会变成第二条 {@code refs}）， 由收尾补在 {@code done}
   * 之前。
   *
   * <p>★ 这是"某一个 delta 先跑了"这个现实场景的处置：契约不允许 {@code refs} 插在 {@code delta} 之间乱序， 但也不允许"引用整条丢掉" ——
   * 所以它被推迟到收尾，且<strong>全流仍然只有一条 {@code refs}</strong>。
   */
  @Test
  void lateRefsNeverBecomesASecondRefsEvent() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assembler.references(refs()); // 迟到的一份：幂等跳过，不产生第二条 refs

    assertThat(kinds(assembler.events()))
        .as("★ refs 只能有一条 —— 否则前端会把引用栏渲染两遍")
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA);
  }

  /**
   * 参数化（逐块）：假模型给出的每一个文本块，都必须是"先 refs 后 delta"。
   *
   * <p>★ 用 {@code @ValueSource} 逐段喂而不是只喂一段：只有第一段能暴露顺序错误， 而这个用例的价值在于把"每段都一样"这件事固定下来（第 17
   * 周有人给"加速"改成"先发 delta 后补 refs"时， 单独那一段的用例仍会红，但它红得清楚）。
   */
  @ParameterizedTest
  @ValueSource(strings = {"根据", "文档规定…", "……"})
  void everyDeltaChunkKeepsRefsAhead(String chunk) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta(chunk);
    assembler.delta("尾");

    assertThat(kinds(assembler.events()))
        .containsExactly(EventKind.META, EventKind.REFS, EventKind.DELTA, EventKind.DELTA);
  }

  /**
   * 参数化（终止路径 × 有引用）：<strong>四条路径上，{@code refs} 都排在 {@code done} 之前</strong>。
   *
   * <p>★ 为什么把四条路径一起过：三条异常路径的代码位置离"发引用"很远（定时器 / 异常处理 / 停止接口）， 最容易出现的坏味是"收尾时忘了把引用补出去" —— 那条流的 {@code
   * refs} 就整条消失了。
   */
  @ParameterizedTest
  @EnumSource(StreamTerminationPath.class)
  void refsPrecedesDoneOnEveryTerminationPath(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assembler.finish(path, StreamFixture.DURATION_MS);

    List<EventKind> kinds = kinds(assembler.events());
    assertThat(kinds)
        .as(
            "路径 %s：refs(%d) 必须早于 done(%d)",
            path, refsIndex(assembler.events()), kinds.indexOf(EventKind.DONE))
        .startsWith(EventKind.META, EventKind.REFS, EventKind.DELTA);
    assertThat(refsIndex(assembler.events())).isLessThan(kinds.indexOf(EventKind.DONE));
    assertThat(deltaTexts(assembler.events()))
        .as("★ 停止/失败路径上已生成的内容必须保留（03 §1.5），不许清屏")
        .containsExactly("根据");
  }

  /**
   * ★ 反证：{@code refs} 至少要被发出去一次 —— 防"把顺序保证实现成永远不发 refs"。
   *
   * <p>判据①的判据是"refs <strong>先于</strong> delta"。一个把 {@code references()} 写成空操作的实现
   * <strong>同样</strong>满足"没有 delta 之前发过 refs"（因为压根没发）—— 而引用栏会永远空着。
   * 所以"先于"这条必须<strong>同时</strong>钉住"确实发了一条"。
   */
  @Test
  void refsIsNotJustSkippedEntirely() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");

    assertThat(assembler.events())
        .as("★ '先于'不许退化成'永远不发'：refs 必须真的在序列里")
        .filteredOn(StreamEvent.References.class::isInstance)
        .hasSize(1);
    assertThat(((StreamEvent.References) assembler.events().get(1)).refs())
        .as("而且带的就是检索结果本身（不是空表）")
        .hasSize(2);
  }

  /**
   * {@code meta} 也必须最先 —— 契约顺序是 {@code meta → refs → delta*}。
   *
   * <p>★ 与判据①同族：{@code meta} 晚到意味着 {@code messageId} 晚到，而"生成中停止"要靠它（{@code 03 §1.2}）。 这里同时钉住"装配器把
   * messageId 放在 {@code meta} 与 {@code done} 的同一个值上"， 否则前端收到一个 {@code done} 却挂不回任何一条消息。
   */
  @Test
  void metaComesFirstAndCarriesTheSameMessageIdAsDone() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("根据");
    assembler.finish(StreamTerminationPath.COMPLETE, StreamFixture.DURATION_MS);

    assertThat(kinds(assembler.events()).get(0)).as("meta 必须第一条").isEqualTo(EventKind.META);
    StreamEvent.StreamMetaEvent meta = (StreamEvent.StreamMetaEvent) assembler.events().get(0);
    StreamEvent.StreamDoneEvent done =
        (StreamEvent.StreamDoneEvent) assembler.events().get(assembler.events().size() - 1);
    assertThat(done.messageId())
        .as("meta 与 done 必须是同一条消息 —— 否则界面永远停在生成中")
        .isEqualTo(meta.messageId())
        .isEqualTo(MESSAGE_ID);
  }

  /** {@code meta} 缺位（没有 traceId/model 的纯域内场景）时，序列从 {@code refs} 开始，顺序不受影响。 */
  @Test
  void orderHoldsEvenWithoutMeta() {
    StreamAssembler assembler = new StreamAssembler(MESSAGE_ID, null);
    assembler.references(refs());
    assembler.delta("根据");

    assertThat(kinds(assembler.events())).containsExactly(EventKind.REFS, EventKind.DELTA);
  }
}
