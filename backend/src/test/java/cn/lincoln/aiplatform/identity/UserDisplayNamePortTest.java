package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.UserDisplayNamePort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@link UserDisplayNamePort} 的真实实现（真栈：真 PG、真迁移、真 {@code iam_user}）。
 *
 * <p>★ 端口契约里有三条"看起来无所谓、错了却会难查"的语义，这里各钉一条：
 *
 * <ol>
 *   <li><strong>只返回能解析到的</strong>：不存在的 id 与被软删的用户都不在返回表里 —— 少了它，页面上会出现"一个已经删掉的人"的名字
 *   <li><strong>不抛</strong>：不存在的 id 是<strong>正常输入</strong>（历史行引用了后来被删的用户）， 它不是错误，不许变成异常
 *   <li><strong>空输入不查库</strong>：{@code null} / 空集合返回空表而不是 {@code null}， 调用方因此不需要判空
 * </ol>
 *
 * <p>★ 这里<strong>不测</strong>"数据库挂了会怎样"：那件事只能靠 {@code NamedParameterJdbcTemplate} 的 mock
 * 来造，而它要断言的是"兜底 catch 在不在"，单元测试 {@code WorkflowVersionQueryServiceTest} 已经用"真实实现 + 一定会抛的
 * jdbc"覆盖了同一段代码，且在真栈里造 DDL 故障属于为测试改形状。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class UserDisplayNamePortTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired private UserDisplayNamePort port;
  @Autowired private JdbcTemplate jdbc;

  /**
   * 一个 id 存在、一个 id 不存在、一个 id 存在但已软删 ⇒ 只返回存在且未删的那条名字，不抛。
   *
   * <p>★ 三个 id 一起传是刻意的：端口是批量的，单条查询"过滤掉了谁"看不出来 —— 批量时才有 as-if 是 IN 列表的语义。
   */
  @Test
  void displayNamesOfReturnsOnlyResolvableIdsAndDoesNotThrow() {
    UUID live = insertUser("显示名-现在在岗", null);
    UUID ghost = UUID.randomUUID();
    UUID removed = insertUser("显示名-已经删了", Instant.now());

    Map<UUID, String> names = port.displayNamesOf(List.of(live, ghost, removed));

    assertThat(names).as("只返回能解析到的：存在的那个").containsEntry(live, "显示名-现在在岗");
    assertThat(names).as("不存在的人不许凭空补一个名字，也不许把 uuid 当名字").doesNotContainKeys(ghost);
    assertThat(names).as("软删的人不算能解析到 —— 用户要求删掉的信息不该再出现").doesNotContainKeys(removed);
    assertThat(names).hasSize(1);
  }

  /** {@code null} / 空集合 / 全是 {@code null} 的集合 ⇒ 空表，且不查库（查了也只是浪费一次往返）。 */
  @Test
  void emptyInputReturnsEmptyMapInsteadOfNull() {
    assertThat(port.displayNamesOf(List.of())).isEmpty();
    assertThat(port.displayNamesOf(null)).as("null 输入也要有返回，不是 NPE").isEmpty();
    assertThat(port.displayNamesOf(Arrays.asList(null, null))).isEmpty();
  }

  /** 集合里混着 {@code null} 时，{@code null} 被忽略，其余照常解析（{@code as_workflow_version.published_by} 可空）。 */
  @Test
  void nullIdsInsideTheCollectionAreIgnored() {
    UUID live = insertUser("显示名-混入空值", null);

    Map<UUID, String> names = port.displayNamesOf(Arrays.asList(live, null));

    assertThat(names).containsOnlyKeys(live);
    assertThat(names).containsEntry(live, "显示名-混入空值");
  }

  private UUID insertUser(String displayName, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    String username = "u" + id.toString().replace("-", "").substring(0, 20);
    jdbc.update(
        "insert into iam_user (id, username, display_name, password_hash, role_id, status,"
            + " created_at, updated_at, deleted_at)"
            + " values (?, ?, ?, 'x', (select id from iam_role where code = 'ADMIN'), 'ACTIVE',"
            + " now(), now(), cast(? as timestamptz))",
        id,
        username,
        displayName,
        deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    return id;
  }
}
