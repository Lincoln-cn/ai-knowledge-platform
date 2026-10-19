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
   * 调试的一轮（{@code #184}）：<strong>只读应用绑定、不建会话、不建消息、不落库</strong>。
   *
   * <p>★★ 它是"调试与正式问答使用同一套配置"（{@code BR-02-08}）的落点：应用 → 模型 / 知识库 / 角色设定 / 检索参数 的映射仍然只在 {@link
   * ChatTurnWriter} 里做一次，{@code ChatService} 拿到的是与正式轮完全同形的 {@code PreparedTurn}。
   *
   * <p>默认实现<strong>抛</strong>而不是返回空：测试替身若没显式支持调试，应当红在"这个替身不支持调试"，而不是安静地 拿到一个 null /
   * 空轮次（那会让"没测"伪装成"测过了"）。
   *
   * @param appId 应用 id（看不见 ⇒ 404）
   * @param question 调试问题（非空）
   * @return 这一轮读数；{@code conversationId} 为 {@code null}（调试不属于任何会话）
   */
  default ChatTurnWriter.PreparedTurn prepareDebug(UUID appId, String question) {
    throw new UnsupportedOperationException("这个 ChatTurnStore 实现不支持应用调试（#184）");
  }

  /**
   * 重新生成的一轮：<strong>覆盖</strong>目标助手消息，不新增消息对（契约「不新增消息对」）。
   *
   * <p>★ 声明在接口上而不是只在实现里：{@code ChatService} 依赖的是本接口 （{@code private final ChatTurnStore turns}）——
   * 只加在实现类上，调用点编译不过 （本批实测：{@code 找不到符号: 方法 prepareRegenerate}）。
   *
   * @param targetMessageId 要覆盖的那条助手消息
   * @param userId 归属判定用它（看不见 ⇒ 404）
   * @return 这一轮读数（{@code assistantMessageId} = <strong>被覆盖的那条</strong>）
   */
  ChatTurnWriter.PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId);

  /**
   * 取某条用户消息的正文 —— {@code REGENERATE} 用它把"这一轮问的是什么"读回来。
   *
   * @param userMessageId 用户消息 id（可为 {@code null}）
   * @return 正文；取不到返回空串
   */
  String questionOf(UUID userMessageId);

  /**
   * 周期性 flush：把"还没落库的那一段"追加进去。
   *
   * @return 本次 flush 之后已落库的字符数
   */
  /**
   * 某一轮提问之前的对话历史（`#168`）。
   *
   * @param conversationId 会话
   * @param userMessageId 本轮用户消息（它的之前才算历史）
   * @param maxMessages 最多几条
   * @return 历史（时间正序）
   */
  java.util.List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
      UUID conversationId, UUID userMessageId, int maxMessages);

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
