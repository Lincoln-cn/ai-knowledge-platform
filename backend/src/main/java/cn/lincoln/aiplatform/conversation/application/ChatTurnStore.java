package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import java.util.List;
import java.util.UUID;

/**
 * 一轮问答里"读会话 / 写消息"这一侧的窄端口（第 17 周 · 0.4.5）。
 *
 * <h3>★ 为什么要有这个接口（而我们已经有 {@link ChatTurnWriter} 了）</h3>
 *
 * <p>为了让 {@code ChatService} 那条<strong>编排</strong>逻辑能被脱离数据库地测：
 *
 * <ul>
 *   <li><strong>断开路径的两帧顺序</strong>（{@code error → done}、各恰好一次）在真 HTTP 上 <strong>读不到</strong> ——
 *       连接是客户端掐的，之后写什么都到不了。验证它需要"接管事件出口"， 而那件事只需要一个假的落库侧：事件出口是 {@code ChatService}
 *       与调用方之间的东西，与数据库无关；
 *   <li>真栈读数（状态 + 正文 + 引用快照 + tokens 落库）由 {@code ChatStreamHttpTest} 单独读 —— 两边各管一段，谁都不兼任。
 * </ul>
 *
 * <p>★ 与本项目其它端口同一条口径：<strong>接口窄到只有被用到的动作</strong>，实现（ {@link ChatTurnWriter}）拿框架类型做落库，编排层不认识 JPA
 * 也不认识事务。
 */
public interface ChatTurnStore {

  /** 取会话（看不见 ⇒ 404）/ 建两条消息 / 定模型；见 {@link ChatTurnWriter#prepare}。 */
  ChatTurnWriter.PreparedTurn prepare(
      UUID conversationId, UUID appId, String question, UUID userId);

  /**
   * 周期性 flush：把"还没落库的那一段"追加进去。
   *
   * @return 本次 flush 之后已落库的字符数
   */
  int flush(UUID messageId, String fullTextSoFar, int persistedChars);

  /** 终态回写（状态 + 已生成文本 + 引用快照 + tokens）；见 {@link ChatTurnWriter#finish}。 */
  void finish(
      UUID messageId,
      StreamTerminationPath path,
      String fullTextSoFar,
      int persistedChars,
      List<SourceRef> references,
      TokenUsage usage,
      long durationMs,
      String failReason,
      String failHint);
}
