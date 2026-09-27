package cn.lincoln.aiplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 权限点目录的幂等同步（{@code ROADMAP §4.7.3} 第 3 周测试项 ①）。
 *
 * <p>★ "跑两次结果一致"这条要求看起来像形式主义，但它守住的是一个很具体的场景： 同步在**每次应用启动时**执行。如果它不幂等，那么"重启一次多一批脏数据"会累积成 一个没人敢重启的系统
 * —— 而重启恰好是运维最常用的动作。
 *
 * <p>★ 另一组用例验的是"**只加不减**"这个坏习惯：同步若不清理， 库里会留下"代码里已经不存在的权限点 / 角色不该有的授权"， 而角色管理页会把它们渲染成勾了也没用的选项。
 */
@SpringBootTest
@Testcontainers
class PermissionCatalogTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  @Autowired PermissionCatalog catalog;
  @Autowired JdbcTemplate jdbc;

  @Test
  void 同步是幂等的_跑两次结果完全一致() {
    catalog.sync();
    String afterFirst = snapshot();
    catalog.sync();
    String afterSecond = snapshot();

    assertThat(afterSecond).as("同步会在每次启动时执行 —— 不幂等就意味着'重启一次多一批脏数据'").isEqualTo(afterFirst);
  }

  @Test
  void 目录里的十六个权限点都进了库() {
    catalog.sync();

    assertThat(jdbc.queryForObject("select count(*) from iam_permission", Integer.class))
        .isEqualTo(Permissions.ALL.size())
        .isEqualTo(16);
  }

  @Test
  void 元数据与代码里的目录一致() {
    catalog.sync();

    for (PermissionCatalog.Item item : PermissionCatalog.ITEMS) {
      String label =
          jdbc.queryForObject(
              "select label from iam_permission where code = ?", String.class, item.code());
      assertThat(label).as("权限点 %s 的界面文案应与目录一致", item.code()).isEqualTo(item.label());
    }
  }

  @Test
  void 各角色的授权集合与代码映射一致() {
    catalog.sync();

    assertThat(countFor("ADMIN", null)).isEqualTo(Permissions.of("ADMIN").size());
    assertThat(countFor("KB_OPERATOR", null)).isEqualTo(11);
    assertThat(countFor("APP_CONFIG", null)).isEqualTo(8);
    assertThat(countFor("USER", null)).isEqualTo(4);
    // 未定义的角色代码 ⇒ 空集合（而不是异常，见 Permissions.of 的注释）
    assertThat(Permissions.of("NOT_A_ROLE")).isEmpty();
  }

  /** 缺的授权要补上（等价于"代码里新增了一个权限点，重启后它出现在库里"）。 */
  @Test
  void 缺失的授权会被补上() {
    catalog.sync();
    jdbc.update(
        "delete from iam_role_permission where role_id = (select id from iam_role where code ="
            + " 'USER') and permission_code = ?",
        Permissions.KNOWLEDGE_VIEW);
    assertThat(countFor("USER", Permissions.KNOWLEDGE_VIEW)).isZero();

    catalog.sync();

    assertThat(countFor("USER", Permissions.KNOWLEDGE_VIEW)).as("同步要能把缺的补回来").isEqualTo(1);
  }

  /** ★ 角色不该有的授权要被清掉 —— 这条防的是"只加不减"的同步。 */
  @Test
  void 角色不该有的授权会被清理() {
    catalog.sync();
    jdbc.update(
        "insert into iam_role_permission (role_id, permission_code, created_at)"
            + " select id, ?, now() from iam_role where code = 'USER'"
            + " on conflict do nothing",
        Permissions.MODEL_MANAGE);
    assertThat(countFor("USER", Permissions.MODEL_MANAGE)).as("先手工塞进去").isEqualTo(1);

    catalog.sync();

    assertThat(countFor("USER", Permissions.MODEL_MANAGE))
        .as("MODEL_MANAGE 只属于 ADMIN；同步应当把 USER 上这条多余的授权删掉")
        .isZero();
  }

  // ────────────────────────── 工具 ──────────────────────────

  private int countFor(String roleCode, String permissionCode) {
    if (permissionCode == null) {
      Integer n =
          jdbc.queryForObject(
              "select count(*) from iam_role_permission rp join iam_role r on r.id = rp.role_id"
                  + " where r.code = ?",
              Integer.class,
              roleCode);
      return n == null ? 0 : n;
    }
    Integer n =
        jdbc.queryForObject(
            "select count(*) from iam_role_permission rp join iam_role r on r.id = rp.role_id"
                + " where r.code = ? and rp.permission_code = ?",
            Integer.class,
            roleCode,
            permissionCode);
    return n == null ? 0 : n;
  }

  private String snapshot() {
    Integer permissionCount =
        jdbc.queryForObject("select count(*) from iam_permission", Integer.class);
    String grants =
        String.join(
            ";",
            jdbc.queryForList(
                "select r.code || ':' || rp.permission_code from iam_role_permission rp"
                    + " join iam_role r on r.id = rp.role_id order by r.code, rp.permission_code",
                String.class));
    return permissionCount + "|" + grants;
  }
}
