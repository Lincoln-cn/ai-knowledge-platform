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
   * 请求停止某一轮生成（幂等）。
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
    return requested.contains(assistantMessageId);
  }

  /**
   * 取走这一轮的停止信号（读到即清）。
   *
   * <p>用于"收尾那一刻判一次"：这一轮结束后信号必须消失，否则下一轮会被误停。
   *
   * @param assistantMessageId 助手消息 id
   * @return 这一轮是否被请求过停止
   */
  public boolean consume(UUID assistantMessageId) {
    return requested.remove(assistantMessageId);
  }
}
