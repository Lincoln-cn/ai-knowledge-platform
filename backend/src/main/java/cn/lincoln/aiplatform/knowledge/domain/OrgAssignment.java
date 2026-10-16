package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 文档列表按<strong>责任部门归属</strong>过滤的三态（{@code #56} 的处置 A）。
 *
 * <h3>它存在的理由：一批"有证据、没出口"的数据</h3>
 *
 * <p>{@code V27} 把 {@code kb_document.department}（自由字符串）换成 {@code org_id}（外键）时， 按 {@code name}
 * 回填存量；**匹配不上的原值留在 {@link Document#departmentLegacy()}**（不猜、不静默丢）， 而 {@code V27} 的注释写着"这类行留在 {@code
 * department_legacy} 里<strong>等巡检</strong>"—— **巡检当时不存在**：没有任何入口能把它们列出来（{@code #56} 的实测读数：开发库 112
 * 篇里 3 篇这样）。
 *
 * <p>★★ 危害的形状（本项目头号病）：这 3 篇在界面上只是"责任部门那一列空着"，看起来像"还没填"， 而不像"系统需要你处理"；而 {@code department_legacy}
 * 是<strong>不可更新</strong>的存档列， 于是这批数据只会被忘掉 —— {@code 07 §11.4 ③} 本来就要求"未匹配的必须能被列出来"。
 *
 * <p>★ 为什么用三态枚举而不是 {@code Boolean orgIdIsNull}：布尔参数在调用点上读不出意图 （{@code findByKbId(kbId, 0, 20,
 * true)} 里的 {@code true} 是什么？）；而三态还能把 "今天的行为"（{@link #ANY}）明确写成默认值，让"加了这个筛选"不改变任何既有调用方的语义。
 */
public enum OrgAssignment {

  /** 不按归属过滤（{@code #56} 之前的既有行为，也是默认档）。 */
  ANY,

  /** 只列<strong>已经有归属</strong>的（{@code org_id} 非空）。 */
  ASSIGNED,

  /**
   * ★ 只列<strong>待补归属</strong>的（{@code org_id} 为空）。
   *
   * <p>★ 这一档就是 {@code #56} 要的那个出口：把这些文档列出来 → 手工指定归属 → 它们**从这一档消失**（判据 2 的读数）。
   */
  UNASSIGNED
}
