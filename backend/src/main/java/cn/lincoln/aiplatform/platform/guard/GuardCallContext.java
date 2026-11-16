package cn.lincoln.aiplatform.platform.guard;

import java.util.UUID;

/**
 * 围栏判定时「这一次提问属于谁、属于哪个会话」的随行上下文。
 *
 * <h3>★ 为什么需要它</h3>
 *
 * <p>拦一条提问要回答管理员最关心的三件事：<b>谁问的</b>、<b>在哪个会话里问的</b>、 <b>什么时候</b>。第三件审计表自己记，前两件在拦截那一刻<b>根本不在手上</b>：
 * 拦截点在 Spring AI 的 advisor 链上，它拿到的只有 {@code Prompt} —— 没有登录主体，也没有会话 id。而这两样在上游（{@code
 * ChatService}）是有的。
 *
 * <p>所以由知道它们的那一层把上下文放进这里，advisor 读取。
 *
 * <h3>★ 为什么是 InheritableThreadLocal</h3>
 *
 * <p>模型调用跑在 {@code ChatService} 新起的线程上（那一层是刻意开的：真流式要在调用方线程之外 逐帧回调），普通 ThreadLocal 到了子线程就是空的。{@code
 * InheritableThreadLocal} 在 {@code new Thread()} 那一刻复制父线程的值，正好对上这个用法。
 *
 * <p>★ 拿不到就是 null：这条上下文只影响<b>审计里多两个字段</b>，不参与判定。 让"拿不到"变成失败会把一次正常问答变坏，而它本来只是让排障更好做。
 */
public final class GuardCallContext {

  private static final InheritableThreadLocal<Carrier> CURRENT = new InheritableThreadLocal<>();

  private GuardCallContext() {}

  /**
   * 一次提问的随行身份。
   *
   * @param actorId 提问的人；{@code null} = 拿不到（公开问答的匿名访客、后台任务）
   * @param conversationId 会话 id；{@code null} = 这一轮还没有会话
   */
  public record Carrier(UUID actorId, UUID conversationId) {}

  /** 放进当前线程（必须在起子线程<b>之前</b>调，否则子线程读不到）。 */
  public static void set(UUID actorId, UUID conversationId) {
    CURRENT.set(new Carrier(actorId, conversationId));
  }

  /** 读当前线程的上下文；没有就是 {@code null}。 */
  public static Carrier current() {
    return CURRENT.get();
  }

  /** 清掉。父线程与子线程都该调，避免线程复用时串味。 */
  public static void remove() {
    CURRENT.remove();
  }
}
