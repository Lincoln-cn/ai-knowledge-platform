package cn.lincoln.aiplatform.conversation.domain.stream;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <strong>流式回答装配器</strong> —— 纯领域逻辑：不碰 Spring、不碰 JDBC、不碰 HTTP。
 *
 * <p>它做一件事：把"模型输出流（增量文本 + 终态 + tokens）"与"检索得到的引用"<strong>缝成一条有顺序保证的事件序列</strong> （{@link
 * StreamEvent}）。它自己不知道 SSE 长什么样，也不发心跳（心跳的定时属于传输层）—— 它只保证<strong>形状与顺序</strong>。
 *
 * <h3>★★ 本类存在的理由：四条保证各自很容易在别处被悄悄破坏</h3>
 *
 * <ol>
 *   <li><strong>{@code refs} 先于第一个 {@code delta}</strong>（ROADMAP §4.7.3 第 17
 *       周判据①）。根因是"检索与生成是两条并发的流"： 检索慢 300ms 时，很自然的写法是"chunk 到了就先发 {@code delta}"，而那样正文里会先出现没有编号的
 *       {@code [1]}，引用栏则晚一步才出现。本类的做法是<strong>首元素缓冲</strong>：<strong>在 {@code refs} 发出去之前， 一个 {@code
 *       delta} 都不许 emit</strong>。注意缓冲的是 {@code refs}<strong>事件</strong>（不是 delta）：检索结果可能比 第一个 chunk
 *       晚到，此时"把首个 delta 压在手里等 refs"才是对的（反过来丢 delta 会篡改答案）。
 *   <li><strong>恰好一次 {@code done}</strong>（判据②）。四条终止路径（正常 / 停止 / 断开 / 超时）必须都收在同一个出口上。
 *       本类的做法是：<strong>唯一</strong>能产出 {@code done} 的公开方法是 {@link #finish}，且它一旦成功过 ({@link
 *       #settled()}) 就再也不产出第二条。{@code error} <strong>不是终态</strong> —— 失败路径先发 {@code error}
 *       再走同一个出口（契约原文："error 之后仍必须跟 done"）。
 *   <li><strong>三态终态</strong>（判据④）。四路径 → 三态的映射只有一处：{@link StreamTerminationPath#status()}。
 *   <li><strong>{@code tokens} 汇总</strong>（判据④ · {@code 10
 *       §8.4}）。口径是"该次问答<strong>全部</strong>模型调用的总和"， 所以累加只有一处实现（{@link TokenUsage#plus}），且缺省是 {@link
 *       TokenUsage#zero()} 而<strong>不是 {@code null}</strong>。
 * </ol>
 *
 * <h3>★ 用法（第 17 周的 worker 循环就长这样）</h3>
 *
 * <pre>{@code
 * StreamAssembler assembler = new StreamAssembler(messageId, new StreamMeta(messageId, traceId, modelId));
 * assembler.begin();                        // meta —— 先发，前端才谈得上"停止"
 * assembler.references(refs);               // 检索完成：进缓冲，还不 emit（慢检索也来得及）
 * assembler.delta(firstChunk);              // ★ 首次 delta：先把 refs 冲出去，再发 delta
 * assembler.addUsage(understandingUsage);   // 查询理解那一次调用
 * assembler.addUsage(hopJudgeUsage);        // 多跳判定那一次调用
 * assembler.addUsage(generationUsage);      // 生成那一次调用（10 §8.4：done.tokens 是三者之和）
 * assembler.finish(StreamTerminationPath.COMPLETE, elapsedMs);
 * }</pre>
 *
 * <h3>★ 线程与生命周期</h3>
 *
 * <p>传输层有<strong>两个写者</strong>：模型线程（{@link #delta} 等）与心跳线程（{@link #heartbeat}）会并发调用同一个实例 （GitHub
 * issue #146）。本类用<strong>监视器（{@code synchronized}）串行化所有状态读写</strong> —— 公开方法全部持锁，
 * 私有方法只在这些入口持锁时被调用。★ 但这只保证"事件不丢、不并发扩容异常"；<strong>sink 的写出顺序仍由 {@code ChatService} 的 {@code
 * writeLock} 保证</strong> —— 本类不替代那把锁。
 *
 * <p>★ 本类<strong>没有</strong>领域事件、没有仓储：它不落库。落库（{@code cv_message}、周期性 flush、状态机）是第 17 周 传输层与用例层的事 ——
 * 本批刻意不预支（判据是"六条数值与形状可断言"，不是"链路能跑"）。
 */
public final class StreamAssembler {

  /** 断开/超时路径合成的 {@code error.reason}（契约里 DISCONNECT 的 error 是可选的，本批发）。 */
  private static final String DISCONNECT_REASON = "连接中断";

  /** 断开/超时路径合成的 {@code error.hint}。 */
  private static final String NETWORK_HINT = "网络或服务异常，可重试";

  /** 闲置超时路径合成的 {@code error.reason}。 */
  private static final String TIMEOUT_REASON = "生成超时";

  /** 超时路径合成的 {@code error.hint}。 */
  private static final String TIMEOUT_HINT = "可稍后重试；若持续超时请联系管理员";

  /** 心跳载荷 —— 契约里 {@code HEARTBEAT_PAYLOAD = 'keep-alive'}（解析端只认前缀 {@code ':'}，不按载荷匹配）。 */
  private static final String HEARTBEAT_PAYLOAD = "keep-alive";

  /** 上游失败时的 {@code reason} 前缀（见 {@link #failureText}）。 */
  private static final String UPSTREAM_FAILED_PREFIX = "生成失败：";

  /**
   * 上游失败时的 {@code hint}。
   *
   * <p>★ 与 {@link #NETWORK_HINT} 分开的理由：网络故障的下一步是「重试」，而「模型名不存在」的下一步是 <strong>去改配置</strong> —— 同一个
   * hint 覆盖两种情况，等于两种都没说清（{@code #224}）。
   */
  private static final String UPSTREAM_HINT = "请检查该应用的对话模型配置（地址、密钥、模型名）；配置正确的话可稍后重试";

  /** 上游 detail 最多带多少字符 —— 一条 404 的响应体能到几 KB，线上没必要把整页都推给用户。 */
  private static final int MAX_DETAIL_CHARS = 160;

  /** 已按顺序产出的事件（含心跳）。 */
  private final List<StreamEvent> emitted = new ArrayList<>();

  /** 本次助手消息 id（进 {@code done} 与 {@code meta}）。 */
  private final String messageId;

  /** 连接建立时的元信息；{@code null} = 本次没有 meta（例如纯域内测试）。 */
  private final StreamMeta meta;

  /** 引用缓冲 —— ★ 本类的核心状态：非 {@code null} 表示"检索结果已到、但还没发出去"。 */
  private List<SourceRef> pendingRefs;

  /** {@code references} 是否已经被调用过（用于区分"没检索完"与"检索完了是空的"）。 */
  private boolean refsKnown;

  private boolean metaEmitted;
  private boolean refsEmitted;
  private boolean deltaEmitted;
  private boolean settled;

  private final StringBuilder text = new StringBuilder();
  private TokenUsage tokens = TokenUsage.zero();
  private StreamEvent.StreamDoneEvent done;

  /**
   * @param messageId 本次助手消息 id（进 {@code done}；{@code meta} 里也有同一个值）
   * @param meta 连接建立元信息（可空 = 不产出 {@code meta}；{@code traceId} 与 {@code model} 都由上层给）
   */
  public StreamAssembler(String messageId, StreamMeta meta) {
    if (messageId == null || messageId.isEmpty()) {
      throw new IllegalArgumentException("messageId 不能为空 —— done 与 meta 都要靠它把事件挂回同一条消息");
    }
    this.messageId = messageId;
    this.meta = meta;
  }

  // ────────────────────────── 输入：装配器吃进去的东西 ──────────────────────────

  /**
   * 连接已建立 —— 产出 {@code meta}。
   *
   * <p>契约把它排在第一位，理由是"先有 meta 才谈得上生成中停止"（{@code 03 §1.2}）。
   *
   * <p>★ 判据刻意<strong>不允许</strong>"refs/delta 已经发过之后再补 meta"：那会产出 {@code refs → meta}
   * 这种前端没有分支处理、但也不会报错的序列（{@code meta} 分支仍然会执行， 只是 {@code messageId}
   * 晚到，"停止"按钮的可用时机就错了）。这里直接抛，把顺序错误在第一个调用点上打出来。
   */
  public synchronized void begin() {
    requireNotSettled("begin");
    if (refsEmitted || deltaEmitted) {
      throw new IllegalStateException(
          "meta 必须最先发出：它之前已经有 refs/delta 了（契约顺序 meta → refs → delta* → artifact → done）");
    }
    emitMetaIfPending();
  }

  /**
   * 检索完成 —— 把引用<strong>存进缓冲</strong>。
   *
   * <p>★ <strong>它不 emit</strong>（这正是"首元素缓冲"的实现）：emit 发生在第一个 {@code delta} 到来时 （{@link
   * #delta}），或在没有 delta 的流收尾时（{@link #finish}）。这样无论检索比生成快还是慢， {@code refs} 都稳定地落在第一个 {@code delta}
   * 之前。
   *
   * <p>★ 重复调用<strong>幂等</strong>（同一批引用只发一次）：重复发 {@code refs} 会让前端把引用栏渲染两遍， 而契约里 {@code refs}
   * 是"一次性到达"的（{@code 10 §8.3}：多跳全部在生成前完成）。
   *
   * @param refs 检索结果（可以为空列表 = 零命中；见 {@link StreamEvent.References}）
   */
  public synchronized void references(List<SourceRef> refs) {
    requireNotSettled("references");
    if (refsEmitted) {
      return;
    }
    if (refsKnown) {
      throw new IllegalStateException("引用只能给一次 —— 契约里 refs 是一次性到达的（多跳全部发生在生成前，10 §8.3）");
    }
    this.pendingRefs = List.copyOf(Objects.requireNonNull(refs, "refs"));
    this.refsKnown = true;
  }

  /**
   * 上游给出一段增量文本。
   *
   * <p>★ <strong>首次调用会先把 {@code refs}（以及尚未发出的 {@code meta}）冲出去</strong> —— 这就是"在拿到第一个 delta 之前不许
   * emit 任何 delta"这条保证的落点。
   *
   * <p>★ <strong>它同时是"首元素缓冲"的闸门</strong>：若此时<strong>还没有</strong>任何检索结果 （{@link #references}
   * 一次都没被调用过），本方法<strong>抛 {@code IllegalStateException}、不 emit</strong>。
   * 这是刻意的：另一种做法是"当成零引用来处理"，而那样会在检索链路整条没跑起来时，安静地答完一整段 <strong>没有任何引用</strong>的正文 ——
   * 用户看到的是一个"看起来正常"的答案，没有任何地方报错 （{@code #10 §5} 的拒答与"无引用三情形 A/B/C"本来是为了让这种事可区分）。
   *
   * <p>★ 与之相对，<strong>收到检索结果"晚了一步"不算违约</strong>：若 {@code references} 是在第一个 {@code delta}
   * <strong>之后</strong>才被调用（典型情形 = 生成已经开始吐字、检索还在跑），本方法不抛 （{@link #references} 幂等跳过），而 {@link
   * #finish} 会在收尾时把 {@code refs} 补在 {@code done} <strong>之前</strong> —— 结果仍然是契约序（{@code refs} 排在全部
   * {@code delta} 之后但排在 {@code done} 之前， 且<strong>始终只有一个 {@code refs}</strong>）。
   *
   * <p>代价同样是明确的：接真模型时，"检索超时了、但模型已经开始吐字"这条路径最后会以 {@code refs: []} 收尾（因为没人给过引用）。这一条留给第 17 周的 worker
   * 循环 —— 它要么在超时前显式给 {@code List.of()}，要么直接走 {@link #finish} 收成失败。
   *
   * @param chunk 增量文本（非空；空增量见 {@link StreamEvent.Delta}）
   */
  public synchronized void delta(String chunk) {
    requireNotSettled("delta");
    flushPendingBeforeFirstDelta();
    emit(new StreamEvent.Delta(chunk));
    deltaEmitted = true;
    text.append(chunk);
  }

  /**
   * 汇总一次模型调用的用量（{@code 10 §8.4}：查询理解 / 多跳判定 / 生成<strong>各算一次</strong>）。
   *
   * <p>★ {@code null} = 这一路没有报用量（例如纯本地判定），语义是<strong>不变</strong>，不是"加 0 后又归零"。
   *
   * @param usage 本次调用的用量（可空）
   */
  public synchronized void addUsage(TokenUsage usage) {
    requireNotSettled("addUsage");
    this.tokens = this.tokens.plus(usage);
  }

  /**
   * 发一个心跳（契约①的 {@code : keep-alive}）。
   *
   * <p>★ <strong>驱动的不是本类</strong>：心跳的定时（"无业务帧时按心跳间隔发"）属于传输层，今天的调用方是 {@code ChatService} 的心跳线程 ——
   * 而它<strong>在检索之前就起</strong>（{@code #142} ③）， 于是"检索阶段也发心跳"（{@code 03 §1.6}、{@code 10
   * §6.4}）在生产链路上成立，而不只是本类的一个入口。
   *
   * <p>★ 本方法只保证一件事：心跳进同一条<strong>有序</strong>事件序列 —— 判据是"心跳发出之后， {@code refs} 仍然排在第一个 {@code delta}
   * 之前"。
   *
   * @return 刚产出的事件对象（便于调用方直接写出去）
   */
  public synchronized StreamEvent heartbeat() {
    requireNotSettled("heartbeat");
    StreamEvent.Heartbeat beat = new StreamEvent.Heartbeat(HEARTBEAT_PAYLOAD);
    emit(beat);
    return beat;
  }

  /**
   * ★★<strong>失败文案的唯一算法</strong> —— {@code error} 帧与落库的 {@code fail_reason} 都从这里取（{@code #224}）。
   *
   * <h3>为什么必须"一处算、两处用"</h3>
   *
   * <p>改之前是两处各写一份：装配器发常量「连接中断」，而 {@code ChatService.failReasonFor} 在<strong>有</strong> 上游 detail
   * 时落库「生成失败：上游返回 HTTP 404：…」。于是同一个失败在库里可查、在流里只剩一句「连接中断」—— {@code ChatService} 那行注释写着"与装配器发出去的
   * {@code error.reason} 同源"，而实现不同源。 <b>"同源"这件事只能靠"同一个函数"保证</b>， 靠两处写一样的话保证不住（第一次改文案就会分叉）。
   *
   * <h3>三档语义（{@code #224} 要求分清的那一刀）</h3>
   *
   * <ol>
   *   <li><strong>用户主动停止 / 客户端断开</strong>（没有 detail）⇒「连接中断」+「网络或服务异常，可重试」。 ★
   *       不许把上游的内部话术糊到用户脸上：用户点停止时，"上游说了什么"与他做的动作无关；
   *   <li><strong>上游失败</strong>（有 detail）⇒「生成失败：上游返回 HTTP 404：…」+ 去改配置的 hint。
   *       这句是<strong>可行动</strong>的 ——「连接中断」不能让人去改模型名；
   *   <li><strong>闲置超时</strong> ⇒「生成超时」+ 超时口径的 hint（与上面两档都不同）。
   * </ol>
   *
   * <p>★ detail 只做<strong>归一与截断</strong>（换行压成空格、超过 {@value #MAX_DETAIL_CHARS} 字加省略号），
   * <strong>不做脱敏</strong>：上游把它自己的密钥回显进 error body 这种情形要由出站脱敏（{@code #85}）在
   * <strong>更靠近上游的那一层</strong>处理 —— 在本方法里做只能遮住这一条路。
   *
   * @param path 终止路径（非空）
   * @param upstreamDetail 上游报错的可读原因；<strong>用户停止 / 写连接失败时为 {@code null}</strong>
   * @return 文案（{@code reason == null} 表示这条路径不该发 {@code error}）
   */
  public static FailureText failureText(StreamTerminationPath path, String upstreamDetail) {
    Objects.requireNonNull(path, "path");
    if (!path.errorPrecedesDone()) {
      return new FailureText(null, null);
    }
    if (path == StreamTerminationPath.IDLE_TIMEOUT) {
      return new FailureText(TIMEOUT_REASON, TIMEOUT_HINT);
    }
    String detail = normalizeDetail(upstreamDetail);
    return detail == null
        ? new FailureText(DISCONNECT_REASON, NETWORK_HINT)
        : new FailureText(UPSTREAM_FAILED_PREFIX + detail, UPSTREAM_HINT);
  }

  /** 把上游 detail 归一成"一句话"：空 ⇒ {@code null}；换行/连续空白压成一个空格；过长截断。 */
  private static String normalizeDetail(String upstreamDetail) {
    if (upstreamDetail == null) {
      return null;
    }
    String oneLine = upstreamDetail.strip().replaceAll("\\s+", " ");
    if (oneLine.isEmpty()) {
      return null;
    }
    return oneLine.length() <= MAX_DETAIL_CHARS
        ? oneLine
        : oneLine.substring(0, MAX_DETAIL_CHARS) + "…";
  }

  /**
   * 失败文案（{@code error} 帧的 {@code reason}/{@code hint}，也是库里 {@code fail_reason}/{@code fail_hint}）。
   */
  public record FailureText(String reason, String hint) {

    /** 这条路径要不要发 {@code error}。 */
    public boolean persistable() {
      return reason != null;
    }
  }

  /**
   * ★★ <strong>唯一</strong>的终态出口 —— 四条路径都走这里（ROADMAP 判据②）。
   *
   * <p>这个重载不带上游 detail ⇒ 只有「连接中断 / 生成超时」两档（用户停止、写连接失败、超时都属此）。 带上游原因请用 {@link
   * #finish(StreamTerminationPath, long, String)}。
   *
   * @param path 终止路径（非空）
   * @param durationMs 耗时（毫秒，非负）
   * @return 本次调用是否产出了 {@code done}（{@code false} = 已经收过尾了）
   */
  public synchronized boolean finish(StreamTerminationPath path, long durationMs) {
    return finish(path, durationMs, null);
  }

  /**
   * ★★ <strong>唯一</strong>的终态出口 —— 四条路径都走这里（ROADMAP 判据②）。
   *
   * <p>语义三条：
   *
   * <ol>
   *   <li>先把还没发的 {@code meta} / {@code refs} 补出去（顺序仍然是 meta → refs → … → done）；
   *   <li>失败路径（{@link StreamTerminationPath#errorPrecedesDone()}）先发一条 {@code error} —— {@code
   *       error} 不是终态，之后**仍然**要发 {@code done}（契约原文）；
   *   <li>发 {@code done}，状态取自 {@link StreamTerminationPath#status()}，{@code tokens} 取累计值。
   * </ol>
   *
   * <p>★ <strong>重复调用返回 {@code false} 且什么都不做</strong>（不抛）：这是"恰好一次"这条保证的写法 ——
   * 四条路径可能<strong>竞争</strong>（用户点停止的同时连接也断了）。抛异常会把一条完全正常的竞态变成 500，
   * 而正确语义是"先到的那条算数"。调用方靠返回值判断"是不是我收的尾"（例如"那我就不用再 flush 了"）。
   *
   * @param path 终止路径（非空）
   * @param durationMs 耗时（毫秒，非负）
   * @param upstreamDetail 上游报错的可读原因（★ 用户停止 / 写连接失败时传 {@code null} —— 见 {@link #failureText}）
   * @return 本次调用是否产出了 {@code done}（{@code false} = 已经收过尾了）
   */
  public synchronized boolean finish(
      StreamTerminationPath path, long durationMs, String upstreamDetail) {
    Objects.requireNonNull(path, "path");
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs 不能为负：" + durationMs);
    }
    if (settled) {
      return false;
    }
    emitMetaIfPending();
    flushRefsAtTermination();
    FailureText failure = failureText(path, upstreamDetail);
    if (failure.reason() != null) {
      emit(new StreamEvent.StreamErrorEvent(failure.reason(), failure.hint()));
    }
    this.done = new StreamEvent.StreamDoneEvent(path.status(), messageId, tokens, durationMs);
    settled = true;
    emit(done);
    return true;
  }

  // ────────────────────────── 输出：已经装配出来的东西 ──────────────────────────

  /**
   * 已经产出的事件序列（<strong>按产出顺序</strong>，含心跳）。
   *
   * @return 不可变快照
   */
  public synchronized List<StreamEvent> events() {
    return List.copyOf(emitted);
  }

  /** 已产出的 {@code done}（还没收尾时是 {@code null}）。 */
  public synchronized StreamEvent.StreamDoneEvent done() {
    return done;
  }

  /** 是否已经收尾（收尾之后装配器不再产出任何事件）。 */
  public synchronized boolean settled() {
    return settled;
  }

  /** 已累计的 token 用量（还没有任何调用时是 {@link TokenUsage#zero()}，不是 {@code null}）。 */
  public synchronized TokenUsage tokens() {
    return tokens;
  }

  /** 到目前为止累计的正文（所有 {@code delta} 拼接；★ 停止/失败路径上它<strong>保留</strong>，不清空）。 */
  public synchronized String text() {
    return text.toString();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private void requireNotSettled(String action) {
    if (settled) {
      throw new IllegalStateException(action + " 发生在 done 之后 —— 一条流里 done 是最后一条事件（契约：恰好一次终态）");
    }
  }

  /** 首元素缓冲的落点：第一个 delta 之前，先把 meta 与 refs 补出去。 */
  private void flushPendingBeforeFirstDelta() {
    emitMetaIfPending();
    // ★ 还没收到检索结果（refsKnown == false）⇒ 不当它是"空引用"，一个 delta 都不许先发
    if (!refsKnown) {
      throw new IllegalStateException(
          "第一个 delta 之前必须已经拿到检索结果（refs）—— 缺了它正文里的 [N] 会先出现而没有引用可点。"
              + "零命中请显式调用 references(List.of())");
    }
    flushRefs();
  }

  /** 把缓冲里的引用发出去（没缓冲过就不发）。 */
  private void flushRefs() {
    if (refsEmitted || !refsKnown) {
      return;
    }
    refsEmitted = true;
    emit(new StreamEvent.References(pendingRefs));
    pendingRefs = null;
  }

  /**
   * 收尾时补发 {@code refs} —— <strong>即使一次都没被调用过</strong>，也补一条空引用。
   *
   * <p>★ 两条语义在这里分工：{@link #flushPendingBeforeFirstDelta()} 的"缺引用 ⇒ 不许发 delta"管的是
   * <strong>顺序</strong>（正文里的 {@code [N]} 不能先于引用栏），本方法管的是<strong>契约里的 refs 槽位</strong>
   * （一次问答天然带着"检索了什么"这个答案）。若这里也不补，那么"检索还没回来就收尾"的流会一条 {@code refs} 都没有 —— 前端只能靠"refs
   * 没来"去猜，而它猜不出"零命中"与"检索没跑"的区别（见 {@link StreamEvent.References}）。
   */
  private void flushRefsAtTermination() {
    if (refsEmitted) {
      return;
    }
    if (!refsKnown) {
      pendingRefs = List.of();
      refsKnown = true;
    }
    flushRefs();
  }

  private void emitMetaIfPending() {
    if (metaEmitted || meta == null) {
      return;
    }
    metaEmitted = true;
    emit(meta.toEvent(messageId));
  }

  private void emit(StreamEvent event) {
    emitted.add(event);
  }
}
