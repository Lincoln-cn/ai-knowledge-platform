package cn.lincoln.aiplatform.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点常量，以及「角色 → 权限点」的<strong>初始映射</strong>。
 *
 * <p>★ 两个真源，都必须对齐（写在这里免得后来的人去猜该信哪个）：
 *
 * <ul>
 *   <li><strong>权限点清单与命名</strong> → {@code frontend/src/constants/permissions.ts}（16 个）
 *   <li><strong>各角色拿到哪些</strong> → {@code frontend/src/mock/fixtures/identity.ts:27-80}
 * </ul>
 *
 * <p>★ 为什么权限点清单是<strong>代码常量</strong>而不是数据：{@code 08 §4.3.1} —— 这是刻意保留的
 * 一处"非官方做法"，因为它根治的是原项目"新增功能忘配权限 ⇒ 功能隐形"的病。 启动时把这份清单<em>幂等同步</em>进 {@code
 * iam_permission}（供角色管理界面渲染勾选框）， <strong>目录的真源永远是这个类</strong>。
 *
 * <h3>★ {@link #of} 已降级为「初始映射」，不是鉴权真源（第 3 周第二段）</h3>
 *
 * <p>「角色挂哪些权限点」的真源是<strong>库</strong>（{@code iam_role_permission}，管理员在界面上勾选）。
 * 鉴权（{@code @PreAuthorize}）读的是令牌里的 {@code authorities} 声明， 而那份声明在登录/刷新时从库里读出后写进令牌 —— 不是读这里的常量。
 *
 * <p>本类的映射现在只剩两个用途，而且都<strong>不参与鉴权</strong>：
 *
 * <ol>
 *   <li><strong>首次 seed</strong>：某角色在 {@code iam_role_permission} 里一行都没有时，
 *       按这份映射做一次初始化（此后以库为准，界面改动不会被重启冲掉）
 *   <li><strong>ADMIN 的代码维护集</strong>：ADMIN 始终按 {@code of("ADMIN")} 差分同步， 保证新增权限点自动落到 ADMIN、且 ADMIN
 *       不可能被削减到把唯一的管理员锁死
 * </ol>
 *
 * <p>⚠️ 如果你正在改鉴权逻辑：不要回到「{@code of(role.code())} 当真源」那条路。 那正是"界面上勾了不生效、且完全看不出来"的静默失效。
 */
public final class Permissions {

  // ── 知识（6）──
  public static final String KNOWLEDGE_VIEW = "knowledge:view";
  public static final String KNOWLEDGE_CREATE = "knowledge:create";
  public static final String KNOWLEDGE_MANAGE = "knowledge:manage";
  public static final String DOCUMENT_UPLOAD = "knowledge:document:upload";
  public static final String SEGMENT_EDIT = "knowledge:segment:edit";

  /**
   * 问答对的增删改（`FR-03-12`）。
   *
   * <p>★ 为什么不复用 {@link #SEGMENT_EDIT}：分段是**自动切**出来的，问答对是**人工标定**的，
   * 两者是不同的事。借用一个语义不相关的权限点，等于让"能改分段的人就能改问答对"这件事 变成一个说不清的口径（本仓既有教训）。
   */
  public static final String QA_EDIT = "knowledge:qa:edit";

  public static final String HIT_TEST = "knowledge:hittest";

  // ── 应用（3）──
  public static final String ASSISTANT_VIEW = "assistant:view";
  public static final String ASSISTANT_EDIT = "assistant:edit";
  public static final String ASSISTANT_PUBLISH = "assistant:publish";

  // ── 对话（3）──
  public static final String CONVERSATION_VIEW = "conversation:view";
  public static final String CONVERSATION_SHARE = "conversation:share";
  public static final String CONVERSATION_MANAGE = "conversation:manage";

  /**
   * 配置「对话设置」（默认模型 / 初始提示词 / 召回开关与阈值 / 流式超时）。
   *
   * <p>★ 单开一个权限点而不是复用 {@code system:manage}： 后者是「整个系统管理」的开关（组织、角色、字典、系统参数全在里面），
   * 而「允许某人调召回阈值」是一件**比系统管理窄得多**的事 —— 复用会把管理员权限切成两半（要么全给、要么不给），于是只能再找一个管理员。
   *
   * <p>★ 默认只有 ADMIN 拥有（见下方的 ADMIN 集合）；其它角色需要在角色管理里显式勾选。
   */
  public static final String CONVERSATION_SETTINGS = "conversation:settings";

  // ── 模型（1）· 运营（2）· 系统（1）──
  public static final String MODEL_MANAGE = "model:manage";
  public static final String DASHBOARD_VIEW = "dashboard:view";
  public static final String FEEDBACK_HANDLE = "feedback:handle";
  public static final String SYSTEM_MANAGE = "system:manage";

  /** 基础数据字典（密级 / 文种 / 提示词类型 / 库分类）—— 管理菜单「基础数据」。 */
  public static final String PLATFORM_DICT_MANAGE = "platform:dict:manage";

  /** 组织机构树 —— 数据权限的载体（07 §十）。 */
  public static final String IDENTITY_ORG_MANAGE = "identity:org:manage";

  /** 全量清单。★ 这是"权限点一共几个"的唯一定义 —— 测试会断言它恰好 ALL.size() 个（不再写死数字）。 */
  public static final List<String> ALL =
      List.of(
          KNOWLEDGE_VIEW,
          KNOWLEDGE_CREATE,
          KNOWLEDGE_MANAGE,
          DOCUMENT_UPLOAD,
          SEGMENT_EDIT,
          QA_EDIT,
          HIT_TEST,
          ASSISTANT_VIEW,
          ASSISTANT_EDIT,
          ASSISTANT_PUBLISH,
          CONVERSATION_VIEW,
          CONVERSATION_SHARE,
          CONVERSATION_MANAGE,
          CONVERSATION_SETTINGS,
          MODEL_MANAGE,
          DASHBOARD_VIEW,
          FEEDBACK_HANDLE,
          SYSTEM_MANAGE,
          IDENTITY_ORG_MANAGE,
          PLATFORM_DICT_MANAGE);

  private static final List<String> KB_OPERATOR =
      List.of(
          KNOWLEDGE_VIEW,
          KNOWLEDGE_CREATE,
          KNOWLEDGE_MANAGE,
          DOCUMENT_UPLOAD,
          SEGMENT_EDIT,
          QA_EDIT,
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
          QA_EDIT,
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
   * 某个角色代码对应的<strong>初始映射</strong>权限点。
   *
   * <p>★ 它<strong>不是鉴权真源</strong>（见类注释）：只用于启动 seed 与 ADMIN 的代码维护集。 鉴权用的授权集合来自 {@code
   * iam_role_permission}。
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
