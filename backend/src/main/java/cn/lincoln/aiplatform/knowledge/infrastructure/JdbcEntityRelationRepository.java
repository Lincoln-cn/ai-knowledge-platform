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

  /** 默认一次列多少条关系（图谱一次画几千条边已经读不动了）。 */
  private static final int DEFAULT_LIST_LIMIT = 2000;

  /** 关系列表的硬上限：这个口子是给人看图的，不是导出工具。 */
  private static final int MAX_LIST_LIMIT = 5000;

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
  public int deleteByDocument(UUID kbId, UUID documentId) {
    if (kbId == null || documentId == null) {
      // 没有文档标识就不动手：删"这个库的全部边"会把别的文档的成果一起清掉。
      return 0;
    }
    return jdbc.update(
        "delete from kb_relation where kb_id = ? and document_id = ?", kbId, documentId);
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

  /**
   * 列一个库里的关系（看图用），join 两端实体取名字与类型。
   *
   * <p>★ 上限写死两级（默认 2000 / 封顶 5000）：这个口子是给人看图的，不是导出工具。 真到了要导出的量级，需要的是分页与筛选，而不是把上限调大。
   */
  @Override
  public List<RelationRow> listOf(UUID kbId, int limit) {
    if (kbId == null) {
      return List.of();
    }
    int capped = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
    return jdbc.query(
        """
        select r.id, r.type, r.from_entity, r.to_entity, r.document_id, r.segment_id, r.confidence,
               f.name as from_name, f.type as from_type,
               t.name as to_name, t.type as to_type
          from kb_relation r
          join kb_entity f on f.id = r.from_entity
          join kb_entity t on t.id = r.to_entity
         where r.kb_id = ?
         order by r.created_at desc, r.id
         limit ?
        """,
        (rs, i) ->
            new RelationRow(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getObject("from_entity", UUID.class),
                rs.getString("from_name"),
                rs.getString("from_type"),
                rs.getObject("to_entity", UUID.class),
                rs.getString("to_name"),
                rs.getString("to_type"),
                rs.getObject("document_id", UUID.class),
                rs.getObject("segment_id", UUID.class),
                rs.getDouble("confidence")),
        kbId,
        capped);
  }

  @Override
  public List<RelationRow> rowsOfEntities(UUID kbId, Collection<UUID> entityIds, int limit) {
    if (kbId == null || entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    int capped = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
    String placeholders = String.join(", ", java.util.Collections.nCopies(entityIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(kbId);
    args.addAll(entityIds);
    args.addAll(entityIds);
    args.add(capped);
    return jdbc.query(
        """
        select r.id, r.type, r.from_entity, r.to_entity, r.document_id, r.segment_id, r.confidence,
               f.name as from_name, f.type as from_type,
               t.name as to_name, t.type as to_type
          from kb_relation r
          join kb_entity f on f.id = r.from_entity
          join kb_entity t on t.id = r.to_entity
         where r.kb_id = ?
           and (r.from_entity in (%s) or r.to_entity in (%s))
         order by r.created_at desc, r.id
         limit ?
        """
            .formatted(placeholders, placeholders),
        (rs, i) ->
            new RelationRow(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getObject("from_entity", UUID.class),
                rs.getString("from_name"),
                rs.getString("from_type"),
                rs.getObject("to_entity", UUID.class),
                rs.getString("to_name"),
                rs.getString("to_type"),
                rs.getObject("document_id", UUID.class),
                rs.getObject("segment_id", UUID.class),
                rs.getDouble("confidence")),
        args.toArray());
  }

  @Override
  public List<UUID> neighborEntityIds(UUID kbId, Collection<UUID> entityIds) {
    if (kbId == null || entityIds == null || entityIds.isEmpty()) {
      return List.of();
    }
    // ★ 两端都要取：实体可能出现在 from_entity 也可能出现在 to_entity，
    //   只取一端会漏掉一半的邻居。
    String placeholders = String.join(", ", java.util.Collections.nCopies(entityIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(kbId);
    args.addAll(entityIds);
    args.add(kbId);
    args.addAll(entityIds);
    List<UUID> rows =
        jdbc.query(
            """
            select to_entity as neighbor
              from kb_relation
             where kb_id = ?
               and from_entity in (%s)
            union
            select from_entity as neighbor
              from kb_relation
             where kb_id = ?
               and to_entity in (%s)
            """
                .formatted(placeholders, placeholders),
            (rs, i) -> rs.getObject(1, UUID.class),
            args.toArray());
    // ★ 去掉起点自己：A 与 B 有关系时，A 也会作为一个"另一端"被查出来，
    //   那不是邻居，把它留在集合里只会让第二跳原地打转。
    Set<UUID> ordered = new LinkedHashSet<>(rows);
    ordered.removeAll(entityIds);
    return new ArrayList<>(ordered);
  }
}
