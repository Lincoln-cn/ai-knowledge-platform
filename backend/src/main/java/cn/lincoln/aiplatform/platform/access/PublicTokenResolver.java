package cn.lincoln.aiplatform.platform.access;

import java.util.Optional;
import java.util.UUID;

/**
 * 公开令牌（免登录）的统一解析端口 —— 端口放 {@code platform}、实现在<strong>提供方模块</strong> （{@code 08 §4.2.3}）。
 *
 * <h3>★ 为什么必须有"一个入口"</h3>
 *
 * <p>{@code 08 §4.2.3} 的原文：前端并行开发时出现过<strong>两套互不相认的凭证</strong> （应用级与后来的会话级），前端当时在 mock 里加了桥接查找 ——
 * 那是补丁。 后端必须有"一个解析入口"：应用级的 {@code AccessCredential} 与会话级的分享<strong>可以是不同实体</strong>，但公开面只问这一个入口，
 * 否则「复制链接发给别人打开」这条链一定会在某处断。
 *
 * <h3>★ 形状：一个接口、多个实现</h3>
 *
 * <p>每一种凭证由它自己的模块实现这个接口（{@code assistant} 实现应用级、将来的 {@code conversation} 实现会话级），公开面的过滤器注入 {@code
 * List<PublicTokenResolver>} 逐个问 —— 谁认得这个 token 谁回答。 这样"加一种凭证"不需要改过滤器，而"只认一部分
 * token"这件事在类型上就说得清（{@link Optional#empty()}）。
 *
 * <h3>★ 它在 0.4.4 落的是"形状"</h3>
 *
 * <p>本周<strong>不实现</strong> {@code /api/v1/public/**} 的 HTTP 端点（那是第 17 周的事，见 {@code
 * ContractDriftTest.UNIMPLEMENTED_PREFIXES} 里的 {@code /public}）：本批落的是 "公开面解析出来的东西长什么样" —— 也就是
 * {@link PublicAccess} 里那三样。 端口先落地，是因为 {@code 08 §4.2.3} 把它定成公开面的唯一入口，而"入口的形状"决定了凭证表要存什么 （★ 具体就是
 * {@code max_classification}：没有它，解析结果里那个冻结密级就无处可取）。
 *
 * <p>★ 每个实现都必须遵守同一套裁决口径：<strong>不存在 / 已撤销 / 已过期 / 越权</strong>四种原因 要能区分（见 {@code
 * AccessCredentialService} 的注释 —— 全返同一个码会让排障只能猜）。
 */
public interface PublicTokenResolver {

  /**
   * 解析一个公开令牌。
   *
   * @param rawToken 链接里的令牌原文
   * @param requesterId 发起解析的<strong>已登录用户</strong>；未登录时为 {@code null}
   * @return 解析结果；{@code rawToken} 不属于本实现负责的凭证类型时返回 {@link Optional#empty()}
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 令牌属于本类型但不可用 （不存在 / 已撤销 / 已过期 /
   *     越权 —— 四种原因要能区分）
   */
  Optional<PublicAccess> resolve(String rawToken, UUID requesterId);

  /**
   * 公开面解析出来的结果。
   *
   * @param credentialId 凭证 id ★ 审计要用它 —— 免登录面**没有** {@code actor_id}， 不记 {@code credential_id}
   *     的话这条记录说不清"是谁进来的"（{@code 13 §十二} 第 4 行）
   * @param assistantId 这条凭证指向的<strong>应用</strong>。★ 归属与可见性判定走它 （凭证自己不带归属，见 {@code AccessCredential}
   *     的类注释）
   * @param subjectId ★ 访客行使的<strong>数据范围</strong>属于谁 —— {@link AccessScopePort#asScope}
   *     要一个"身份持有者"，而免登录面没有当前用户。
   *     <p>★★ <strong>当前取"应用归属人"</strong>（即"这条链接行使的是归属人的范围"），
   *     但它是一处<strong>明确的待裁决</strong>：另一种口径是"链接有自己的最小范围"， 那样访客看到的是"应用里被显式分享出来的一部分"而不是整个应用。 两者的差别只在
   *     {@code OwnedResource} 那一维，而本轮先按"归属人"落地 + 记账 （★ 判据要的"冻结密级"那一维与它无关，见交付报告"不确定/需要裁决"）。
   * @param maxClassification ★★ <strong>创建时冻结</strong>的密级上限（{@code 13 §5.2} 第 ① 级）。
   *     访客每次访问都用它<strong>重新过滤</strong>（第 ② 级），不是复用创建时命中的结果。
   */
  record PublicAccess(
      UUID credentialId, UUID assistantId, UUID subjectId, ClassificationLevel maxClassification) {}
}
