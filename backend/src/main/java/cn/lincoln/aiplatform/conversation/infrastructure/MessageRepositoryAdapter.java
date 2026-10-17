package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的消息仓储适配成 domain 的 {@link MessageRepository}。
 *
 * <p>★ 存在的理由与其它适配器相同：<strong>框架类型不许进领域层</strong>。
 *
 * <p>★ <strong>这个类刻意很薄</strong>：{@link #save} 只做一次 {@code save}，没有任何"顺手把会话计数 +1" 或"顺手算一下密级"的逻辑 ——
 * 状态机与密级抬升只发生在领域对象上（{@link Message#appendText} / {@code
 * Conversation.raiseClassification}），适配器碰它们一次就多一个"谁说了算"的歧义。
 *
 * <p>★ 关于 {@code jsonb} 列（{@code references}）的读写：本类<strong>不碰</strong>它。 {@link
 * Message#bindReferences} 把契约的 {@code SourceRef} 列表放在领域对象上， 由 JPA 的 JSON 映射（{@code
 * Message.references} 上的 {@code @JdbcTypeCode(SqlTypes.JSON)}） 原样写进 {@code jsonb} 列、原样读回来。★
 * 这里<strong>不做</strong>任何字段筛选 / 改名 / 排序： 快照的意义就是"当时是什么样，现在还是什么样"。
 */
@Component
public class MessageRepositoryAdapter implements MessageRepository {

  private final JpaMessageRepository jpa;

  public MessageRepositoryAdapter(JpaMessageRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Message save(Message message) {
    return jpa.save(message);
  }

  @Override
  public Optional<Message> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<Message> listByConversation(UUID conversationId) {
    return jpa.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
  }

  /**
   * ★★ 常规分页：一次 {@code Page} 读拿到"内容 + 总数"。
   *
   * <p>★★ 偏移 → 页码的换算是 {@code page = offset / limit}，而 {@code PageRequest} 读的是 {@code pageNumber *
   * pageSize} ⇒ 两者**互逆**，所以这里必须整除着用： 调用方传的 {@code offset} 恒为 {@code (page-1) * size}（{@link
   * cn.lincoln.aiplatform.conversation.application.ChatHistoryService} 只在这一个形状上传参）。
   *
   * <p>★ <strong>本批实测踩到的一条记录（第 3 轮）：不要用"最后一页应当有 size 条"这种断言</strong> —— 5 条消息、{@code size=2}
   * 时最后一页只有 **1** 条（偏移 4 ⇒ 只剩第 5 条）， 而我当时把 1 条当成"少回一条"的 bug 查了三轮。★ 判据要写"这一页**应当**是哪几条"，不是"应当有几条"。
   */
  @Override
  public Slice slice(UUID conversationId, int offset, int limit) {
    int safeLimit = Math.max(1, limit);
    int safeOffset = Math.max(0, offset);
    Page<Message> page =
        jpa.findByConversationId(
            conversationId,
            PageRequest.of(
                safeOffset / safeLimit,
                safeLimit,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
    return new Slice(page.getContent(), page.getTotalElements());
  }

  /**
   * ★★ {@code around} 窗口：目标 + 前后各 {@code radius} 条，**按对话顺序升序**。
   *
   * <p>★★ <strong>本批实测的一条取舍（第 3、4 轮各踩一次）</strong>：这一档我先写成两条派生查询 （{@code …CreatedAtLessThan…} /
   * {@code …CreatedAtGreaterThan…}），**两次都只拿回目标一条** ——
   * 派生查询在这个形状上没有给出我要的结果，而"窗口里少了前后文"**不报错**，只是用户看到的历史短了。 ⇒
   * 改成<strong>在这条会话的消息列表上取窗口</strong>：读法与分页档同源 （同一条 {@code
   * findByConversationIdOrderByCreatedAtAscIdAsc}），正确性一眼可核； 代价是一次会话内读取，而这一档的窗口本来就限定在一条会话里。
   *
   * <p>★★ <strong>两条语义由本方法负责</strong>（见 {@link MessageRepository#windowAround} 的注释）：
   *
   * <ol>
   *   <li>先确认目标<strong>属于这个会话</strong>（在列表里找不到就是不属于）—— 只按消息 id 找会让调用方越过会话边界；
   *   <li>窗口<strong>以目标为锚</strong>取下标区间 {@code [i-radius, i+radius]} 并夹到列表边界 ⇒
   *       同刻多条消息也不会把目标挤出窗口（它们是同一份有序列表里的相邻元素）。
   * </ol>
   */
  @Override
  public Optional<Window> windowAround(UUID conversationId, UUID targetMessageId, int radius) {
    if (conversationId == null || targetMessageId == null) {
      return Optional.empty();
    }
    List<Message> all = jpa.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
    int anchor = -1;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id().equals(targetMessageId)) {
        anchor = i;
        break;
      }
    }
    if (anchor < 0) {
      // ★ 目标不在这个会话里 ⇒ 由调用方翻成 404（契约逐字："不是静默回退到第一页"）
      return Optional.empty();
    }
    int span = Math.max(0, radius);
    int from = Math.max(0, anchor - span);
    int to = Math.min(all.size(), anchor + span + 1);
    // ★★ total 给的是**会话总数**（`all.size()`），不是窗口条数 —— mock 那侧逐字如此，
    //    而前端拿它判断"窗口前还有没有更早的一页"（只给窗口条数 ⇒ 深链后"加载更早"消失且不报错）。
    return Optional.of(new Window(List.copyOf(all.subList(from, to)), all.size()));
  }
}
