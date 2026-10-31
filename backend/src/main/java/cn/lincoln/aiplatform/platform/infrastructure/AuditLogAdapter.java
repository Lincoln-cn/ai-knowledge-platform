package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.AuditWriteFailedException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计留痕的 JDBC 实现（`#257`）：一条 INSERT，与调用方同一个事务。
 *
 * <p>★ <strong>actor_name 在写入时刻取一次快照</strong>：审计要回答的是"当时是谁"， 而 {@code iam_user}
 * 里的名字会随改名而变。这里查不到用户（或用户已被删）时 记 {@code null} 而不是让插入失败 —— 少一个名字，仍要留下"有人做了这件事"。
 */
@Component
public class AuditLogAdapter implements AuditLogPort {

  private static final Logger log = LoggerFactory.getLogger(AuditLogAdapter.class);

  private static final String INSERT =
      """
      insert into pf_audit_log
        (id, actor_id, actor_name, action, resource_type, resource_id, detail, created_at)
      values (?, ?, ?, ?, ?, ?, ?::jsonb, now())
      """;

  private static final String ACTOR_NAME = "select display_name from iam_user where id = ?";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public AuditLogAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void record(
      AuditAction action,
      String resourceType,
      String resourceId,
      UUID actorId,
      Map<String, Object> detail) {
    String detailJson = toJson(detail);
    try {
      jdbc.update(
          INSERT,
          UUID.randomUUID(),
          actorId,
          actorName(actorId),
          action.code(),
          resourceType,
          resourceId,
          detailJson);
    } catch (DataAccessException failure) {
      // ★ 换成专门的类型再抛：数据访问异常在全局处理器里是"兜底 500"，但日志与错误里看不到这件事
      //   与审计有关。包装一层带上动作名，现场能直接看出"是审计这条写入失败了"。
      //   ★ 不在这里 catch 后放过 —— 调用方事务必须跟着回滚（#257 判据 ④）。
      throw new AuditWriteFailedException(
          "审计写入失败（" + action.code() + " " + resourceType + "/" + resourceId + "）", failure);
    }
  }

  private String actorName(UUID actorId) {
    if (actorId == null) {
      return null;
    }
    try {
      return jdbc.queryForObject(ACTOR_NAME, String.class, actorId);
    } catch (RuntimeException notFoundOrDbDown) {
      // ★ 查不到名字不影响留痕本身：这是一次"补全信息"的读，不是审计主体。
      //   但也不能当作正常 —— 留一条 warn，别让它变成下一次"库不可达却没人知道"。
      log.warn("审计写入了 actor_id，但查不到对应的 display_name：{}", actorId, notFoundOrDbDown);
      return null;
    }
  }

  private String toJson(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    } catch (JacksonException serializationFailure) {
      // ★ 不许静默丢 detail：把它降级成一个可读说明，至少留下"这条审计原本有 detail"的痕迹。
      //   ★ 本项目是 Jackson 3（包名 tools.jackson），JacksonException 是 unchecked，且没有
      //     getOriginalMessage()（那是 Jackson 2 的 JsonProcessingException 才有的）。
      log.warn("审计 detail 序列化失败，改为记录错误说明", serializationFailure);
      return "{\"detailSerializationFailed\":\""
          + String.valueOf(serializationFailure.getMessage()).replace('"', '\'')
          + "\"}";
    }
  }
}
