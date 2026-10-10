package cn.lincoln.aiplatform.platform.org;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组织机构的<strong>只读目录端口</strong>（{@code 07 §十一}）—— 组织机构树的第一个消费位的取数口。
 *
 * <p>★ <strong>接口放 {@code platform}、实现放 {@code identity}</strong>：与 {@code AccessScopePort}
 * 同一条铁律（{@code 08 §4.4.2}）。所有模块已依赖 platform ⇒ 依赖矩阵零改动；而 identity 之外的模块从此再也<strong>不需要</strong>直接依赖
 * {@code identity.domain} —— 那条依赖会被 {@code ModuleBoundaryTest}（Spring Modulith 的 {@code
 * verify()}）判红。
 *
 * <p>★ 只读、且只给三个方法，是刻意的：组织树的<strong>维护</strong>（建/改/移/停/删）属于 identity 自己的用例层（{@code
 * OrgService}）。别的模块只该"认识一个部门"，不该改它。
 *
 * <p>★ <strong>{@code code} 是身份、{@code name} 只是表达</strong>（用户 2026-10-08 指出）：
 *
 * <ul>
 *   <li>{@code code}：稳定机器码，<strong>全局唯一且不可改</strong> —— 它进共享主体（grant 的 {@code
 *       subject_id}）、审计，以及<strong>文档的召回前缀与 {@code content_hash}</strong> （{@code 07
 *       §11.2}）。可变的东西不许进入向量与哈希的材料。
 *   <li>{@code name}：展示名，<strong>可改、可重名</strong>（跨父）—— 因此它只能出现在人读的位置
 *       （界面/引用/提示词渲染），<strong>绝不能</strong>参与身份判定或命中材料。
 *   <li>{@code path}：物化路径，<strong>移动部门会变</strong> ⇒ 同样不许进前缀（否则一次移动会让 整棵子树下所有文档的向量失效）。
 * </ul>
 *
 * <p>★ 本端口<strong>不含</strong>"可见性判定"：归属 ≠ 可见性（{@code 07 §11.1}）。 {@link #selfAndDescendants(UUID)}
 * 是给"含下级"的共享判定（{@code T301} 的 {@code ScopeFilter}）准备的 ——
 * 这一轮<strong>刻意不接进检索谓词</strong>，避免在没有真实消费方时先长出一条没人验证的判定分支。
 */
public interface OrgDirectoryPort {

  /** 一个部门的只读投影。★ 只出网这四个字段：调用方要的不是实体，而是"认识一个部门"的最小事实。 */
  record OrgBrief(UUID id, String code, String name, String path, boolean enabled) {}

  /**
   * 按 id 取一个部门（不存在 ⇒ 空）。
   *
   * <p>★ 建档路径用它校验"用户选的责任部门真实存在" —— 不校验就等于让 {@code org_id} 指向一个 从未存在过的 uuid，而外键报错会变成一次
   * 500（"我崩了"），真正的原因却是"你传错了"。
   */
  Optional<OrgBrief> findById(UUID id);

  /**
   * 按 id 批量取（列表页一次取回所有部门名）。
   *
   * <p>★ 存在的理由是<strong>不要 N+1</strong>：列表页有 20 条文档时逐个 {@link #findById(UUID)} 就是 20 次查询 ——
   * 页面看起来完全正常，只是慢，而"慢"没人会当成缺陷报上来。
   *
   * <p>★ 返回的清单<strong>顺序不承诺</strong>与入参一致（缺 id 的直接不出现）：调用方要按 key 建映射。
   */
  List<OrgBrief> findByIds(Collection<UUID> ids);

  /**
   * 这个部门<strong>及它的全部下级</strong>的 id（按物化路径前缀取，{@code 07 §10.2}）。
   *
   * <p>★ 语义是"含下级"：共享给「技术部」意味着「技术部及下级都看得见」（用户口径）。 ★ <strong>只属于共享与身份两个消费位</strong>（{@code 07 §11.1}
   * 的 ②③）—— 文档归属（①）是"精确一个部门"，对归属问"含下级"没有意义。
   */
  List<UUID> selfAndDescendants(UUID orgId);
}
