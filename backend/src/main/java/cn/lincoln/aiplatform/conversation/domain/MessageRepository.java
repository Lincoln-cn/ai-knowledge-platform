package cn.lincoln.aiplatform.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <h3>★★ 这个接口刻意<strong>只有两个方法</strong>，而"少"是它的全部价值</h3>
 *
 * <p>写入路径只有 {@link #save} 一条。消息的每一次变化（追加文本、绑引用、收尾）都发生在 {@link Message} <strong>自己身上</strong>，然后由同一条
 * {@code save} 落库 —— 所以"状态机在哪儿生效" 这个问题只有一个答案：领域对象里。
 *
 * <p>★ <strong>为什么没有 {@code appendChunk(id, text)} / {@code markStopped(id)} 这类"库层写"方法</strong>：
 * 那会把状态机的判断拆给 SQL（"当前 status 是不是 {@code GENERATING}"变成一句 {@code where status = …}），
 * 而<b>被挡住的非法转移</b>与<b>静默无操作</b>在返回值上长得一模一样（都是"影响了 0 行"）。 症状是"停止之后的迟到 delta 被安静丢弃"与"迟到 delta
 * 把已完成的答案加长"这两种完全相反的病 都表现为"没有报错"。
 *
 * <h3>★ 为什么也没有"按会话列出消息"</h3>
 *
 * <p>分页 / 窗口的读法是 {@code frontend/src/api/conversations.ts} 的 {@code MessageQuery} （{@code page} /
 * {@code size} / {@code around} 三者互斥且有优先级），它属于 {@code /conversations/:id/messages} 那一批（{@code
 * ROADMAP §11.2} 第 17 周）。 <strong>今天还没有调用方</strong>，所以这里<strong>不预支</strong>一个形状 —— 一个没人调的查询方法
 * 只会让人以为"消息列表已经能取了"（{@code #27} 点名的 A 类病）。 ★ 库层已经为它准备好了：{@code idx_msg_conv (conversation_id,
 * created_at)} 随本批的迁移一起落。
 *
 * <h3>★ 事务边界不在这一层</h3>
 *
 * <p>{@code 02 §5.2} 把"引用集合落库"与"会话密级抬升"定成<strong>同一个事务</strong>：
 *
 * <pre>
 *   message.bindReferences(refs);
 *   conversation.raiseClassification(maxOf(refs));   // 领域层：只升不降
 *   messages.save(message);                          // ← 两次 save 必须在同一个事务里
 *   conversations.save(conversation);
 * </pre>
 *
 * <p>⇒ 事务的开合属于<strong>用例层</strong>（第 17 周），而不是这两个仓储方法各自的事：
 * 把它们各自标成"独立事务"恰好会拆散那条口径，制造出"引用已落库、会话密级还没跟上"的窗口 （{@code V32} 的注释把那个窗口叫"越权能看到"）。本批的测试因此显式开事务来验这一条。
 */
public interface MessageRepository {

  /** 保存（新增或更新）。★ 状态机的判断已在 {@link Message} 里做完，这里只落库。 */
  Message save(Message message);

  /**
   * 按 id 取。
   *
   * <p>★ <strong>不过滤任何"可见性"</strong>：消息的可见性完全由所属会话决定 （{@code
   * Conversation.userId}），所以收窄发生在"取会话"那一步，不在这里再写一遍 —— 两处各写一份判定，就会有"某一处忘了写"的那一天（{@code 13 §6.4}
   * 的两处耦合点）。
   *
   * @param id 消息 id
   * @return 消息；不存在时空
   */
  Optional<Message> findById(UUID id);

  /**
   * 按会话列出全部消息（<strong>按创建时间升序</strong>，即对话的自然顺序）。
   *
   * <p>★ 本批<strong>只有测试调用它</strong>（真栈往返要"读回来一整条对话"）。真正的分页 / 窗口读法 （{@code page} / {@code around}）属第
   * 17 周，见类注释 —— 这里给的是一个<strong>没有分页语义</strong>的 最小形状，刻意不加 {@code Pageable}：那会把框架类型漏进领域层（与 {@code
   * JpaConversationRepository} 同一条判断）。
   *
   * @param conversationId 会话 id
   * @return 该会话的消息，按 {@code created_at} 升序
   */
  List<Message> listByConversation(UUID conversationId);
}
