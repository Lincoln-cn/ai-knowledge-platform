package cn.lincoln.aiplatform.conversation.domain;

/**
 * 消息状态机（契约 {@code frontend/src/api/conversations.ts} 的 {@code MessageStatus}）。
 *
 * <h3>★★ 取值逐字对齐契约，而且<strong>不许自创第五个</strong></h3>
 *
 * <p>四个取值与契约的联合类型一字不差：{@code GENERATING} / {@code GENERATED} / {@code STOPPED} / {@code
 * FAILED}。{@code 02 §5.2} 的注释里写的是另一套（{@code PENDING} / {@code STREAMING} / {@code DONE} / {@code
 * STOPPED} / {@code ERROR}），本枚举<strong>不采用</strong> —— 契约是线上形状、设计册那一行是冻结之前的意图，
 * 两套取值域并存会让"这条消息到底停在哪一态"变成两处口径（理由与本批的取舍写在 {@code V33} 的文件头）。
 *
 * <p>★ <strong>多一个取值的症状不是报错</strong>：前端 {@code onDone} 按这三个终态分派 UI，收到一个认不出的值 就只能停在"生成中"——
 * 页面永远转圈，而任何地方都没有异常。
 *
 * <h3>★ 为什么"哪些是终态"落在这里，而"怎么转"落在 {@link Message}</h3>
 *
 * <p>一条状态机有<strong>两个不同的问题</strong>，混在一起写就会出现第二处口径：
 *
 * <ul>
 *   <li>"这个取值是不是终态" —— 只与<strong>取值域</strong>有关，是 {@link #isTerminal()}，在这里；
 *   <li>"这次转移合不合法" —— 与<strong>被转的那个对象</strong>有关（消息的 role、当前内容、已经发生的用量）， 是 {@link Message} 的
 *       {@code complete} / {@code stop} / {@code fail} 与私有 {@code appendChunk}。
 * </ul>
 *
 * <p>★ 与 {@code StreamStatus}（{@code domain/stream}）刻意是<strong>两个</strong>枚举，即使取值有三个重合： 那个是"这一条 SSE
 * 流以什么收尾"（四条终止路径映射过来的<strong>结果</strong>），本枚举是"这一行消息在库里是什么状态"
 * （一个可以停留很久、被刷新读到的<strong>状态</strong>）。把两者合并会让 {@code StreamTerminationPath} 的映射 与消息的生命周期互相绑死 ——
 * 而它们的<strong>可达集合本来就不同</strong>：消息可以有 {@code GENERATING}， 那不是一个终态，永远不会出现在 {@code done} 里。
 */
public enum MessageStatus {

  /** 生成中 —— 还没有终态。★ 这一态会被"刷新页面"读到（{@code A2 状态真实性}的载体）。 */
  GENERATING,

  /** 正常生成完成（终态）。 */
  GENERATED,

  /** 用户主动停止（终态）。★ 已生成的内容<strong>必须保留</strong>（{@code 03 §1.4} / MSG-I2）。 */
  STOPPED,

  /** 失败（终态）：连接断开 / 闲置超时 / 上游报错。 */
  FAILED;

  /**
   * 是不是终态。
   *
   * <p>★ 三个终态的共同点是"不能再转"：终态之后不许再追加文本、不许再标状态。这一条<strong>必须由领域层挡</strong> （库层的 {@code CHECK}
   * 既不能跨行、也不能比较新旧值），否则"生成完成之后又追加了一段文本"这种篡改 <strong>不会报错</strong>，只会让落库的内容与用户看到的内容分叉。
   *
   * @return {@code true} = 终态，之后任何转移都必须被拒绝
   */
  public boolean isTerminal() {
    return this != GENERATING;
  }
}
