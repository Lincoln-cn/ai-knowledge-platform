package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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

  /** 分页缺省与上限：一页 50 行是"看得清又不卡"的经验值，500 是"再怎么翻也不该一次给超过这个数"。 */
  private static final int DEFAULT_PAGE_SIZE = 50;

  private static final int MAX_PAGE_SIZE = 500;

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
  public List<Entity> listOf(UUID kbId) {
    if (kbId == null) {
      return List.of();
    }
    return jdbc.query(
        "select id, type, name, source, confidence from kb_entity"
            + " where kb_id = ? and deleted_at is null and merged_into is null"
            + " order by type, name",
        (rs, i) ->
            new Entity(
                rs.getObject(1, java.util.UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getDouble(5)),
        kbId);
  }

  @Override
  public List<Entity> pageOf(UUID kbId, String type, String keyword, int offset, int limit) {
    if (kbId == null) {
      return List.of();
    }
    int size = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
    int skip = Math.max(offset, 0);
    // ★ 排序按"被多少段提到"降序，不是按类型名。按类型名排的第一页会全是 1 段提到、
    // 0 条关系的文书（DOC 在字母序最前），那是这一页最难看的开头 —— 分页器翻十页
    // 也翻不到真正重要的东西。
    StringBuilder sql =
        new StringBuilder(
            "select e.id, e.type, e.name, e.source, e.confidence from kb_entity e"
                + " left join (select entity_id, count(*) as c from kb_entity_mention"
                + "            where kb_id = ? group by entity_id) m on m.entity_id = e.id"
                + " where e.kb_id = ? and e.deleted_at is null and e.merged_into is null");
    List<Object> args = new ArrayList<>();
    args.add(kbId);
    args.add(kbId);
    appendFilters(sql, args, type, keyword);
    sql.append(" order by coalesce(m.c, 0) desc, e.type, e.name offset ? limit ?");
    args.add(skip);
    args.add(size);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Entity(
                rs.getObject(1, java.util.UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getDouble(5)),
        args.toArray());
  }

  @Override
  public long countMatching(UUID kbId, String type, String keyword) {
    if (kbId == null) {
      return 0;
    }
    StringBuilder sql =
        new StringBuilder(
            "select count(*) from kb_entity e"
                + " where e.kb_id = ? and e.deleted_at is null and e.merged_into is null");
    List<Object> args = new ArrayList<>();
    args.add(kbId);
    appendFilters(sql, args, type, keyword);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0 : n;
  }

  /**
   * 过滤条件只写一处。
   *
   * <p>★ 为什么抽出来：{@code pageOf} 与 {@code countMatching} 的过滤条件必须**逐字一致**， 否则会出现"总数说有 3 页、翻到第 3
   * 页是空的"—— 这类分页 bug 只在一部分关键字下复现，最难查。
   */
  private static void appendFilters(
      StringBuilder sql, List<Object> args, String type, String keyword) {
    if (type != null && !type.isBlank()) {
      sql.append(" and e.type = ?");
      args.add(type.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      // ilike：用户搜"公司"要能搜到"公司法"，也要能搜到"中国石油天然气集团公司"。
      sql.append(" and e.name ilike ?");
      args.add("%" + keyword.trim() + "%");
    }
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
