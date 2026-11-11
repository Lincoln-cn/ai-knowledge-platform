package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

  @Override
  public List<UUID> segmentIdsOf(UUID kbId, Collection<UUID> entityIds) {
    if (kbId == null || entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    // ★ 只查"这些实体参与过关系陈述"的段，且要求 segment_id 非空：
    //   存量回填那批边没有段落，返回它们等于把"无法核对"的边当成命中。
    String placeholders = String.join(", ", java.util.Collections.nCopies(entityIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(kbId);
    args.addAll(entityIds);
    List<UUID> rows =
        jdbc.query(
            """
            select distinct segment_id
              from kb_relation
             where kb_id = ?
               and segment_id is not null
               and (from_entity in (%s) or to_entity in (%s))
             order by segment_id
            """
                .formatted(placeholders, placeholders),
            (rs, i) -> rs.getObject(1, UUID.class),
            args.toArray());
    // 两端都命中的边会让同一段出现两次，distinct 已去重；这里再保一层顺序稳定。
    Set<UUID> ordered = new LinkedHashSet<>(rows);
    return new ArrayList<>(ordered);
  }
}
