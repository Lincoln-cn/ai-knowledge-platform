package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRole;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「停止生成」这一个用例（契约 {@code stopMessage}；{@code #119} 的收口）。
 *
 * <h3>★★ 它存在的唯一理由：把"用户意图"变成一个服务端看得见的<strong>显式输入</strong></h3>
 *
 * <p>契约里 {@code ABORT}（用户停止 ⇒ {@code STOPPED}，不发 error）与 {@code DISCONNECT} （连接断开 ⇒ {@code FAILED}
 * + error）是<strong>两条</strong>路径、<strong>两个</strong>终态。 而在此之前，前端唯一的停止动作是 {@code abort()}（掐 fetch）——
 * 服务端只看到"连接没了"， 于是用户停止被记成故障、真正的断网被记成用户意图。这条端点是"用户点了停止"这句话的<strong>唯一</strong>表达位。
 *
 * <p>★ 它<strong>不</strong>造第二套停止机制：真正的信号仍然是既有的 {@link ChatStopSignal} （进程内、按助手消息
 * id、一次性）。本类只做三件事：<strong>判可见性 → 判还能不能停 → 置信号</strong>。
 *
 * <h3>★★ 为什么是<strong>独立</strong>的一个类，而不是加进 {@link ChatService}</h3>
 *
 * <p>{@code ChatService} 是<strong>编排</strong>：它刻意只认识窄端口（落库侧是 {@link ChatTurnStore}），
 * 不认识仓储、也不认识消息/会话实体 —— 那条链上的每多一个依赖，都多一处会与"四条终止路径的唯一出口"分叉的地方。 而本用例是<strong>控制面</strong>的一次读 +
 * 一次置位（不参与生成、不产出帧），它要的正是 {@link MessageRepository} / {@link ConversationRepository} 这两个读口。两者混在一起，
 * 编排层就会为了一个"停下来"的请求而认识整个数据模型。
 *
 * <h3>★ 权限：看不见一律 404（不是 403）</h3>
 *
 * <p>与 {@link ChatTurnWriter} 的会话归属判定<strong>同一条口径</strong>（{@code 07 §3.3} 纪律 2）：
 * "不存在"与"不属于我"走同一个出口、同一段文案 —— 403 等于告诉对方"这个 id 是存在的"，那是一次免费的探测。
 * 判定顺序也刻意先判<strong>会话</strong>再判消息：否则一条不属于我的会话里的消息 id， 会通过"消息不存在"与"消息存在但不是助手消息"这两种不同的报错漏出存在性。
 *
 * <h3>★★ 已经收尾的那条消息：{@code accepted=false}，而不是错误、也不是静默</h3>
 *
 * <p>停止请求晚到（这一轮已经 {@code GENERATED} / {@code STOPPED} / {@code FAILED}）是<strong>正常</strong>的：
 * 用户点了停止，而生成恰好在同一毫秒写完。三种处置里只有一种是对的：
 *
 * <ul>
 *   <li>报错 ⇒ 用户按的是"停止"，给他看一条失败提示是错的；
 *   <li>静默 200 ⇒ 谁也不说不出这一轮到底有没有被停（本项目头号病）；
 *   <li>★ <strong>200 + {@code accepted=false} + 当前状态</strong> ⇒ 回执里写着"没停成，因为它已经收尾了"。
 * </ul>
 *
 * <p>★ 并且<strong>不置信号</strong>：为一条已经收尾的消息置信号，那个 id 会永远留在 {@link ChatStopSignal} 的集合里（它是"一次性事件"、只由
 * {@code consume} 取走）。 那是一种没有症状的泄漏：直到某天有人重发同一个消息 id，它一上来就被停。
 *
 * <h3>★ 已知不足（如实记，见交付报告）</h3>
 *
 * <p>信号在 {@link ChatStopSignal} 里是<strong>进程内</strong>的：多实例部署时停止请求可能落到另一个实例。 本批没有改它（MVP
 * 单实例成立），但这条边界必须写在这里，而不是留在"看起来已经支持了"的状态。
 */
@Service
public class ChatStopService {

  private static final Logger log = LoggerFactory.getLogger(ChatStopService.class);

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ChatStopSignal stopSignal;

  public ChatStopService(
      ConversationRepository conversations, MessageRepository messages, ChatStopSignal stopSignal) {
    this.conversations = conversations;
    this.messages = messages;
    this.stopSignal = stopSignal;
  }

  /**
   * 停止回执（契约 {@code frontend/src/api/conversations.ts} 的 {@code ChatStopReceipt}）。
   *
   * @param accepted 是否真的置上了停止信号（{@code false} = 这一轮已经收尾，停止请求晚到了）
   * @param status 服务端<strong>此刻</strong>读到的消息状态（可能仍是 {@code GENERATING} ——
   *     终态由生成线程按判定顺序落库，与这次响应之间有一段正常的间隔）
   */
  public record Receipt(boolean accepted, MessageStatus status) {}

  /**
   * 请求停止某一轮生成（幂等：重复请求只是重复置位同一个信号）。
   *
   * @param conversationId 会话 id（契约路径里的那一格；也是可见性判定的入口）
   * @param messageId 助手消息 id（契约 {@code meta} 帧给出的那个值）
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @return 停止回执
   * @throws BusinessException 会话看不见 / 消息不属于该会话（{@code 404}）· 该消息是用户消息（{@code 400}）
   */
  public Receipt requestStop(UUID conversationId, UUID messageId, UUID userId) {
    // ── ① 会话：看不见 ⇒ 404（先判它，免得从消息的报错里漏出存在性）──
    Conversation conversation =
        conversations
            .findById(conversationId)
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(() -> notFound("会话不存在", "它可能已被删除，或者不在你的可见范围内"));

    // ── ② 消息：必须属于这个会话，且必须是助手消息 ──
    Message message =
        messages
            .findById(messageId)
            .filter(found -> found.conversationId().equals(conversation.id()))
            .orElseThrow(() -> notFound("消息不存在", "请刷新会话后重试；它可能属于另一轮问答"));

    if (message.role() != MessageRole.ASSISTANT) {
      // ★ 停止信号是按助手消息 id 查的：收下一条用户消息的 id 只会得到一个"谁都不看"的信号
      //   （200 + 什么都不发生 —— 静默失效）。所以这里必须显式拒绝。
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只能停止助手回答", "用户消息没有生成过程，不需要停止");
    }

    // ── ③ 还能不能停：已经收尾 ⇒ 回执说清"没停成"，且不置信号（见类注释）──
    if (message.status() != MessageStatus.GENERATING) {
      log.info("收到停止请求，但助手消息 {} 已经收尾（{}）⇒ accepted=false，不置信号", messageId, message.status());
      return new Receipt(false, message.status());
    }

    // ── ④ 置信号：这是"用户点了停止"在服务端唯一的落点（机制仍是既有的 ChatStopSignal）──
    stopSignal.request(messageId);
    log.info("已置停止信号：会话 {} 的助手消息 {} 将按 ABORT 收尾（STOPPED，保留已生成内容）", conversation.id(), messageId);
    return new Receipt(true, message.status());
  }

  private static BusinessException notFound(String message, String hint) {
    return new BusinessException(ErrorCode.NOT_FOUND, message, hint);
  }
}
