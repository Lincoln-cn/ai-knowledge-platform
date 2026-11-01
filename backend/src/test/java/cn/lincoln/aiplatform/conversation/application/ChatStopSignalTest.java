package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #143} 后半：<strong>停止信号的两条结构性质</strong> —— 「恰好一次」与「一轮开始就清残留」。
 *
 * <h3>这个类维护的是一个三态的生命周期（三个方法各管一步，顺序无关且幂等）</h3>
 *
 * <pre>
 *   request(id)   「用户点了停止」这件事发生        → requested 里
 *   agree(id)     服务端答应停这一轮（登记/认领）    → decided 里   ← 端点的 accepted 取它的返回值
 *   consume(id)   收尾路径读一次（读到即清）        → 两边都没有   ← 读到 ⇒ ABORT
 *   begin(id)     一轮开始：清掉上一条同 id 的残留    → 两边都没有
 * </pre>
 *
 * <h3>它为什么必须单独钉（而不是"在真栈里顺便验一下"）</h3>
 *
 * <p>"集合里的 id 永不淘汰"正是它原来的病：某天有人重发同一个消息 id（重生成），它会<strong>一上来就被停</strong>，
 * 界面上看起来像"模型不吐字"——没有异常、没有日志。这类账在真栈上要复现得靠时序，所以在单元级把两条性质穷尽：
 *
 * <ul>
 *   <li>★ <strong>恰好一次</strong>：一次停止请求只可能被两者之一取走 —— 要么这一轮的收尾路径读到它（{@code ABORT}），
 *       要么生成已经先结束、由请求侧当场拒收（{@code accepted=false}）；
 *   <li>★ <strong>清残留</strong>（{@link ChatStopSignal#begin}）：一轮开始必须把上一条同 id 的残留清掉， 于是"同 id
 *       二次生成不受前一次停止影响"从"靠消息 id 不复用这条约定"变成机制。
 * </ul>
 *
 * <p>★★ 两条本批实测的教训（都写进了断言）：
 *
 * <ol>
 *   <li><strong>只置上、不登记 ⇒ 停止静默失效</strong>：{@code ChatStreamEventRecordingTest} 原来只调 {@code
 *       request(...)}，而 {@code begin} 会把"没人认领的待领信号"清掉 ⇒ 那一轮正常跑完 （{@code expected STOPPED but was
 *       GENERATED}）；
 *   <li><strong>"先置上、再 remove"是恒真的写法</strong>：那样 {@code agree} 永远返回 true，三处断言当场红 —— 所以它是"先问、再移"。
 * </ol>
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>；真栈那一侧（晚到的停止 ⇒ {@code accepted=false} 且不留残留 ⇒ 下一轮照常跑完）在
 * {@code PublicStopEndpointTest} 里。
 */
class ChatStopSignalTest {

  private final ChatStopSignal signal = new ChatStopSignal();

  /**
   * ★★ 恰好一次：只置上 ⇒ 收尾路径读不到；置上 + 登记 ⇒ 读得到，且只读得到一次。
   *
   * <p>★★ 这条节奏是本批实测逼出来的：第一版让 {@code consume} 直接取"待领的那个"，于是"生成线程先读走" 会让请求侧误报 {@code
   * accepted=false}（真栈用例 {@code ConversationStopEndpointTest} 当场抓住）。 两个集合分开之后，"我认领时它还在不在"才有明确答案。
   */
  @Test
  void theLifecycleIsExactlyOnce() {
    UUID messageId = UUID.randomUUID();

    // ① 只置上：这一轮还没答应停 ⇒ 收尾路径读不到
    signal.request(messageId);
    assertThat(signal.consume(messageId))
        .as("★ 只被置上、还没登记的，收尾路径**读不到**（否则'生成先结束'那一侧会被误判成 ABORT）")
        .isFalse();

    // ② 登记：这一轮答应停了 ⇒ 读得到，而且只读得到一次
    assertThat(signal.agree(messageId)).as("★ 登记成功 —— 这一轮会被收尾路径读到").isTrue();
    assertThat(signal.consume(messageId)).as("★ 登记过了才读得到，而这是**唯一**一次").isTrue();
    assertThat(signal.consume(messageId)).as("★ 第二次必须为假：信号是一次性事件，不是状态位").isFalse();

    // ③ 收尾之后（两边都空）再来一次 ⇒ 必须当场拒收（这就是端点的 accepted=false）
    assertThat(signal.agree(messageId)).as("★ 没有任何待领的信号 ⇒ 晚到的请求必须被拒收（accepted=false）").isFalse();
  }

  /**
   * ★★ 登记是<strong>幂等</strong>的：重复请求同一条消息 ⇒ 仍返回 true，且不会重复认领。
   *
   * <p>★ 它挡的是一个真实形状：用户在界面上连点两次"停止"（或客户端重试），两条请求都要得到 "服务端收下了"这个答案，而信号只能被收尾路径取走一次。
   */
  @Test
  void agreeingTwiceIsIdempotentAndStillReadsOnce() {
    UUID messageId = UUID.randomUUID();

    signal.request(messageId);
    assertThat(signal.agree(messageId)).isTrue();
    signal.request(messageId);
    assertThat(signal.agree(messageId)).as("★ 第二次请求同一条消息也要成交（幂等）").isTrue();

    assertThat(signal.consume(messageId)).as("★ 但信号只被读走一次").isTrue();
    assertThat(signal.consume(messageId)).isFalse();
  }

  /**
   * ★★ 清残留：{@link ChatStopSignal#begin} 必须把上一条同 id 留下的东西清干净（两个集合都要）。
   *
   * <p>★ 场景逐字（它就是这条账的形状）：第一轮正常收到停止（{@code request + agree + consume}），此后一个
   * <strong>晚到的</strong>请求又把同一个 id 置上 —— 若没有 {@code begin}，第二轮（同 id）一上来就会看见它。
   */
  @Test
  void beginDiscardsWhatThePreviousRunLeftBehind() {
    UUID messageId = UUID.randomUUID();

    // 第一轮：置上 → 登记 → 收尾那一刻读走（这一轮正常地收到了停止）
    signal.request(messageId);
    assertThat(signal.agree(messageId)).isTrue();
    assertThat(signal.consume(messageId)).isTrue();

    // 晚到的请求：这一刻没有人在消费了 ⇒ 残留在集合里
    signal.request(messageId);
    assertThat(signal.isRequested(messageId)).as("前提：残留确实存在（否则这条用例是空跑）").isTrue();

    // 第二轮开始：必须清干净
    signal.begin(messageId);

    assertThat(signal.isRequested(messageId))
        .as("★★ #143 后半判据：同 id 的二次生成不受前一次停止影响 —— begin 必须清掉残留")
        .isFalse();
    assertThat(signal.agree(messageId)).as("★ 清干净之后连'登记'都登记不到东西").isFalse();
  }

  /**
   * ★ 已登记但还没被收尾路径读走时，{@code begin} 也要清（否则下一轮会读到上一轮的登记）。
   *
   * <p>这一格是上面那条的"另一侧"：残留既可能是<strong>待领</strong>的，也可能是<strong>已登记未读</strong>的。
   */
  @Test
  void beginAlsoClearsAnUnreadAgreement() {
    UUID messageId = UUID.randomUUID();

    signal.request(messageId);
    assertThat(signal.agree(messageId)).isTrue();

    signal.begin(messageId);

    assertThat(signal.consume(messageId)).as("★ 上一轮登记过、但没读走的那个信号不许流到下一轮").isFalse();
  }

  /** ★ {@code null} 输入不许抛（{@code ConcurrentHashMap} 的集合对 {@code null} 是抛 NPE 的）。 */
  @Test
  void nullMessageIdIsANoOp() {
    signal.begin(null);
    signal.request(null);

    assertThat(signal.agree(null)).as("★ null 不是'某个消息'，登记不到任何东西").isFalse();
    assertThat(signal.consume(null)).isFalse();
    assertThat(signal.isRequested(null)).isFalse();
    assertThat(signal.settle(null)).as("★ null 也不是'某一轮'，取不到终态").isFalse();
  }

  /**
   * ★★ #242 的核心不变量：<strong>终态取下之后到来的停止请求必须被拒收</strong>。
   *
   * <p>它挡的是真栈今天量到的那条：用户在第一帧（4 字）点停止，回执 {@code accepted=true}，而最终终态是 {@code GENERATED}、库里 54 字全文 ——
   * 因为收尾路径已经取完快照、请求侧才认领成功。现在的形状是：收尾路径先 {@code settle} ⇒ 请求侧 {@code agree} 只能回 false（{@code
   * accepted=false}），<strong>两边的答案不再能同时为真</strong>。
   */
  @Test
  void aStopThatArrivesAfterTheTerminalIsSettledIsRefused() {
    UUID messageId = UUID.randomUUID();

    // 收尾路径先到：这一轮判成 COMPLETE（没有信号可读）
    assertThat(signal.settle(messageId)).as("★ 没人认领过 ⇒ 收尾路径读到 false（COMPLETE）").isFalse();

    // 请求侧后到（而且它先 request 再 agree，和 ChatStopService 的形状一致）
    signal.request(messageId);
    assertThat(signal.agree(messageId))
        .as("★★ 终态已取下 ⇒ 必须拒收（accepted=false）—— 答应下来也改不动终态了")
        .isFalse();

    // 拒收之后不许留残留（否则下一轮同 id 一上来就被停）
    assertThat(signal.isRequested(messageId)).as("★ 拒收必须连带清残留").isFalse();
  }

  /**
   * ★★ #242 的另一侧：<strong>停止先到 ⇒ 收尾路径必定读到它</strong>（终态 {@code STOPPED}）。
   *
   * <p>两条合起来才是"{@code accepted=true} ⇔ 终态 {@code STOPPED}"：只测这一侧的话，一个"总是返回 true 的 agree"也能过。
   */
  @Test
  void aStopThatArrivesBeforeTheTerminalWins() {
    UUID messageId = UUID.randomUUID();

    signal.request(messageId);
    assertThat(signal.agree(messageId)).as("★ 终态还没取下 ⇒ 成交（accepted=true）").isTrue();
    assertThat(signal.settle(messageId)).as("★★ 收尾路径必须读到它（⇒ ABORT ⇒ STOPPED）").isTrue();
    assertThat(signal.settle(messageId)).as("★ 只读一次：信号是一次性事件").isFalse();
  }

  /**
   * ★ 重新生成同一个 id ⇒ 上一轮"终态已取下"必须被 {@link ChatStopSignal#begin} 清掉。
   *
   * <p>否则同 id 的第二轮会一上来就"停不下来"：第一次 {@code agree} 就被拒收，而这一轮其实刚开始。
   */
  @Test
  void beginClearsTheSettledTerminalSoAFreshRunCanBeStoppedAgain() {
    UUID messageId = UUID.randomUUID();

    assertThat(signal.settle(messageId)).isFalse();
    signal.begin(messageId);

    signal.request(messageId);
    assertThat(signal.agree(messageId)).as("★ 新一轮必须能被停（上一轮的'终态已取下'不许漏过来）").isTrue();
    assertThat(signal.settle(messageId)).isTrue();
  }
}
