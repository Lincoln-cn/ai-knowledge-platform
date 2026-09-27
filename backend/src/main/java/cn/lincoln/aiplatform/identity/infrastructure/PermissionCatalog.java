package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限点目录的**幂等同步**（{@code 08 §4.3.1}）。
 *
 * <p>★ 真源是<strong>代码</strong>（{@link Permissions} 常量），库里的 {@code iam_permission}
 * 只是它的只读镜像，供角色管理页渲染勾选框。所以同步方向永远是 <em>代码 → 库</em>， 而<strong>不是</strong>"有人在界面上加了一个权限点"。
 *
 * <p>★ 这正是{@link Permissions} 类注释里说的那条"刻意保留的非官方做法"： 官方假设权限来自配置，而配置就会被忘掉 —— 原项目"新增功能忘配权限 ⇒ 功能隐形"的病
 * 就是这么得的。把权限点做成代码常量、启动时同步，是**根治**而不是提醒。
 *
 * <p>★ 同步必须**幂等**（跑两次结果一致）：它在每次应用启动时执行， 而"启动一次就多一批脏数据"会累积成一个没人敢重启的系统。
 *
 * <p>★ 它同时负责**清理**：代码里删掉的权限点会从库里移除、角色不再持有的授权也会被删。 只加不减的同步会留下"库里有一个代码里不存在的权限点"，而角色管理页会把它渲染成一个
 * 勾了也没用的选项。
 */
@Service
public class PermissionCatalog {

  private static final Logger log = LoggerFactory.getLogger(PermissionCatalog.class);

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
          new Item(Permissions.ASSISTANT_VIEW, "查看应用", "应用"),
          new Item(Permissions.ASSISTANT_EDIT, "配置应用", "应用"),
          new Item(Permissions.ASSISTANT_PUBLISH, "发布应用", "应用"),
          new Item(Permissions.CONVERSATION_VIEW, "使用对话", "对话"),
          new Item(Permissions.CONVERSATION_SHARE, "分享自己的对话", "对话"),
          new Item(Permissions.CONVERSATION_MANAGE, "管理他人会话", "对话"),
          new Item(Permissions.MODEL_MANAGE, "模型管理", "模型"),
          new Item(Permissions.DASHBOARD_VIEW, "查看运营看板", "运营"),
          new Item(Permissions.FEEDBACK_HANDLE, "处理反馈", "运营"),
          new Item(Permissions.SYSTEM_MANAGE, "账号与权限", "系统"));

  private final JdbcTemplate jdbc;
  private final RoleRepository roles;

  public PermissionCatalog(JdbcTemplate jdbc, RoleRepository roles) {
    this.jdbc = jdbc;
    this.roles = roles;
  }

  /** 全量同步。★ 幂等：重复调用不改变结果。 */
  @Transactional
  public void sync() {
    upsertPermissions();
    syncRolePermissions();
    log.info("权限点目录已同步：{} 个权限点，{} 个角色", ITEMS.size(), roles.findAll().size());
  }

  private void upsertPermissions() {
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
  }

  /**
   * 让每个角色的授权集合与代码里的映射一致。
   *
   * <p>★ 做的是**差分**（该加的加、该删的删），而不是"先清空再插入"： 清空重插会在事务窗口内让"这个角色持有 0 个权限点"，而并发的鉴权请求 恰好落在这个窗口里就会全部被拒 ——
   * 一次重启制造一批莫名其妙的 403。
   */
  private void syncRolePermissions() {
    for (Role role : roles.findAll()) {
      Set<String> expected = new HashSet<>(Permissions.of(role.code()));
      Set<String> current =
          new HashSet<>(
              jdbc.queryForList(
                  "select permission_code from iam_role_permission where role_id = ?",
                  String.class,
                  role.id()));

      for (String code : expected) {
        if (!current.contains(code)) {
          jdbc.update(
              """
              insert into iam_role_permission (role_id, permission_code, created_at)
              values (?, ?, now())
              on conflict do nothing
              """,
              role.id(),
              code);
        }
      }
      for (String code : current) {
        if (!expected.contains(code)) {
          jdbc.update(
              "delete from iam_role_permission where role_id = ? and permission_code = ?",
              role.id(),
              code);
        }
      }
    }
  }
}
