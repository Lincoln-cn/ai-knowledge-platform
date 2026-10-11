package cn.lincoln.aiplatform.platform.access;

/**
 * 共享主体"是否真实存在"的只读校验口（{@code 07 §8.2}）。
 *
 * <p>★ <strong>为什么必须有这一道</strong>：写错一个 id 的授权 = <strong>静默没共享出去</strong> ——
 * 对方看不到，而写的人以为共享了。症状与"没共享过"完全一样，所以它不能在写入口留白。 （前端 mock 的 {@code PUT /assistants/:id/grants}
 * 里已经写着这条理由，但后端此前根本没有写入口， 于是这条校验一直没有后端版本。）
 *
 * <p>★ <strong>为什么是一个口、而不是按 kind 各开一个</strong>：{@code kind} 决定"去哪张表"是**同一个问题** （"这个主体存在吗"），而三种 kind
 * 的<strong>取值口径并不一致</strong>：{@code USER} / {@code ORG} 是 uuid， {@code ROLE} 是角色
 * <strong>code</strong>（见 {@link Grant#subject()}）。把这三套口径分散到每个调用方， 就是"同一个问题三处答案"——而它们一定会慢慢分叉。
 *
 * <p>★ 接口放 {@code platform}、实现放 {@code identity}（{@code 08 §4.4.2}）：只有 identity 认识账号、角色与组织机构这三个目录。
 */
public interface GrantSubjectPort {

  /**
   * 这个共享主体此刻可不可以被授权。
   *
   * <p>★ 口径里有两条容易被忽略、但都已被设计册写死：
   *
   * <ul>
   *   <li><strong>软删的账号不算存在</strong>（{@code iam_user.deleted_at} 非空 ⇒ false）：软删是"人走了"，
   *       把库共享给一个已删除的账号是一条永远不会被任何人命中的授权。
   *   <li><strong>停用的部门不算存在</strong>（{@code 07 §10.5} 判据 3："停用一个部门 ⇒ <em>不产生新的共享</em>，
   *       既有共享不变成不可见"）：所以这里返 false 只挡<strong>新建</strong>的共享，不动既有的。
   * </ul>
   *
   * @param kind 主体种类；{@code null} 或枚举里的预留值（{@code GROUP}/{@code LINK}）一律 false
   * @param id 主体标识；{@code USER}/{@code ORG} 是 uuid 字符串，{@code ROLE} 是角色 code
   */
  boolean exists(SubjectKind kind, String id);
}
