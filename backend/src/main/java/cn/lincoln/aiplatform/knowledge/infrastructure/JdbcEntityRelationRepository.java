package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link EntityRelationRepository} 的 JDBC 实现。 */
@Repository
public class JdbcEntityRelationRepository implements EntityRelationRepository {

  private final JdbcTemplate jdbc;

  public JdbcEntityRelationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int insertAll(UUID kbId, List<Candidate> relations) {
    if (relations == null || relations.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    for (Candidate c : relations) {
      // 用 insert ... on conflict do nothing 而不是 where not exists：
      // 这里已经有一张带唯一约束的表，让数据库判定比在应用里查一次再插更可靠
      // （两个并发的解析任务会同时查到"不存在"）。
      inserted +=
          jdbc.update(
              """
              insert into kb_relation
                  (kb_id, from_entity, to_entity, type, document_id, segment_id, confidence)
              values (?, ?, ?, ?, ?, ?, ?)
              on conflict (from_entity, to_entity, type) do nothing
              """,
              kbId,
              c.fromEntity(),
              c.toEntity(),
              c.type(),
              c.documentId(),
              c.segmentId(),
              c.confidence());
    }
    return inserted;
  }
}
