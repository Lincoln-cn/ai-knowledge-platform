package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageFeedback;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息级赞踩的<strong>写入用例</strong>（契约 {@code submitMessageFeedback}： {@code POST
 * /messages/{messageId}/feedback}，body {@code { type: 'UP' | 'DOWN' | null }}）。
 *
 * <p>★ 它补的是 <strong>S3</strong> 的一条（{@code ROADMAP §2.1}：
 * 「赞/取消赞<b>刷新后仍在</b>」）——「刷新后仍在」这四个字意味着它<strong>必须落库</strong>， 不能只活在前端状态里。本批之前契约里那条端点是**空账**（{@code
 * /messages} 在 {@code ContractDriftTest} 的未实现清单里，{@code cv_message} 也没有 {@code feedback} 列）。
 *
 * <h3>★★ 归属判定：看不见 ⇒ 404（不是 403）</h3>
 *
 * <p>与 {@link ChatHistoryService} / {@link ChatStopService} <strong>逐字同一条</strong> （{@code 07 §3.3}
 * 纪律 2）："不存在"与"不属于我"走同一个出口、同一段文案 —— 403 等于告诉对方"这个 id 是存在的"。
 *
 * <p>★ 判定路径是<strong>先取消息、再用它的 {@code conversationId} 取会话</strong>： 不解引用（{@code MessageRepository} 不
 * join 会话），但**归属的权威是会话** —— 消息本身没有 {@code userId}。
 *
 * <h3>★ 为什么"是不是助手消息 / 是不是生成中"由领域层判（不在本类）</h3>
 *
 * <p>那两条是<strong>消息自己的不变量</strong>（{@code Message#rate} 的 javadoc 写了理由）， 把它们写在本类会让"从别的入口评价一条消息"绕过它们
 * —— 而领域不变量一旦有第二个入口就不再是不变量。
 */
@Service
public class MessageFeedbackService {

  private final ConversationRepository conversations;
  private final MessageRepository messages;

  public MessageFeedbackService(ConversationRepository conversations, MessageRepository messages) {
    this.conversations = conversations;
    this.messages = messages;
  }

  /**
   * 赞 / 踩 / 取消评价。
   *
   * @param messageId 目标消息 id
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @param rawType 契约里的原始取值（{@code 'UP'} / {@code 'DOWN'} / {@code null}）；★ 解析在 {@link
   *     MessageFeedback#parse}，非法取值直接 400 且**带合法清单**
   * @return 落库后的那条消息（契约要求回 {@code ChatMessage} —— 前端用它就地更新那一格）
   * @throws BusinessException 消息不存在 / 会话不属于当前用户（404）· 取值非法 / 不是助手消息 / 仍在生成（400）
   */
  @Transactional
  public Message rate(UUID messageId, UUID userId, String rawType) {
    Message message = messages.findById(messageId).orElseThrow(() -> notFound(messageId, "消息"));
    // ★★ 归属的权威是**会话**（消息没有 userId）；看不见一律 404，与"消息不存在"同一段文案
    Conversation conversation =
        conversations
            .findById(message.conversationId())
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(() -> notFound(messageId, "消息"));

    try {
      message.rate(MessageFeedback.parse(rawType));
    } catch (IllegalArgumentException e) {
      // ★ 领域层抛的是 IllegalArgumentException（"只有助手消息可评价" / "还在生成中" / "取值不合法"），
      //   这里统一成 400 并把原话带上 —— 那句话已经是可读原因，再包一层反而丢掉"怎么办"。
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    // ★ 显式 save：本方法是 @Transactional，脏检查其实也会落库 —— 但"确实保存了"这件事
    //   不该依赖调用方是否在事务里（同一个用例将来若被非事务路径复用，脏检查就失效了）。
    return messages.save(message);
  }

  /** 404 的统一形状：**不区分**"消息不存在"与"不属于我"（区别对待本身就是信息泄露）。 */
  private static BusinessException notFound(UUID messageId, String what) {
    return new BusinessException(ErrorCode.NOT_FOUND, what + "不存在或不属于当前用户：" + messageId);
  }
}
