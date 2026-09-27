package cn.lincoln.aiplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>★ 新语义（第 3 周第二段）下的两条核心性质：
 *
 * <ol>
 *   <li><strong>目录由代码权威</strong>：代码里新增的权限点自动进目录、自动落到 ADMIN， 但不会自动落到 USER 这类角色（要显式勾选）
 *   <li><strong>角色授权以库为准</strong>：已初始化的角色不再被 seed 回代码映射 —— 界面改动 不能被重启冲掉。只有「孤儿」（code 已不在目录里）会被清理
 * </ol>
 */
@SpringBootTest
@Testcontainers
class PermissionCatalogTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  @Autowired PermissionCatalog catalog;
  @Autowired JdbcTemplate jdbc;

  /**
   * 每条用例前把授权清空再同步一次 —— 回到「各角色都已按初始映射 seed」的确定状态。
   *
   * <p>★ 必要性：新语义下「已初始化的角色不 seed」，所以一旦某条用例改了 USER 的授权， 下一条用例看到的就是脏状态。测试之间不能共享可变的授权数据。
   */
  @BeforeEach
  void resetToSeededState() {
    jdbc.update("delete from iam_role_permission");
    catalog.sync();
  }

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

  /**
   * ★ 新语义：代码里<strong>新增</strong>的权限点会自动出现在目录里、并自动落到 ADMIN， 而 USER
   * 这类角色<strong>不会</strong>自动获得（需要显式勾选）。
   *
   * <p>这条钉的是「新增功能忘配权限 ⇒ 功能隐形」的解药：目录由代码权威， ADMIN 兜底持有全部 —— 但非 ADMIN
   * 角色的授权是<strong>数据</strong>，不该被启动静默扩张。
   */
  @Test
  void 新权限点自动进目录并落到ADMIN但不自动授予USER() {
    catalog.sync();

    // 模拟「代码里新增了一个权限点」：把 model:manage 从目录与全部授权里挖掉，
    // 让库回到"代码有、库没有"的初始态（注意外键：先删授权再删目录行）
    jdbc.update(
        "delete from iam_role_permission where permission_code = ?", Permissions.MODEL_MANAGE);
    jdbc.update("delete from iam_permission where code = ?", Permissions.MODEL_MANAGE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from iam_permission where code = ?",
                Integer.class,
                Permissions.MODEL_MANAGE))
        .as("先确认挖干净了")
        .isZero();

    catalog.sync();

    // ① 自动出现在目录里
    assertThat(
            jdbc.queryForObject(
                "select count(*) from iam_permission where code = ?",
                Integer.class,
                Permissions.MODEL_MANAGE))
        .as("代码里新增的权限点必须自动进目录 —— 否则角色管理页看不到它")
        .isEqualTo(1);
    // ② 自动落到 ADMIN（代码维护集）
    assertThat(countFor("ADMIN", Permissions.MODEL_MANAGE))
        .as("ADMIN 兜底持有全部权限点，杜绝「功能隐形」")
        .isEqualTo(1);
    // ③ USER 不会自动获得
    assertThat(countFor("USER", Permissions.MODEL_MANAGE))
        .as("非 ADMIN 角色的授权是数据，不该被启动静默扩张")
        .isZero();
  }

  /**
   * ★ 真正的孤儿清理：一个<strong>代码里不存在</strong>的权限点及其授权会被清掉。
   *
   * <p>注意 {@code iam_role_permission.permission_code} 有外键指向 {@code iam_permission}，
   * 所以要先插一行伪造的目录行、再插授权，才能验证 sync 把<strong>两者都</strong>清掉。
   *
   * <p>⚠️ 旧语义把「USER 手工持有 model:manage」也当孤儿删掉 —— 新语义下那是<strong>合法状态</strong>
   * （等于管理员勾过），不删。这里验的才是真正的孤儿。
   */
  @Test
  void 代码里不存在的权限点及其授权会被清理() {
    catalog.sync();

    // 先插伪造的目录行（否则授权行过不了外键），再给 USER 插一条授权
    jdbc.update(
        "insert into iam_permission (code, label, module, created_at, updated_at)"
            + " values ('ghost:permission', '幽灵权限', '测试', now(), now())");
    jdbc.update(
        "insert into iam_role_permission (role_id, permission_code, created_at)"
            + " select id, 'ghost:permission', now() from iam_role where code = 'USER'");
    assertThat(countFor("USER", "ghost:permission")).as("先手工塞进去").isEqualTo(1);

    catalog.sync();

    assertThat(countFor("USER", "ghost:permission")).as("code 不在目录里的授权是孤儿，必须清掉").isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from iam_permission where code = 'ghost:permission'",
                Integer.class))
        .as("伪造的目录行也要一起清掉 —— 否则角色管理页会渲染出一个勾了也没用的选项")
        .isZero();
  }

  /** ★ 新语义：已初始化的角色（库里已有授权行）<strong>不</strong>再被 seed 回代码映射。 这条直接钉住「重启冲掉界面改动」这个病。 */
  @Test
  void 已初始化的角色不会被同步冲掉界面改动() {
    catalog.sync();

    // 模拟管理员在界面上把 USER 的授权改成只留 knowledge:view
    jdbc.update(
        "delete from iam_role_permission where role_id = (select id from iam_role where code ="
            + " 'USER')");
    jdbc.update(
        "insert into iam_role_permission (role_id, permission_code, created_at) select id,"
            + " 'knowledge:view', now() from iam_role where code = 'USER' on conflict do nothing");

    catalog.sync();

    assertThat(countFor("USER", null)).as("USER 已初始化 ⇒ 以库为准，启动不该把它冲回 4 个").isEqualTo(1);
    assertThat(countFor("USER", Permissions.KNOWLEDGE_VIEW)).isEqualTo(1);
    assertThat(countFor("USER", Permissions.ASSISTANT_VIEW)).as("界面上被取消的授权不该被 seed 回来").isZero();
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
