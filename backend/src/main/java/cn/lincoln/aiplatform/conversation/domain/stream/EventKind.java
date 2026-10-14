package cn.lincoln.aiplatform.conversation.domain.stream;

/**
 * SSE 事件名（wire 上的 {@code event:} 值）—— 与冻结契约逐字一致。
 *
 * <p>取值出处是<strong>两处一致</strong>的：契约里的事件清单注释（{@code frontend/src/api/stream-types.ts} 开头 6 行）与前端解析器的
 * {@code switch}（{@code frontend/src/lib/sse.ts} 的 {@code emit}）。 后端 emit
 * 的每一个事件名都必须能在这里找到，<strong>且只在这里找</strong> —— 在控制器里手写字符串常量， 就等于把"事件名有几个、分别叫什么"变成两处口径。
 *
 * <h3>为什么把 {@code HEARTBEAT} 也放进来（它不是业务事件）</h3>
 *
 * <p>契约①把心跳定义成 <strong>SSE 注释帧</strong>（{@code : keep-alive}），<strong>没有</strong> {@code event:} 行
 * —— 所以它不是一个 wire 上的事件名，而 {@code EventKind.HEARTBEAT} 是<strong>本侧</strong>的
 * 归一化取值：它让"心跳"进入同一条有序事件序列，从而"心跳发生在检索阶段而不是只在生成阶段" （{@code 03 §1.6}、{@code 10
 * §6.4}）这件事可以被<strong>断言</strong>，而不是只靠读代码。
 *
 * <p>★ 本批<strong>不发</strong>心跳：定时与"无业务帧时发"属于传输层（第 17 周 SSETransport 的职责）。 这里只承载它，让装配器里的顺序断言能把它算进去（见
 * {@code StreamAssembler#heartbeat()}）。
 */
public enum EventKind {

  /** 连接建立：{@code messageId} / {@code traceId} / {@code model}。 */
  META("meta"),

  /** 引用来源：<strong>必须先于第一个 {@code delta}</strong>。 */
  REFS("refs"),

  /** 逐字增量。 */
  DELTA("delta"),

  /** 结构化产物（当前为 suggestions）—— 契约里有，本批不产出（V1.1）。 */
  ARTIFACT("artifact"),

  /** 终态收尾：GENERATED / STOPPED / FAILED。 */
  DONE("done"),

  /** 失败原因与下一步；之后<strong>仍会走 {@code done}</strong>。 */
  ERROR("error"),

  /** 心跳注释帧（{@code : keep-alive}）—— 不是业务事件，见类注释。 */
  HEARTBEAT("keep-alive");

  private final String wireName;

  EventKind(String wireName) {
    this.wireName = wireName;
  }

  /**
   * wire 上的名字。
   *
   * <p>★ 对前六个来说它等于小写的枚举名（{@code event:} 行的取值）；对 {@link #HEARTBEAT} 来说它是 <strong>注释帧载荷</strong>（契约的
   * {@code HEARTBEAT_PAYLOAD = 'keep-alive'}），因为心跳在 wire 上 根本没有 {@code event:}
   * 行。这个"不一样"必须显式写出来，而不是靠调用方记得特判心跳 —— 前端已经有过一次教训（{@code .cmd} 里的中文注释被当成命令、连带吃掉同一行的 {@code set}，
   * 前端静默跑成 mock）：<strong>两件事长得像时，最省事的做法是让它们看起来不一样。</strong>
   *
   * @return wire 名（心跳返回注释帧载荷）
   */
  public String wireName() {
    return wireName;
  }
}
