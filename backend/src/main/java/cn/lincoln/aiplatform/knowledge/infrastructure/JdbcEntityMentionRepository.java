package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link EntityMentionRepository} 的 JDBC 实现。 */
@Repository
public class JdbcEntityMentionRepository implements EntityMentionRepository {

  private final JdbcTemplate jdbc;

  public JdbcEntityMentionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public int insertAll(List<Mention> mentions) {
    if (mentions == null || mentions.isEmpty()) {
      return 0;
    }
    // ★ on conflict do nothing 而不是先查再插：重新解析会再抽一遍，
    //   靠调用方"记得先查一下"迟早会漏。
    int[][] counts =
        jdbc.batchUpdate(
            "insert into kb_entity_mention (entity_id, kb_id, document_id, segment_id)"
                + " values (?, ?, ?, ?) on conflict (entity_id, segment_id) do nothing",
            mentions,
            mentions.size(),
            (ps, m) -> {
              ps.setObject(1, m.entityId());
              ps.setObject(2, m.kbId());
              ps.setObject(3, m.documentId());
              ps.setObject(4, m.segmentId());
            });
    int inserted = 0;
    for (int[] row : counts) {
      for (int n : row) {
        // do nothing 时返回 0，真插入返回 1
        if (n > 0) {
          inserted += n;
        }
      }
    }
    return inserted;
  }

  @Override
  public List<UUID> segmentIdsOf(UUID entityId) {
    if (entityId == null) {
      return List.of();
    }
    return jdbc.query(
        "select segment_id from kb_entity_mention where entity_id = ? order by segment_id",
        (rs, i) -> rs.getObject(1, UUID.class),
        entityId);
  }
}
