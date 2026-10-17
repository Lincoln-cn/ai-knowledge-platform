package cn.lincoln.aiplatform.platform.model;

import java.time.Duration;
import java.util.UUID;

/**
 * <strong>流式</strong>对话模型端口（跨模块 SPI，{@code 01 §二} 第 3 行：端口在我们这边）—— 第 17 周 · 0.4.5 新增。
 *
 * <h3>★★ 为什么不能复用 {@link ChatPort}</h3>
 *
 * <p>{@link ChatPort#complete} 的语义是<strong>一次调用换一整段正文</strong>（查询理解、多跳判定要的正是它）。 而问答链路要的是"边生成边发给用户"：
 * 契约 {@code frontend/src/api/stream-types.ts} 把 {@code delta}（逐字增量）定成<strong>一等事件</strong>，
 * 且首字延迟（{@code ROADMAP §二} 的 B4 ≤10s）只有在流式下才有意义。 用 {@code complete} 拼出来的"一次性 delta" 会让整条 SSE
 * 链路退化成"等完了再发"——那时心跳与"生成中停止"都失去载体。
 *
 * <h3>★ 本批<strong>只定义形状</strong>，不实现真流式</h3>
 *
 * <p>真实现（OpenAI 兼容的 {@code stream: true} + 逐块解析）与 {@code ChatModelAdapter} 同族， 但它需要 SDK/HTTP 的流式读取，
 * 属<strong>接真模型那一批</strong>。本批（接 SSE 真链路）要的是：
 *
 * <ul>
 *   <li>链路<strong>可被断言</strong> —— 测试注入一个假实现，逐块喂文本，于是"事件序列 / 恰好一次 done / refs 先于首个 delta /
 *       落库终态"全部可以在真 HTTP + 真库上读到读数；
 *   <li>生产不至于起不来 —— 兜底实现 {@code ChatPortFallbackStreamAdapter} 用 {@link ChatPort#complete}
 *       一次性拿到全文，再按块送出（形状对、只是没有"边生成边到"）。
 * </ul>
 *
 * <h3>★ 终止语义：端口只报"它自己为什么结束"，不报 HTTP 层的四路径</h3>
 *
 * <p>{@link End#COMPLETE} / {@link End#FAILED} 是<strong>这一路模型调用</strong>的结局。 而契约的四条终止路径（{@code
 * COMPLETE} / {@code ABORT} / {@code DISCONNECT} / {@code IDLE_TIMEOUT}）是<strong>一次问答</strong>的结局
 * —— 后三条由<strong>传输层</strong>观测（用户停止信号、写连接失败、闲置超时），不属于模型端口。 把四路径塞进端口会让"谁负责发 {@code error}"变成两处口径。
 *
 * <p>★ 也刻意<strong>不让端口抛异常来表示失败</strong>：{@code DISCONNECT} 是从写连接抛出来的， 若端口也用异常表达失败，
 * 调用方就分不清"模型坏了"与"用户断线了"——而这两者的终态与 {@code error} 文案完全不同 （{@code FAILED} 的两种原因）。
 */
public interface ChatStreamPort {

  /**
   * 流式发一次对话补全。<strong>同步阻塞</strong>：本方法返回即表示这一路调用已经结束（成功或失败）。
   *
   * @param modelId 由应用绑定或 {@link ChatPort#pickChatModel()} 给出的模型 id
   * @param prompt 完整提示词
   * @param timeout 这次调用的时间预算（超过即放弃 ⇒ 按 {@link End#FAILED} 收）
   * @param callback 逐块回调（调用方在这里把块写出去 / 落库）
   */
  void stream(UUID modelId, String prompt, Duration timeout, Callback callback);

  /**
   * 带**角色边界**的多轮流式对话（`#168`）。
   *
   * <p>★ 默认实现把消息拼成一段文本交给 {@link #stream} —— **兼容兜底**，会丢 role 边界 （而丢了边界的多轮正是反向注入 `#84` 的形状）⇒
   * **生产实现必须覆盖**。
   */
  default void streamConversation(
      UUID modelId, java.util.List<ChatMessage> messages, Duration timeout, Callback callback) {
    StringBuilder flattened = new StringBuilder();
    for (ChatMessage message : messages) {
      flattened.append(message.wireRole()).append(": ").append(message.content()).append('\n');
    }
    stream(modelId, flattened.toString(), timeout, callback);
  }

  /** 逐块回调。★ 业务帧那几个方法都<strong>可能</strong>被调用方抛异常打断 —— 见 {@link Callback#onChunk}。 */
  interface Callback {

    /**
     * 收到一段增量文本。
     *
     * <p>★★ <strong>允许抛异常来中断这一路调用</strong>（本接口唯一的"取消"机制）。两个真实的调用方：
     * 用户点了停止（装配器抛"停止信号"）、写连接已经断了（{@code IOException}）。端口实现<strong>必须</strong>让这个异常传出去、 并停止继续调用回调 ——
     * 吞掉它等于"用户停了、模型还在烧 token"，而那不会报错。
     *
     * @param chunk 一段增量（实现不应送空串；空串由调用方按"无操作"处理）
     */
    void onChunk(String chunk);

    /**
     * 这一路调用<strong>上报用量</strong>（{@code #142} ④：此前本接口没有这条通道 ⇒ 生产上 {@code done.tokens}
     * 恒为零，而"零用量"与"没有模型调用"在成本口径上完全不可分）。
     *
     * <h3>★★ 为什么是独立的一个方法，而不是让 {@link #onEnd} 多带一份用量</h3>
     *
     * <ol>
     *   <li><strong>依赖方向</strong>：本接口在 {@code platform}，而用量的值对象 {@code TokenUsage} 在 {@code
     *       conversation.domain}。让 SPI 引用它就是把依赖反过来（{@code conversation} 已经依赖 {@code platform}）⇒
     *       模块环，{@code ModuleBoundaryTest} 的 {@code verify()} 当场判红。所以这里只传两个
     *       <strong>分量</strong>，由消费方折成它自己的值对象；
     *   <li><strong>到达时机与终态无关</strong>：兼容 OpenAI 的流式实现在<strong>最后一个块</strong>里给用量，也就是 {@code onEnd}
     *       之前那一刻 —— 把它挂在 {@code onEnd} 上会逼实现"为了带用量而推迟报终态"；
     *   <li><strong>"没报"必须是一个可表达的状态</strong>：不给 {@code onEnd} 加一个可空的第三参，是因为那样
     *       "这一路没有用量"与"实现忘了传"在签名上长得一模一样（本仓最贵的那类洞）。<strong>不调用本方法</strong>就是"没报"。
     * </ol>
     *
     * <h3>★ 三条语义</h3>
     *
     * <ul>
     *   <li><strong>可以不调</strong>：没有用量可报时（例如兜底实现走的 {@code ChatPort#complete} 只回原文）
     *       就<strong>不调</strong>，<strong>不要报 0</strong> —— 报 0 与"没调用"在成本口径上是两个不同的答案 （{@code 10
     *       §8.4}；{@code TokenUsage.zero()} 的注释把这条写死了）；
     *   <li><strong>可以调多次</strong>：消费方负责累加（一次问答的 {@code done.tokens} 是全部调用之和）；
     *   <li><strong>必须在 {@link #onEnd} 之前调</strong>：一条流一旦终态，账就封了 —— 终态之后再报会被装配器拒绝 （{@code
     *       StreamAssembler#addUsage} 的 {@code requireNotSettled}）。
     * </ul>
     *
     * @param promptTokens 提示词 token 数（非负）
     * @param completionTokens 生成 token 数（非负）
     */
    void onUsage(int promptTokens, int completionTokens);

    /**
     * 这一路调用结束了。
     *
     * <p>★ {@code COMPLETE} 之后<strong>不会</strong>再有 {@link #onChunk}；{@code FAILED} 之后同样不会。
     * 实现<strong>必须</strong>保证只调用一次 —— 否则调用方那台"恰好一次 done"的闸门会被两次终态推着走两次。
     *
     * @param end 结局（见 {@link End}）
     * @param detail {@code FAILED} 时的可读原因（{@code COMPLETE} 时忽略）
     */
    void onEnd(End end, String detail);
  }

  /** 这一路模型调用的结局（★ 不是契约的四条终止路径，见接口注释）。 */
  enum End {

    /** 正常写完。 */
    COMPLETE,

    /** 上游报错 / 超时 / 输出不合规 —— {@code detail} 给可读原因。 */
    FAILED
  }
}
