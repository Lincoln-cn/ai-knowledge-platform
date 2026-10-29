package cn.lincoln.aiplatform.conversation.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 用户"停止生成"的<strong>服务端信号</strong>（第 17 周 · 0.4.5）。
 *
 * <h3>★★ 它为什么必须存在（契约里这一条最容易被读漏）</h3>
 *
 * <p>冻结契约把 {@code ABORT}（用户主动停止）与 {@code DISCONNECT}（连接断开）定成<strong>两条不同的终止路径</strong>，
 * 且终态不同：{@code ABORT ⇒ done(STOPPED)}（<strong>不</strong>发 error）， {@code DISCONNECT ⇒ error +
 * done(FAILED)}。而这一轮真实的前端（{@code lib/sse.ts}）的 {@code abort()} 做的是 {@code controller.abort()} ——
 * <strong>掐断 fetch</strong>。
 *
 * <p>★★ 于是服务端只看到"连接没了"，<strong>看不出</strong>那是用户点了停止、还是网线掉了。 这两件事在同一个字节流上长得一模一样。若服务端把"连接没了"一律当
 * {@code ABORT}， 那么真正的断网会被显示成"已停止"（用户会以为是自己停的，而答案其实还没写完）； 一律当 {@code DISCONNECT} 则用户点停止之后看到一条失败提示。
 *
 * <p>⇒ 正确形状是：<strong>停止必须是一个显式的服务端信号</strong>（与连接状态无关）， 而"连接没了但不是停止"才按 {@code DISCONNECT}
 * 收。本类就是那个信号的落点：由停止入口标记， 由编排线程在每一个块上检查（见 {@code ChatService} 的消费点）。
 *
 * <h3>★ 形状：进程内、按消息 id、幂等、自动过期</h3>
 *
 * <ul>
 *   <li><strong>键是助手消息 id</strong>（不是会话 id）：一轮问答一条消息，停止的粒度就是它；
 *   <li><strong>只增不减地标记，读完即清</strong>（{@code consume}）：信号是"这一轮的一次性事件"，不是状态位 ——
 *       留着它会让下一轮同名消息（重生成）一上来就被停；
 *   <li><strong>不落库</strong>：它是<strong>运行中</strong>的控制信号。停止的<strong>结果</strong>（{@code STOPPED} +
 *       已生成内容）落库，信号本身不落 —— 与"任务推进的唯一真相源在后端、前端连接只是读取通道"是同一条口径。
 * </ul>
 *
 * <p>★ 进程内（{@code ConcurrentHashMap}）在多实例部署下<strong>不够</strong>（停止请求可能落到另一个实例）—— 那需要一条共享通道（redis
 * pub/sub 或一张表）。本批如实记在交付报告里：MVP 单实例成立， 多实例是<strong>已知不足</strong>，不是"已经支持"。
 */
@Component
public class ChatStopSignal {

  private final Set<UUID> requested = ConcurrentHashMap.newKeySet();

  /**
   * 已经"被认领"的助手消息 id —— ★ <strong>与 {@link #requested} 是两个集合，而这两个集合的分工就是全部设计</strong>。
   *
   * <p>一个消息 id 上的信号只有三种归宿，而它们必须**可区分**（这是本批实测踩出来的）：
   *
   * <ol>
   *   <li>在 {@link #requested} 里 ⇒ 还没人认领（这一轮正在跑、用户点了停止）；
   *   <li>被 {@link #agree} 认领 ⇒ 停止请求赢了，它**必须**被这一轮的收尾路径读到（{@code accepted=true}）；
   *   <li>被生成线程先读到 ⇒ 收尾路径赢了，晚到的停止请求必须**收不到**（{@code accepted=false}）。
   * </ol>
   *
   * <p>★ 为什么不能只用一个集合（第一版的写法）：那样"我认领时信号还在不在"与"这一轮到底停不停"会互相污染 —— 生成线程先读走时，请求侧会看到"信号没了"从而误报 {@code
   * accepted=false}，而用户明明点中了。 真栈用例 {@code ConversationStopEndpointTest} 当场抓住了这个错（它断言 {@code
   * accepted=true}）。
   */
  private final Set<UUID> decided = ConcurrentHashMap.newKeySet();

  /**
   * ★★ 这一轮是否<strong>已经取下终态</strong>（{@code #242}，2026-10-30）。
   *
   * <h3>它补的是哪一条缺口</h3>
   *
   * <p>"这一轮停不停"由两个并发方决定：收尾路径（{@link #settle}）与请求侧（{@link #agree}）。在只有 {@code requested}/{@code
   * decided} 两个集合时，两边各自读自己看到的东西，于是存在一个窗口： 收尾路径已经取完快照（判成 {@code COMPLETE}），请求侧才认领成功并回 {@code
   * accepted=true} —— 用户被告知"已停止"，落库却是 {@code GENERATED}（真栈实测： 停止时只吐了 4/54 字，回执 {@code
   * accepted=true}，最终终态 {@code GENERATED}、库里 54 字）。
   *
   * <p>⇒ 这里加一个<strong>共用闸门</strong>：两方都在 {@link #gate} 上同步，
   *
   * <ol>
   *   <li>请求侧先到 ⇒ 信号在里面 ⇒ 收尾路径必定读到它（{@code ABORT} ⇒ {@code STOPPED}）；
   *   <li>收尾路径先到 ⇒ 这个 id 被记进本集合 ⇒ 之后的请求侧一律<strong>拒收</strong>（{@code accepted=false}）， 而不是先答应再被覆盖。
   * </ol>
   *
   * 于是 {@code accepted=true} 与"终态必为 {@code STOPPED}"重新等价 —— 回执不再是猜测。
   */
  private final Set<UUID> settledTerminal = ConcurrentHashMap.newKeySet();

  /** 两个集合的唯一同步点（{@link #agree} / {@link #settle} / {@link #begin} 都必须经过它）。 */
  private final Object gate = new Object();

  /**
   * ★★ <strong>一轮生成开始：清掉这条消息 id 上的残留</strong>（{@code #143} 后半，2026-10-16）。
   *
   * <h3>它把哪一条"侥幸"变成机制</h3>
   *
   * <p>{@link #consume} 的注释已经写着"读到即清：留着会让下一轮同名消息一上来就被停" —— 但那是
   * <strong>收尾那一刻</strong>的清理，它漏掉一种时序：停止请求在<strong>收尾路径最后一次调用之后</strong> 才进来（用户点了停止，而生成恰好在同一毫秒写完）⇒
   * 那个 id 带着一个"上一轮的停止"活到下一轮。 今天之所以没出事，靠的是"**消息 id 不复用**"这条隐式约定（重生成开一条新的助手消息）——
   * 而"靠约定"正是本项目要消掉的那一类形状。
   *
   * <p>⇒ 这里把它变成<strong>显式的一步</strong>：每一轮生成开始（{@code ChatService.Run} 构造时）先清干净。
   * 于是"这一轮会不会被停"<strong>只看这一轮自己的请求</strong>，与前一轮的残留无关 —— 这正是 {@code #143} 判据里那句"**同 id
   * 二次生成不受前一次停止影响**"的载体。
   *
   * @param assistantMessageId 助手消息 id（{@code null} ⇒ 无操作）
   */
  public void begin(UUID assistantMessageId) {
    if (assistantMessageId != null) {
      synchronized (gate) {
        requested.remove(assistantMessageId);
        decided.remove(assistantMessageId);
        // ★ 上一轮的"终态已取下"也必须清掉（#242）：同一 id 重新生成时，它又是一轮全新的、可以被停的生成。
        settledTerminal.remove(assistantMessageId);
      }
    }
  }

  /**
   * ★★ <strong>登记一次停止请求：成功 ⇒ 这一轮会被收尾路径读到（{@code accepted=true}）； 失败 ⇒ 这一轮已经结束了，调用方**必须**据此回 {@code
   * accepted=false}，且**不留残留**</strong> （{@code #143} 后半）。
   *
   * <h3>为什么必须是<strong>一个</strong>方法，而不是"先 request 再 claim"</h3>
   *
   * <p>★ 两步 API 本身是个坑（本批实测踩到）：{@code ChatStreamEventRecordingTest} 里原来只有 {@code
   * request(...)}（那时它确实是唯一入口），而 {@code begin} 一上来就把"待领"的信号清掉了 ⇒ 那一轮<strong>正常跑完</strong>，用例红在
   * {@code expected STOPPED but was GENERATED}。 也就是说：把协议拆成两个公开方法，等于给每一个调用点留一次"漏写第二步"的机会，而漏写的症状是
   * <strong>停止静默失效</strong>（用户点了停止，服务端照常把答案写完）。
   *
   * <p>⇒ 两步收进类内部，对外只留这一个入口。{@link #begin}（一轮开始清残留）与 {@link #consume}（收尾时读一次）各自仍是唯一点，但它们不再需要调用方按顺序拼。
   *
   * @param assistantMessageId 助手消息 id
   * @return 是否登记成功（{@code false} = 没有待领的信号：这一轮已经收尾了，或本来就没有请求过）
   */
  public boolean agree(UUID assistantMessageId) {
    if (assistantMessageId == null) {
      return false;
    }
    synchronized (gate) {
      // ★★ ⓪ 终态已经取下 ⇒ 一律拒收（`#242`）：这时候答应下来也改不动终态了。
      //   宁可回 accepted=false（"这一轮已经收尾了"），也不能回 true 再被生成线程的 COMPLETE 覆盖 ——
      //   回执与终态不一致是本项目最贵的一种谎（用户看到"已停止"，答案却写满了）。
      if (settledTerminal.contains(assistantMessageId)) {
        requested.remove(assistantMessageId);
        decided.remove(assistantMessageId);
        return false;
      }
      // ★★ 三种状态，答案是"能不能成交"（幂等，且**顺序无关**）：
      //   ① 已在 `decided` ⇒ 这个人已经认领过这次停止（重复请求）⇒ 成交，**不要重复认领**；
      //   ② 还在 `requested` ⇒ 待领 ⇒ 认领：从 `requested` 移到 `decided`；
      //   ③ 两边都没有 ⇒ 这一轮已经收尾了（收尾路径的 consume 早跑过，`begin` 也清过）⇒ 当场拒收。
      //   ★ 为什么①必须挡在②前面：`decided` 与 `requested` 是**两个集合**，一个 id 不可能同时在两边
      //   —— 但"重复请求"必须能分辨出来，否则第二次请求会走到③被误报成"晚到了"。
      if (decided.contains(assistantMessageId)) {
        return true;
      }
      if (!requested.contains(assistantMessageId)) {
        return false;
      }
      requested.remove(assistantMessageId);
      decided.add(assistantMessageId);
      return true;
    }
  }

  /**
   * ★★ <strong>收尾那一刻：既取下终态，又读一次停止信号</strong>（{@code #242}）。
   *
   * <p>它是 {@link #consume} 的加强版：先把这个 id 记进 {@link #settledTerminal}（此后 {@link #agree} 一律拒收），
   * 再取走已认领的信号。两件事在 {@link #gate} 上做，因此与 {@link #agree} 之间<strong>没有窗口</strong>：
   *
   * <ul>
   *   <li>本次返回 {@code true} ⇒ 这一轮的终态必定是 {@code ABORT}（{@code STOPPED}），且此前那一次 {@code agree} 已经回了
   *       {@code accepted=true} —— 两者一起成立；
   *   <li>本次返回 {@code false} ⇒ 之前没人认领过（或者认领发生在本次之后，那时 {@link #agree} 会拒收）。
   * </ul>
   *
   * <p>★ 一个 id 上重复调用只第一次为真（与 {@link #consume} 同语义），因为 {@code ChatService} 的收尾路径可能被第二个入口读第二次， 但它读的是
   * {@code terminalPath} 快照、不会再落到这里。
   *
   * @param assistantMessageId 助手消息 id（{@code null} ⇒ 无操作）
   * @return 这一轮是否被请求过停止
   */
  public boolean settle(UUID assistantMessageId) {
    if (assistantMessageId == null) {
      return false;
    }
    synchronized (gate) {
      settledTerminal.add(assistantMessageId);
      return decided.remove(assistantMessageId);
    }
  }

  /**
   * 请求停止某一轮生成（幂等）。
   *
   * <p>★ <strong>它不是给编排/端点用的入口</strong>：登记一次停止请求请用 {@link #agree} （它把"置上 +
   * 认领"两步合成一步，见那里的注释）。本方法保留为内部与测试用的原始操作。
   *
   * @param assistantMessageId 助手消息 id
   */
  public void request(UUID assistantMessageId) {
    if (assistantMessageId != null) {
      requested.add(assistantMessageId);
    }
  }

  /**
   * 这一轮的停止信号是否已经被置上（<strong>不消费</strong>）。
   *
   * <p>★ 编排线程在每一个模型块上调用它：置上了就立刻收尾（{@code ABORT}）， 并<strong>抛异常打断上游</strong> ——
   * 不抛的话模型会继续吐（用户停了、token 还在烧，且不报错）。
   *
   * @param assistantMessageId 助手消息 id
   * @return 是否已请求停止
   */
  public boolean isRequested(UUID assistantMessageId) {
    // ★ 空守卫不是装饰：`ConcurrentHashMap` 的集合对 `null` 是**抛 NPE** 的（本批实测：
    //   `nullMessageIdIsANoOp` 当场红在 `Cannot invoke "Object.hashCode()" because "key" is null`）。
    if (assistantMessageId == null) {
      return false;
    }
    // ★★ 两个集合都要看（本批实测踩到）：认领会把信号从 `requested` **移到** `decided`，
    //   而 `Run#onChunk` 的快速检查点读的就是本方法 —— 只看 `requested` 的话，
    //   "用户点过停止"在认领之后**立刻变得看不见**，于是模型接着把答案吐完
    //   （`ChatStreamEventRecordingTest` 当场红在 `expected STOPPED but was GENERATED`）。
    //   ★ 而这正是"停止静默失效"的形状：不报错、答案还更完整。
    return requested.contains(assistantMessageId) || decided.contains(assistantMessageId);
  }

  /**
   * 取走这一轮的停止信号（读到即清 —— ★ 但只对<strong>被认领过的</strong>那一次为真）。
   *
   * <p>用于"收尾那一刻判一次"：它读的是 {@link #decided}，于是它与 {@link #agree} 构成一条 <strong>恰好一次</strong>的协议 ——
   * 一次停止请求只可能被下面两者之一取走：
   *
   * <ul>
   *   <li>这一轮的收尾路径（本方法，{@code ⇒ ABORT}）；
   *   <li>或生成已经先结束了，于是没有任何人会读它 ⇒ 由 {@link #agree} 当场拒收（{@code ⇒ accepted=false}）。
   * </ul>
   *
   * <p>★★ 为什么不是"直接 {@code requested.remove}"（第一版的写法，真栈当场证伪）：那样生成线程先读走信号时， 停止请求会看到"信号没了"从而误报 {@code
   * accepted=false} —— 而用户明明点中了停止。 两个集合分开之后，"我认领时它还在不在"这个问题才有一个明确的答案。
   *
   * @param assistantMessageId 助手消息 id
   * @return 这一轮是否被请求过停止（★ 同一个 id 上重复调用只第一次为真）
   */
  public boolean consume(UUID assistantMessageId) {
    return assistantMessageId != null && decided.remove(assistantMessageId);
  }

  /**
   * 认领一次停止请求 —— ★ <strong>private</strong>：这一步与"置上信号"是同一条协议的两半， 对外只暴露 {@link #agree}（两步分开暴露 =
   * 给每个调用点留一次"漏写第二步"的机会，本批实测踩到过）。
   *
   * @param assistantMessageId 助手消息 id
   * @return 是否认领成功（{@code false} = 没有待领的信号：这一轮已经收尾了，或本来就没有请求过）
   */
  private boolean claim(UUID assistantMessageId) {
    if (assistantMessageId == null) {
      return false;
    }
    // ★ 只有"本来有待领的信号"才算认领成功；成功之后它进入 decided，等着这一轮的收尾路径来读。
    if (requested.remove(assistantMessageId)) {
      decided.add(assistantMessageId);
      return true;
    }
    return false;
  }
}
