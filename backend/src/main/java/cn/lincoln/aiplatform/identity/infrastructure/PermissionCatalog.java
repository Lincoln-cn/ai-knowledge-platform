package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限点目录的<strong>幂等同步</strong>（{@code 08 §4.3.1}）。
 *
 * <h3>两个真源，分工明确（第 3 周第二段定案）</h3>
 *
 * <ul>
 *   <li><strong>{@code iam_permission}（目录）</strong>→ 真源是<strong>代码</strong>（{@link Permissions} 常量
 *       + {@link #ITEMS}）。启动时 upsert + 清理代码里已不存在的 code。理由：权限点目录的真源是代码， 它是「新增功能忘配权限 ⇒ 功能隐形」的解药 ——
 *       新增一个 {@code @PreAuthorize} 要用的权限点，目录里会自动多一项，角色管理页自动多一个勾选框。
 *   <li><strong>{@code iam_role_permission}（角色授权）</strong>→ 真源是<strong>库</strong>（管理员在界面上勾）。
 *       鉴权读的就是这张表（经由令牌的 {@code authorities}，见 {@code JwtTokenService}）。
 *       启动同步<strong>不得</strong>把界面改动冲掉。
 * </ul>
 *
 * <h3>角色授权的同步规则</h3>
 *
 * <ol>
 *   <li><strong>ADMIN 例外</strong>：始终按 {@code Permissions.of("ADMIN")} 差分同步（代码权威）。 两个理由，缺一不可： ①
 *       新增权限点会自动落到 ADMIN，杜绝「功能隐形」； ② ADMIN 是打破玻璃的角色，代码维护能保证它<strong>不可能被削减</strong>到把平台唯一的管理员锁死 ——
 *       那是不可恢复状态。
 *   <li><strong>其它角色</strong>：只在该角色在 {@code iam_role_permission} 里<strong>一行都没有</strong>时， 按 {@code
 *       Permissions.of(code)} 做一次 seed（= 首次初始化）。此后以库为准， 界面改动不会被重启冲掉。
 * </ol>
 *
 * <p>★ 孤儿授权（{@code permission_code} 已不在目录里）不需要单独一步清理： {@code upsertPermissions()}
 * 删目录行时会<strong>先删引用它的授权</strong> （外键约束要求这个顺序），所以"目录里没有的 code 的授权"在同步里根本活不到下一步。 给它单开一次全表扫是永远命不中的死代码
 * —— 曾经写过一版 {@code cleanupOrphanGrants}，正是因为这个不可达性被删掉。
 *
 * <p>★ 保留「差分而非先清空再插入」的实现：清空重插会在事务窗口内让角色持有 0 个权限点， 并发鉴权会全部被拒 —— 一次重启制造一批莫名其妙的 403。这与 {@code
 * RolePermissionRepository#replaceAll} 的约束同源。
 *
 * <p>★ 同步必须<strong>幂等</strong>（跑两次结果一致）：它在每次应用启动时执行， 而"启动一次就多一批脏数据"会累积成一个没人敢重启的系统。
 */
@Service
public class PermissionCatalog {

  private static final Logger log = LoggerFactory.getLogger(PermissionCatalog.class);

  /** ADMIN 角色代码。★ 与前端 {@code RoleManage.vue} 的 {@code isAdminRole} 同口径。 */
  public static final String ADMIN_ROLE_CODE = "ADMIN";

  /**
   * 一条权限点的元数据。
   *
   * @param label 界面文案
   * @param module 归属模块，用于分组展示
   */
  public record Item(String code, String label, String module) {}

  /**
   * 目录内容。★ 逐项对齐契约的 {@code frontend/src/mock/fixtures/identity.ts:5-23} —— 那边的 label / module
   * 就是界面已经在用的文案，这里照抄以免两处各说一套。
   */
  public static final List<Item> ITEMS =
      List.of(
          new Item(Permissions.KNOWLEDGE_VIEW, "查看知识库", "知识"),
          new Item(Permissions.KNOWLEDGE_CREATE, "创建知识库", "知识"),
          new Item(Permissions.KNOWLEDGE_MANAGE, "编辑/删除知识库", "知识"),
          new Item(Permissions.DOCUMENT_UPLOAD, "上传与加工文档", "知识"),
          new Item(Permissions.SEGMENT_EDIT, "治理分段", "知识"),
          new Item(Permissions.HIT_TEST, "命中测试", "检索"),
          new Item(Permissions.QA_EDIT, "维护问答对", "知识"),
          new Item(Permissions.ASSISTANT_VIEW, "查看应用", "应用"),
          new Item(Permissions.ASSISTANT_EDIT, "配置应用", "应用"),
          new Item(Permissions.ASSISTANT_PUBLISH, "发布应用", "应用"),
          new Item(Permissions.CONVERSATION_VIEW, "使用对话", "对话"),
          new Item(Permissions.CONVERSATION_SHARE, "分享自己的对话", "对话"),
          new Item(Permissions.CONVERSATION_MANAGE, "管理他人会话", "对话"),
          new Item(Permissions.MODEL_MANAGE, "模型管理", "模型"),
          new Item(Permissions.DASHBOARD_VIEW, "查看运营看板", "运营"),
          new Item(Permissions.FEEDBACK_HANDLE, "处理反馈", "运营"),
          new Item(Permissions.SYSTEM_MANAGE, "账号与权限", "系统"),
          new Item(Permissions.IDENTITY_ORG_MANAGE, "组织机构", "系统"),
          new Item(Permissions.PLATFORM_DICT_MANAGE, "基础数据", "系统"));

  private final JdbcTemplate jdbc;
  private final RoleRepository roles;
  private final RolePermissionRepository rolePermissions;

  public PermissionCatalog(
      JdbcTemplate jdbc, RoleRepository roles, RolePermissionRepository rolePermissions) {
    this.jdbc = jdbc;
    this.roles = roles;
    this.rolePermissions = rolePermissions;
  }

  /** 全量同步。★ 幂等：重复调用不改变结果。 */
  @Transactional
  public void sync() {
    Set<String> catalogCodes = upsertPermissions();
    syncRolePermissions(catalogCodes);
    log.info("权限点目录已同步：{} 个权限点，{} 个角色", ITEMS.size(), roles.findAll().size());
  }

  /**
   * 目录同步（代码 → 库）。★ 代码权威：upsert 现有项、删除代码里已不存在的 code。
   *
   * <p>★ 删目录行时<strong>顺带删掉引用它的授权行</strong>（外键要求先删子行）。 所以同步结束后不可能存在"code 不在目录里"的授权 ——
   * 不需要再单独做一次孤儿授权清理。
   *
   * @return 当前目录里的全部 code（角色授权 seed/差分要按它过滤）
   */
  private Set<String> upsertPermissions() {
    for (Item item : ITEMS) {
      jdbc.update(
          """
          insert into iam_permission (code, label, module, created_at, updated_at)
          values (?, ?, ?, now(), now())
          on conflict (code) do update
            set label = excluded.label,
                module = excluded.module,
                updated_at = now()
          """,
          item.code(),
          item.label(),
          item.module());
    }

    // 清理代码里已删除的权限点。★ 只加不减会留下"勾了也没用"的幽灵选项。
    Set<String> catalogCodes =
        ITEMS.stream().map(Item::code).collect(Collectors.toCollection(HashSet::new));
    List<String> staleCodes =
        jdbc.queryForList("select code from iam_permission", String.class).stream()
            .filter(code -> !catalogCodes.contains(code))
            .toList();
    for (String stale : staleCodes) {
      // 先删引用它的授权（外键），再删目录行
      jdbc.update("delete from iam_role_permission where permission_code = ?", stale);
      jdbc.update("delete from iam_permission where code = ?", stale);
      log.info("权限点 {} 已从代码中移除，目录里的残留行与授权已清理", stale);
    }
    return catalogCodes;
  }

  /**
   * 角色授权同步。★ 新语义（第 3 周第二段）：
   *
   * <ul>
   *   <li>ADMIN → 始终按代码差分（见类注释的两条理由）
   *   <li>其它角色 → 仅在「一行都没有」时 seed 一次；之后以库为准
   * </ul>
   */
  private void syncRolePermissions(Set<String> catalogCodes) {
    for (Role role : roles.findAll()) {
      if (ADMIN_ROLE_CODE.equals(role.code())) {
        // ADMIN 例外：代码权威。新增权限点自动落到 ADMIN；ADMIN 不可能被削减到锁死自己。
        List<String> expected =
            Permissions.of(role.code()).stream()
                .filter(catalogCodes::contains)
                .collect(Collectors.toList());
        rolePermissions.replaceAll(role.id(), expected);
      } else if (!rolePermissions.hasAnyGrant(role.id())) {
        // 首次初始化：该角色在库里一行都没有，按初始映射 seed 一次。
        // 此后管理员在界面上的勾选就是真源，重启不会冲掉。
        List<String> seed =
            Permissions.of(role.code()).stream()
                .filter(catalogCodes::contains)
                .collect(Collectors.toList());
        rolePermissions.replaceAll(role.id(), seed);
        log.info("角色 {} 首次初始化授权：{} 个权限点", role.code(), seed.size());
      }
      // 其它情况：库里已有授权 ⇒ 以库为准，什么都不做。
      // ★ 这正是「重启不冲掉界面改动」的那条线 —— 旧行为是无条件按 Permissions.of 差分，
      //   管理员勾的会被静默改回去，而界面上看起来一切正常。
    }
  }
}
