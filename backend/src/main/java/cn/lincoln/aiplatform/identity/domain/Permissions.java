package cn.lincoln.aiplatform.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点常量，以及「角色 → 权限点」的映射。
 *
 * <p>★ 两个真源，都必须对齐（写在这里免得后来的人去猜该信哪个）：
 *
 * <ul>
 *   <li><strong>权限点清单与命名</strong> → {@code frontend/src/constants/permissions.ts}（16 个）
 *   <li><strong>各角色拿到哪些</strong> → {@code frontend/src/mock/fixtures/identity.ts:27-80}
 * </ul>
 *
 * <p>★ 为什么权限点是<strong>代码常量</strong>而不是数据：{@code 08 §4.3.1} —— 这是刻意保留的 一处"非官方做法"，因为它根治的是原项目"新增功能忘配权限
 * ⇒ 功能隐形"的病。 第 3 周会把这份清单<em>幂等同步</em>进库（供角色管理界面渲染勾选框）， 但**真源永远是这个类**，不是库。
 */
public final class Permissions {

  // ── 知识（6）──
  public static final String KNOWLEDGE_VIEW = "knowledge:view";
  public static final String KNOWLEDGE_CREATE = "knowledge:create";
  public static final String KNOWLEDGE_MANAGE = "knowledge:manage";
  public static final String DOCUMENT_UPLOAD = "knowledge:document:upload";
  public static final String SEGMENT_EDIT = "knowledge:segment:edit";
  public static final String HIT_TEST = "knowledge:hittest";

  // ── 应用（3）──
  public static final String ASSISTANT_VIEW = "assistant:view";
  public static final String ASSISTANT_EDIT = "assistant:edit";
  public static final String ASSISTANT_PUBLISH = "assistant:publish";

  // ── 对话（3）──
  public static final String CONVERSATION_VIEW = "conversation:view";
  public static final String CONVERSATION_SHARE = "conversation:share";
  public static final String CONVERSATION_MANAGE = "conversation:manage";

  // ── 模型（1）· 运营（2）· 系统（1）──
  public static final String MODEL_MANAGE = "model:manage";
  public static final String DASHBOARD_VIEW = "dashboard:view";
  public static final String FEEDBACK_HANDLE = "feedback:handle";
  public static final String SYSTEM_MANAGE = "system:manage";

  /** 全量清单。★ 这是"权限点一共几个"的唯一定义 —— 测试会断言它恰好 16 个。 */
  public static final List<String> ALL =
      List.of(
          KNOWLEDGE_VIEW,
          KNOWLEDGE_CREATE,
          KNOWLEDGE_MANAGE,
          DOCUMENT_UPLOAD,
          SEGMENT_EDIT,
          HIT_TEST,
          ASSISTANT_VIEW,
          ASSISTANT_EDIT,
          ASSISTANT_PUBLISH,
          CONVERSATION_VIEW,
          CONVERSATION_SHARE,
          CONVERSATION_MANAGE,
          MODEL_MANAGE,
          DASHBOARD_VIEW,
          FEEDBACK_HANDLE,
          SYSTEM_MANAGE);

  private static final List<String> KB_OPERATOR =
      List.of(
          KNOWLEDGE_VIEW,
          KNOWLEDGE_CREATE,
          KNOWLEDGE_MANAGE,
          DOCUMENT_UPLOAD,
          SEGMENT_EDIT,
          HIT_TEST,
          ASSISTANT_VIEW,
          CONVERSATION_VIEW,
          CONVERSATION_SHARE,
          DASHBOARD_VIEW,
          FEEDBACK_HANDLE);

  private static final List<String> APP_CONFIG =
      List.of(
          KNOWLEDGE_VIEW,
          HIT_TEST,
          ASSISTANT_VIEW,
          ASSISTANT_EDIT,
          ASSISTANT_PUBLISH,
          CONVERSATION_VIEW,
          CONVERSATION_SHARE,
          DASHBOARD_VIEW);

  private static final List<String> USER =
      List.of(KNOWLEDGE_VIEW, ASSISTANT_VIEW, CONVERSATION_VIEW, CONVERSATION_SHARE);

  /**
   * ⚠️ {@code CREATOR} 的这份集合是**按 {@code 07 §4.1} 的功能矩阵推出来的** —— 前端 mock 里<em>没有</em>这个角色（只有
   * r1~r4），所以它没有可对齐的真源。
   *
   * <p>它和 {@code KB_OPERATOR} 恰好都是 11 个，但集合不同：CREATOR 有 {@code assistant:edit/publish}（能建自己的应用），没有
   * {@code dashboard:view} 与 {@code feedback:handle}（不参与运营）。两者的真正差别其实在<strong>数据面</strong> （{@code
   * own} vs {@code all}），那要等第 3 周做数据范围时才落地。
   */
  private static final List<String> CREATOR =
      List.of(
          KNOWLEDGE_VIEW,
          KNOWLEDGE_CREATE,
          KNOWLEDGE_MANAGE,
          DOCUMENT_UPLOAD,
          SEGMENT_EDIT,
          HIT_TEST,
          ASSISTANT_VIEW,
          ASSISTANT_EDIT,
          ASSISTANT_PUBLISH,
          CONVERSATION_VIEW,
          CONVERSATION_SHARE);

  private static final Map<String, List<String>> BY_ROLE_CODE =
      Map.of(
          "ADMIN", ALL,
          "KB_OPERATOR", KB_OPERATOR,
          "APP_CONFIG", APP_CONFIG,
          "CREATOR", CREATOR,
          "USER", USER);

  private Permissions() {}

  /**
   * 某个角色代码对应的权限点。
   *
   * <p>★ 未知角色代码返回**空列表**而不是抛异常：角色代码来自数据库， 而"库里有个代码不认识的角色"应该表现为"这个账号什么都做不了"（并留下日志）， 而不是让整个登录流程 500
   * —— 后者会把一个配置问题升级成一次事故。
   */
  public static List<String> of(String roleCode) {
    return BY_ROLE_CODE.getOrDefault(roleCode, List.of());
  }

  /** 全部已定义的角色代码（供第 3 周的幂等同步与测试用）。 */
  public static Set<String> roleCodes() {
    return BY_ROLE_CODE.keySet();
  }
}
