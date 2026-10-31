package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.watermark.ConversationWatermarkPort;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConversationWatermarkPort} 的实现：按引用快照反查会话，再逐条走既有的单调抬升。
 *
 * <h3>★ 为什么反查用 {@code reference_snapshot} 而不是"会话 → 文档"的关系表</h3>
 *
 * <p>没有那张表，也不该为这件事建：引用是<b>那一刻检索到了什么</b>的快照（{@code SourceRef} 十个字段原样落在 {@code
 * cv_message.reference_snapshot} 里，含 {@code documentId}），而"某文档被哪些会话引用过"是可以从快照算出来的派生问题。
 * 为派生问题建一张会在两处更新的表，正是 {@code #40} 那种"同一状态四处各写一份"的形状。
 *
 * <p>★ 抬升本身<strong>不在这里算</strong>：规则（只升/不降/相等幂等）与那句 SQL 都在 {@link
 * ConversationRepository#raiseClassification} 里（{@code #108} 的择一 (a)）。本类只做"找出该抬哪些会话"，不做第二次比较 ——
 * 在调用方比一次就多一处会漂的口径。
 */
@Component
public class ConversationWatermarkAdapter implements ConversationWatermarkPort {

  private static final Logger log = LoggerFactory.getLogger(ConversationWatermarkAdapter.class);

  /**
   * 反查：出现在任一消息引用快照里的会话。
   *
   * <p>★ {@code reference_snapshot is not null} 必须显式写：{@code jsonb_array_elements(null)} 会让整条 SQL
   * 返回零行（不是报错），而"零行"与"没有引用"在这里恰好都表现成"不用补偿" —— 一个写错的查询会安静地通过。 显式排除 null 之后，剩下的 {@code []} 会被 {@code
   * array_elements} 展开成零行，语义仍然正确。
   *
   * <p>★ {@code e->>'documentId'} 用的是契约字段名（{@code SourceRef.documentId}），不是列名 —— 快照里存的是
   * 序列化后的对象，键名就是 record 的组件名。
   */
  private static final String IDS_REFERENCING_DOCUMENT =
      """
      select distinct m.conversation_id
        from cv_message m
        cross join lateral jsonb_array_elements(m.reference_snapshot) e
       where m.reference_snapshot is not null
         and e->>'documentId' = ?
      """;

  private final JdbcTemplate jdbc;
  private final ConversationRepository conversations;

  public ConversationWatermarkAdapter(JdbcTemplate jdbc, ConversationRepository conversations) {
    this.jdbc = jdbc;
    this.conversations = conversations;
  }

  @Override
  @Transactional
  public int raiseForDocument(UUID documentId, ClassificationLevel refsMax) {
    if (documentId == null || refsMax == null) {
      return 0;
    }
    List<UUID> ids = jdbc.queryForList(IDS_REFERENCING_DOCUMENT, UUID.class, documentId.toString());
    int raised = 0;
    for (UUID id : ids) {
      if (conversations.raiseClassification(id, refsMax)) {
        raised++;
      }
    }
    log.info("文档 {} 升密到 {}：引用过它的 {} 个会话里，{} 个水位被抬高", documentId, refsMax, ids.size(), raised);
    return raised;
  }
}
