package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowPause;
import cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 中断快照的 JDBC 实现。
 *
 * <p>★ `state` 列是 `jsonb`，存的是 {@code FlowState} 的键值对。这里自己序列化而不是引 JPA： 整张表就两行 SQL，而 JPA 映射一个
 * `Map<String,String>` 要一个 converter + 一个实体类，比这两行 SQL 更容易出错。
 */
@Repository
public class JdbcWorkflowPauseRepository implements WorkflowPauseRepository {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;

  public JdbcWorkflowPauseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(WorkflowPause pause) {
    // ★ on conflict do update 而不是 insert：恢复失败重试时同一个执行会被再暂停一次，
    //   而它仍是「同一次执行」—— 换一行新的会让审计里出现两次中断。
    jdbc.update(
        """
        insert into as_workflow_pause
          (execution_id, assistant_id, node_id, node_type, state, steps_done, created_at, resumed_at)
        values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
        on conflict (execution_id) do update set
          node_id = excluded.node_id,
          node_type = excluded.node_type,
          state = excluded.state,
          steps_done = excluded.steps_done,
          resumed_at = excluded.resumed_at
        """,
        pause.executionId(),
        pause.assistantId(),
        pause.nodeId(),
        pause.nodeType(),
        writeState(pause.state()),
        pause.stepsDone(),
        java.sql.Timestamp.from(pause.createdAt()),
        pause.resumedAt() == null ? null : java.sql.Timestamp.from(pause.resumedAt()));
  }

  @Override
  public Optional<WorkflowPause> find(UUID executionId) {
    return jdbc
        .query(
            """
            select execution_id, assistant_id, node_id, node_type, state, steps_done,
                   created_at, resumed_at
            from as_workflow_pause where execution_id = ?
            """,
            (rs, rowNum) -> map(rs),
            executionId)
        .stream()
        .findFirst();
  }

  private static WorkflowPause map(ResultSet rs) throws SQLException {
    java.sql.Timestamp resumed = rs.getTimestamp("resumed_at");
    return new WorkflowPause(
        rs.getObject("execution_id", UUID.class),
        rs.getObject("assistant_id", UUID.class),
        rs.getString("node_id"),
        rs.getString("node_type"),
        readState(rs.getString("state")),
        rs.getInt("steps_done"),
        rs.getTimestamp("created_at").toInstant(),
        resumed == null ? null : resumed.toInstant());
  }

  private static String writeState(Map<String, String> state) {
    return MAPPER.writeValueAsString(state);
  }

  /**
   * 读回变量表。
   *
   * <p>★ 空串/空对象都当空表，不抛异常：快照是「恢复时的起点」，而一张没有变量的起点仍然可以继续跑（
   * 比如中断点在第一个节点之后、什么都没写）。抛异常会让「能恢复」变成「恢复不了且不知道为什么」。
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> readState(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    Map<String, Object> raw = MAPPER.readValue(json, Map.class);
    Map<String, String> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
    return out;
  }

  /** 便于测试断言的构造入口：不传 {@code createdAt} 就是现在。 */
  static Instant now() {
    return Instant.now();
  }
}
