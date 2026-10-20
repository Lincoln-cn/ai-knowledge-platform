package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Message;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的消息仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code MessageRepository} —— 双继承会让 {@code Pageable} / {@code
 * Specification} 这些框架类型漏进领域层（与 {@code JpaConversationRepository} / {@code
 * JpaKnowledgeBaseRepository} 同一条判断）。
 */
public interface JpaMessageRepository extends JpaRepository<Message, UUID> {

  /**
   * 某个会话的全部消息，按创建时间升序（对话的自然顺序）。
   *
   * <p>★ {@code CreatedAtAsc} 与 {@code V33} 的 {@code idx_msg_conv (conversation_id, created_at)}
   * <strong>同一个列序</strong>：这个索引就是为了这一条查询建的，列序反了它就白建。
   *
   * <p>★ 同秒创建的两条消息之间没有稳定顺序 —— 所以再按 {@code id} 兜一道，让返回顺序在"时间相同"时 也是确定的（否则测试会偶发地飘）。
   */
  List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

  /**
   * 分页读（登录面历史消息端点的常规档）。
   *
   * <p>★ 用 {@code Page} 一次拿到"内容 + 总数"：分两次问会出现"这中间又落了一条消息"的窗口 ⇒ {@code total} 与页内容不同口径。
   *
   * <p>★ 排序由调用方经 {@code Pageable} 给（{@code createdAt asc, id asc}）—— 与上面那条**同源**。
   */
  Page<Message> findByConversationId(UUID conversationId, Pageable pageable);

  /**
   * ★ 行锁读：读时收敛陈旧 {@code GENERATING} 前先锁住这一行，避免把并发收尾刚写好的答案覆盖成 {@code FAILED}（{@code #182}）。
   *
   * <p>★ 锁的是 {@code cv_message} 的单行；收尾线程与读历史都走同一条行，后到者会等待并看到先到者写的终态。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Message m where m.id = :id")
  Optional<Message> findByIdForUpdate(@Param("id") UUID id);
}
