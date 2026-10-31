package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.platform.access.UserDisplayNamePort;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link UserDisplayNamePort} 的实现：一条 {@code in (…)} 读 {@code iam_user.display_name}。
 *
 * <p>★ 读法来自本项目另外两处（{@code FeedbackService#assigneeName} 与 {@code AuditLogAdapter} 的 {@code
 * ACTOR_NAME}）—— 都是 {@code iam_user.display_name}，这里只是把单条换成批量。<strong>别为一个"显示名字"新开一个 REST
 * 端点</strong>：那会让前端为了一个人名去要 {@code system:manage}，而调用方的门槛通常低得多（发布历史只要 {@code assistant:view}）。
 *
 * <p>★ {@code deleted_at is null} 与那两处一致：软删的用户不再算"能解析到的人"。**不写这个谓词**，页面上就会出现
 * "一个已经离职、账号已被删掉的人"仍然显示着名字 —— 而那是用户明确要求"删掉"的信息。
 *
 * <h3>★ 兜底在这里，且只在这里</h3>
 *
 * <p>整个 {@code query} 包在 {@code catch (RuntimeException)} 里，失败只记 {@code
 * warn}：本方法的产出只影响"名字这一列好看不好看"， 让一次用户表查询的抖动把整页业务列表变成 500，等于用装饰性信息换掉主信息。
 * 调用方因此<strong>不需要</strong>再兜一层 —— 兜底唯一的那个 {@code catch} 才能被"故意去掉它 ⇒ 断言变红"证伪；两处都兜，去掉任何一处都不会红。
 *
 * <p>★ 失败时返回的是<strong>已经取到的那些</strong>而不是空表：JDBC 逐行回调，一个中途断掉的连接完全可能已经把前几行读出来了。
 * 扔掉它们等于让"能给的那么多"变成"一点都不给"。
 */
@Component
public class UserDisplayNameAdapter implements UserDisplayNamePort {

  private static final Logger log = LoggerFactory.getLogger(UserDisplayNameAdapter.class);

  /**
   * 一次 {@code in (…)} 取这一批 id 的<strong>当前</strong>显示名。
   *
   * <p>★ 空的 {@code display_name} 由 {@link #displayNamesOf} 过滤掉，不在这里判：SQL 只负责"取到行"， "什么算解析到了"
   * 是一条语义判断，放在能写注释的 Java 里比塞进 {@code where} 更好读。
   */
  private static final String DISPLAY_NAMES =
      "select id, display_name from iam_user where id in (:ids) and deleted_at is null";

  private final NamedParameterJdbcTemplate jdbc;

  public UserDisplayNameAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, String> displayNamesOf(Collection<UUID> ids) {
    List<UUID> wanted =
        ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    if (wanted.isEmpty()) {
      return Map.of();
    }

    Map<UUID, String> names = new HashMap<>();
    try {
      jdbc.query(
          DISPLAY_NAMES,
          Map.of("ids", wanted),
          rs -> {
            String name = rs.getString("display_name");
            if (name != null && !name.isBlank()) {
              names.put(rs.getObject("id", UUID.class), name);
            }
          });
    } catch (RuntimeException lookupFailed) {
      log.warn(
          "用户显示名解析失败，本次按「解析不到」返回：待解析 {} 个 id，已取到 {} 个", wanted.size(), names.size(), lookupFailed);
    }
    return names;
  }
}
