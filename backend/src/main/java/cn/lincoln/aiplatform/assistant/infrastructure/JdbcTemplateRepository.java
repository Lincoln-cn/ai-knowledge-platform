package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Template;
import cn.lincoln.aiplatform.assistant.domain.TemplateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TemplateRepository} 的 JDBC 实现（{@code #295}）。
 *
 * <h3>★ 为什么用 JDBC 而不是 JPA</h3>
 *
 * <p>模板的 {@code payload} 是一棵"一次写完、整棵读出"的配置树（jsonb）。用 JPA 的话， 要么给它建一个 {@code @ElementCollection}
 * 的扁平表（那是把快照拆碎，正好违反"模板是不可变快照"）， 要么挂一个 {@code @JdbcTypeCode(SqlTypes.JSON)} 的自定义类型 —— 而模块边界闸门也倾向少开一个
 * JPA 实体。这里就一张表、五个动作，JDBC 更直白。
 *
 * <h3>★ payload 存成 jsonb 而不是 text</h3>
 *
 * <p>为了将来能按 payload 里的字段查（比如"哪些模板默认开了多跳"）。真存 text 就只能全表读出来
 * 在内存里筛，那个退化在模板数量上不明显、但在"按配置特点找模板"这个需求出现时是硬伤。
 */
@Repository
public class JdbcTemplateRepository implements TemplateRepository {

  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

  private static final String SELECT_COLUMNS =
      "id, name, description, category, source_app_id, payload, builtin,"
          + " created_at, created_by, updated_at, updated_by";

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  private final RowMapper<Template> rowMapper =
      (rs, rowNum) ->
          new Template(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              rs.getString("description"),
              rs.getString("category"),
              rs.getObject("source_app_id", UUID.class),
              readPayload(rs.getString("payload")),
              rs.getBoolean("builtin"),
              toInstant(rs.getTimestamp("created_at")),
              rs.getObject("created_by", UUID.class),
              toInstant(rs.getTimestamp("updated_at")),
              rs.getObject("updated_by", UUID.class));

  public JdbcTemplateRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<Template> listLive() {
    return jdbc.query(
        "select "
            + SELECT_COLUMNS
            + " from as_template where deleted_at is null"
            + " order by category, name, id",
        rowMapper);
  }

  @Override
  public Optional<Template> findLive(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return jdbc
        .query(
            "select " + SELECT_COLUMNS + " from as_template where id = ? and deleted_at is null",
            rowMapper,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public boolean nameTaken(String name, UUID excludeId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from as_template where deleted_at is null and name = ?"
                + " and (?::uuid is null or id <> ?::uuid)",
            Integer.class,
            name,
            excludeId == null ? null : excludeId.toString(),
            excludeId == null ? null : excludeId.toString());
    return count != null && count > 0;
  }

  @Override
  @Transactional
  public Template save(Template template) {
    String payloadJson = writePayload(template.payload());
    Timestamp created = toTimestamp(template.createdAt());
    Timestamp updated =
        toTimestamp(template.updatedAt() == null ? Instant.now() : template.updatedAt());
    jdbc.update(
        "insert into as_template ("
            + SELECT_COLUMNS
            + ") values"
            + " (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)"
            + " on conflict (id) do update set"
            + "   name = excluded.name,"
            + "   description = excluded.description,"
            + "   category = excluded.category,"
            + "   source_app_id = excluded.source_app_id,"
            + "   payload = excluded.payload,"
            + "   builtin = excluded.builtin,"
            + "   updated_at = excluded.updated_at,"
            + "   updated_by = excluded.updated_by",
        template.id(),
        template.name(),
        template.description(),
        template.category(),
        template.sourceAppId(),
        payloadJson,
        template.builtin(),
        created,
        template.createdBy(),
        updated,
        template.updatedBy());
    return template;
  }

  @Override
  @Transactional
  public void markDeleted(UUID id, UUID updatedBy) {
    jdbc.update(
        "update as_template set deleted_at = now(), updated_at = now(), updated_by = ?"
            + " where id = ? and deleted_at is null",
        updatedBy,
        id);
  }

  // ── payload 与时间的有序转换（两个方向各自集中在一处，改序列化只改这里） ──

  private Map<String, Object> readPayload(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(raw, PAYLOAD_TYPE);
    } catch (Exception e) {
      // ★ 不吞：payload 读不出来意味着这一行是坏的，静默返回空 map 会让"模板看起来还在、克隆出来是空的"
      throw new IllegalStateException("模板 payload 不是合法 JSON（该行数据已损坏）", e);
    }
  }

  private String writePayload(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception e) {
      throw new IllegalArgumentException("模板 payload 无法序列化为 JSON", e);
    }
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
