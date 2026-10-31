package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.AuditQueryPort;
import cn.lincoln.aiplatform.platform.domain.AuditRecordView;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 审计的查询实现（`#258`）。
 *
 * <p>★ 只用 {@code select}，而且**不给调用方任何拼 SQL 的机会**：过滤条件的列名是写死的、 值一律走占位符 ——
 * 审计表里最不该出现的就是"注入"，因为它记的是别人做的事。
 *
 * <p>★ 分页在库里做（{@code limit/offset}），不在内存里切：审计表的行数只增不减， 先全查再切在几个月后就会变成一次全表扫描。
 */
@Component
public class AuditQueryAdapter implements AuditQueryPort {

  private static final String COLUMNS =
      "id, created_at, actor_id, actor_name, action, resource_type, resource_id,"
          + " classification_level, clearance_at_action, detail::text as detail, result";

  private final JdbcTemplate jdbc;

  public AuditQueryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Result page(Query query) {
    List<Object> args = new ArrayList<>();
    String where = where(query, args);

    Long total =
        jdbc.queryForObject(
            "select count(*) from pf_audit_log" + where, Long.class, args.toArray());

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(query.size());
    pageArgs.add((long) (query.page() - 1) * query.size());
    List<AuditRecordView> rows =
        jdbc.query(
            "select "
                + COLUMNS
                + " from pf_audit_log"
                + where
                + " order by created_at desc, id desc limit ? offset ?",
            ROW_MAPPER,
            pageArgs.toArray());

    return new Result(rows, total == null ? 0 : total);
  }

  @Override
  public List<AuditRecordView> byResource(String resourceType, String resourceId) {
    return jdbc.query(
        "select "
            + COLUMNS
            + " from pf_audit_log where resource_type = ? and resource_id = ?"
            + " order by created_at desc, id desc",
        ROW_MAPPER,
        resourceType,
        resourceId);
  }

  /**
   * 拼 where 子句与参数。
   *
   * <p>★ {@code to} 是**不含**的（半开区间）：否则"查 10 月 1 日到 10 月 2 日"会把 10 月 2 日零点那一秒算进两天。
   *
   * <p>★ 时间给的是 {@link Timestamp#from}：审计表的列是 {@code timestamptz}，用字符串传会让时区解释权落到数据库上。
   */
  private static String where(Query query, List<Object> args) {
    StringBuilder sb = new StringBuilder();
    and(sb, args, "action = ?", query.action());
    and(sb, args, "resource_type = ?", query.resourceType());
    and(sb, args, "resource_id = ?", query.resourceId());
    and(sb, args, "actor_id = ?", query.actorId());
    if (query.from() != null) {
      and(sb, args, "created_at >= ?", Timestamp.from(query.from()));
    }
    if (query.to() != null) {
      and(sb, args, "created_at < ?", Timestamp.from(query.to()));
    }
    return sb.toString();
  }

  private static void and(StringBuilder sb, List<Object> args, String clause, Object value) {
    if (value == null || (value instanceof String s && s.isBlank())) {
      return;
    }
    sb.append(sb.isEmpty() ? " where " : " and ").append(clause);
    args.add(value);
  }

  private static final RowMapper<AuditRecordView> ROW_MAPPER =
      (rs, rowNum) ->
          new AuditRecordView(
              rs.getObject("id", UUID.class),
              rs.getTimestamp("created_at").toInstant(),
              rs.getObject("actor_id", UUID.class),
              rs.getString("actor_name"),
              rs.getString("action"),
              rs.getString("resource_type"),
              rs.getString("resource_id"),
              rs.getString("classification_level"),
              rs.getString("clearance_at_action"),
              rs.getString("detail"),
              rs.getString("result"));
}
