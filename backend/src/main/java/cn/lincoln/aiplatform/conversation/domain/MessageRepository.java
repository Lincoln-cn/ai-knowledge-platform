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
 *   messages.save(message);
 *   conversations.raiseClassification(conversationId, maxOf(refs));  // ★ 库侧一条 UPDATE，不读实体
 * </pre>
 *
 * <p>★★ <strong>2026-10-16 订正（{@code #108}）</strong>：上面第二句原先写的是 {@code
 * conversation.raiseClassification(...)} + {@code conversations.save(conversation)} —— 那是
 * <strong>"读 → 改 → 更新"</strong>，<strong>同事务之外</strong>的两次并发抬升会丢更新（会话密级滞后于真实 max， 不报错、不留痕）。★ 抬升现在走
 * {@link ConversationRepository#raiseClassification} （<strong>不接收实体</strong> ⇒
 * "把陈旧实体写回去"这条路径在签名上不存在）。 ★ 但 {@code save(Conversation)} 仍然会写 {@code max_classification} 这一列 ——
 * 谁沿用上面那段老写法， 谁就把丢更新原样搬回来（本批未封死，见 {@code ConversationClassificationWriteTest} 里那条反向读数）。
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
   * ★★ 按 id 取并<strong>锁住这一行</strong>（{@code SELECT … FOR UPDATE}）。
   *
   * <p>只给"读历史时收敛陈旧 {@code GENERATING}"用（{@code #182}）：读到 {@code GENERATING} 之后、写回 {@code FAILED}
   * 之前，收尾线程可能正好把它完成。若不加锁，"读时收敛"会把一条<strong>刚刚生成好的答案</strong>改写成失败 ——
   * 用户看到的是"答案没了"，而日志里两边都成功。加锁后两个写者排队，后到者重新看到对方写的终态并让路。
   *
   * <p>★ 默认实现退化成普通读：领域层不为测试假件强加锁语义；真正的锁由 JPA 适配器提供。
   *
   * @param id 消息 id
   * @return 消息；不存在时空
   */
  default Optional<Message> findByIdForUpdate(UUID id) {
    return findById(id);
  }

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

  /**
   * ★★ 常规分页读（{@code page}/{@code size} 那一档；登录面历史消息端点）。
   *
   * <p>★ 为什么单开一条而不是"{@link #listByConversation} 取全部再在内存里切"：长会话（几百上千条）上
   * 那是一次**全表读**，而这一档正是聊天窗口每次打开都会走的路。
   *
   * <p>★ 为什么返回领域自己的 {@link Slice} 而不是 Spring 的 {@code Page}：后者会把框架类型漏进领域层 （与 {@code
   * ConversationRepository} 同一条既有判断）。
   *
   * @param conversationId 会话 id
   * @param offset 从第几条开始（0 起）
   * @param limit 取多少条
   * @return 这一页（升序）与<strong>该会话的总条数</strong>（★ 总条数必须与内容同一份谓词）
   */
  Slice slice(UUID conversationId, int offset, int limit);

  /**
   * ★★ {@code around} 档的窗口读：以目标为中心前后各取若干条，<strong>目标必在结果里</strong>。
   *
   * <p>★★ <strong>两条"必须由实现保证"的语义</strong>（失效都不会报错）：
   *
   * <ol>
   *   <li><strong>目标必须在结果里</strong> —— 只按 {@code createdAt} 切窗口时，同刻的多条消息会把目标挤出窗口，
   *       于是"跳到某条消息"变成"跳到它附近"（用户看不出区别，但那一条不在）；
   *   <li><strong>找不到目标要能表达"找不到"</strong> —— 用 {@link Optional#empty()}。契约逐字写着 "找不到该消息时应报
   *       404，而不是静默回退到第一页"。
   * </ol>
   *
   * <p>★★ <strong>为什么回的是 {@link Window} 而不是裸的列表</strong>（本批与 mock 对齐时发现的一处真漂移）： {@code total}
   * 必须是<strong>会话总数</strong>，不是窗口条数 —— mock 那侧逐字回的是 {@code total: list.length}（整条会话），而前端拿 {@code
   * Math.ceil(total / MSG_PAGE_SIZE)} 判断"窗口前还有没有更早的一页"。只回窗口条数 ⇒ 深链跳进来之后**"加载更早"消失**
   * （用户回不到更早的内容，而页面上没有任何报错）。
   *
   * @param conversationId 会话 id（★ 目标必须属于它：只按消息 id 找会让调用方越过会话边界）
   * @param targetMessageId 目标消息 id
   * @param radius 前后各取多少条；{@code < 0} 视为 0
   * @return 窗口（对话自然顺序升序）+ 会话总数；目标不在该会话里 ⇒ 空
   */
  Optional<Window> windowAround(UUID conversationId, UUID targetMessageId, int radius);

  /**
   * 一页消息 + 该会话的总条数。
   *
   * <p>★ 两个分量一起返回是刻意的：分两次问会出现"这中间又落了一条消息"的窗口 ⇒ {@code total} 与页内容不同口径，而那是没人会怀疑的读数。
   *
   * @param content 这一页的消息（按对话自然顺序升序）
   * @param total 该会话的消息总条数
   */
  record Slice(List<Message> content, long total) {}

  /**
   * {@code around} 档的读数：窗口 + <strong>会话总数</strong>。
   *
   * @param messages 窗口里的消息（升序，含目标）
   * @param total 该会话的消息总条数（★ 不是窗口条数 —— 理由见 {@link #windowAround} 的注释）
   */
  record Window(List<Message> messages, long total) {}
}
