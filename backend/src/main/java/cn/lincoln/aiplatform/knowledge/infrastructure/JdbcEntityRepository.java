package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 实体库的 JDBC 实现。
 *
 * <p>★ 全部走 {@code on conflict do nothing}：一段文档重新解析时会再抽一遍，同库同名实体 不该因此变成两行 —— 也不能指望调用方"记得先查一下"。
 *
 * <p>★ 唯一约束用的是 {@code uq_kb_entity_code}（只在 {@code code is not null} 时生效）， 而抽取出来的实体**没有 code** ⇒
 * 幂等性改用 `where not exists` 实现。这一点必须写清楚： 以为有索引兜底而实际没有，是"静默重复"的典型来路。
 */
@Component
public class JdbcEntityRepository implements EntityRepository {

  private final JdbcTemplate jdbc;

  public JdbcEntityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int insertAll(UUID kbId, List<Candidate> candidates) {
    if (kbId == null || candidates == null || candidates.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    for (Candidate c : candidates) {
      if (c == null || c.name() == null || c.name().isBlank() || c.type() == null) {
        continue;
      }
      inserted +=
          jdbc.update(
              // ★ 幂等靠 where not exists：抽取出的实体 code 为 null，uq_kb_entity_code 不生效
              "insert into kb_entity (kb_id, type, name, source, confidence) "
                  + "select ?, ?, ?, ?, ? where not exists ("
                  + "  select 1 from kb_entity e "
                  + "  where e.kb_id = ? and e.type = ? and e.name = ? and e.deleted_at is null)",
              kbId,
              c.type(),
              c.name(),
              c.source(),
              BigDecimal.valueOf(c.confidence()),
              kbId,
              c.type(),
              c.name());
    }
    return inserted;
  }

  @Override
  public Optional<UUID> findIdByName(UUID kbId, String type, String name) {
    if (kbId == null || type == null || name == null) {
      return Optional.empty();
    }
    List<UUID> ids =
        jdbc.queryForList(
            "select id from kb_entity "
                + "where kb_id = ? and type = ? and name = ? and deleted_at is null",
            UUID.class,
            kbId,
            type,
            name);
    return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
  }

  @Override
  public long countOf(UUID kbId) {
    if (kbId == null) {
      return 0;
    }
    Long n =
        jdbc.queryForObject(
            "select count(*) from kb_entity where kb_id = ? and deleted_at is null",
            Long.class,
            kbId);
    return n == null ? 0 : n;
  }
}
