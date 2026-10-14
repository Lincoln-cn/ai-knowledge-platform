package cn.lincoln.aiplatform.platform.access;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 按 {@code subjectId} 读<strong>主体身份</strong>的端口（第 17 周 · {@code 08 §4.4.4} · Issue {@code #92}）。
 *
 * <h3>★ 它解决的是什么（为什么不能复用现成的东西）</h3>
 *
 * <p>公开面（访问凭证 / 分享链接）的<strong>正常使用者是未登录访客</strong>，可是"以某个人的范围取数"这件事 一直在读<strong>令牌</strong>（{@code
 * JwtTokenService} 把 role / scope / clearance / clLabels / orgId 写进令牌， 于是 {@code
 * AccessScopeAdapter} 可以零查询地构造 {@link ScopeFilter}）—— 访客<strong>没有令牌</strong>，
 * 于是没有第二个地方能回答"这个人的角色、数据范围、密级、部门是什么"。
 *
 * <p>★ 两条现成的路都不能走：
 *
 * <ul>
 *   <li>{@link AccessScopePort#asScope}：它的正当性是"发起人 = 当前登录用户"，匿名面走不通（{@code #92} 的正文）；
 *   <li>{@link cn.lincoln.aiplatform.platform.org.OrgDirectoryPort}：它<strong>只该管组织树</strong>。 把"角色
 *       / 数据范围 / 密级"塞进去，等于让"目录"一个词承担两种语义 —— 而组织树是<strong>一棵树</strong>，
 *       身份是<strong>一个人</strong>，两者的权威源、缓存粒度、失效时机都不同。
 * </ul>
 *
 * <h3>★ 形状：端口在 {@code platform}、实现在 {@code identity}</h3>
 *
 * <p>与本项目其余跨模块 SPI 同一条口径（{@code 08 §4.4.2}）：所有模块已依赖 {@code platform} ⇒ 依赖矩阵零改动； 而真正读 {@code
 * iam_user} / {@code iam_role} 的实现必须住在 {@code identity}（那两张表只有它看得见， {@code ModuleBoundaryTest} 会拦）。
 *
 * <p>★ <strong>它与 {@link GrantSubjectPort} 不是一回事</strong>：那个回答"共享给了谁"（主体是用户 / 角色 / 部门），
 * 这个回答"<em>行使谁的范围</em>"。两者都用了 subject 这个词，是因为各自领域的既有叫法如此 ——
 * <strong>不要合并</strong>：一个是授权的<em>接收方</em>，一个是取数的<em>身份持有者</em>。
 *
 * <h3>★ 这里只回答"这个人是谁"，不回答"他能不能用这条链接"</h3>
 *
 * <p>链接的可用性（存在 / 已撤销 / 已过期 / 已停用）是 {@code PublicTokenResolver} 的事 （{@code 08 §4.2.3}）。
 *
 * <p>★★ 而<strong>账号自己的状态</strong>（{@code iam_user.status != ACTIVE}）在<strong>这里</strong>判 （{@code
 * 08 §4.4.4} 末尾那条产品口径）：主体账号被停用 ⇒ 它发出的公开链接<strong>不再返回任何内容</strong> （fail-closed：载入不出 ⇒ {@link
 * AccessScopePort#asSubject} 装 {@link ScopeFilter#nobody()}）。 理由：账号停用最常见的原因是<strong>离职 /
 * 处置</strong>，若它的链接继续有效，等于"停用账号"不起作用。
 *
 * <p>★ 与"链接失效"分开是有意的，观感差别也如实写在这里：链接失效 ⇒ 访客看到 {@code 40401}–{@code 40404} 四态文案之一； 账号停用 ⇒
 * 链接<strong>打得开</strong>，但<strong>没有内容</strong>。把后者也做成"链接已失效"要新增第五个码与一处自动撤销， 那是 {@code 13 §5.3} 的读法
 * (b)，MVP 明确不采用（采用 (a)）。
 */
public interface SubjectIdentityPort {

  /**
   * 载入一个主体的身份。
   *
   * <p>★ 返回 {@link Optional#empty()} 的三种情形（都是 <strong>fail-closed</strong>，调用方据此"什么都看不见"）：
   *
   * <ol>
   *   <li>账号不存在、或已<strong>软删</strong>（{@code iam_user.deleted_at} 非空）；
   *   <li>★ 账号<strong>不是 ACTIVE</strong>（停用 / 待审核）—— 见类注释那条产品口径；
   *   <li>它的角色行读不出来（身份<strong>不完整</strong>）—— 一条身份只读出一半就按"读不出来"处理，
   *       <strong>不</strong>退回任何默认档：默认档会让"角色被误删"表现成"这个人按最窄范围继续看得到东西"。
   * </ol>
   *
   * <p>★ 调用方<strong>不许</strong>把 {@code empty} 读成"照抄当前请求者" —— 匿名面上没有"请求者"可抄， 而抄出来的要么是 {@link
   * ScopeFilter#nobody()}、要么是一条静默的越权（{@code 08 §4.4.4} 判据 ③ 的金丝雀）。
   *
   * @param subjectId 主体（公开面 = 凭证 / 分享的<strong>归属人</strong> userId）；{@code null} ⇒ {@code empty}
   */
  Optional<SubjectIdentity> load(UUID subjectId);

  /**
   * 主体身份 —— 构造 {@link ScopeFilter} 需要的<strong>材料</strong>（不是范围本身）。
   *
   * <p>★ 为什么是"材料"而不是直接返回 {@link ScopeFilter}：部门链要在这里<strong>展开一次</strong> （{@code 07 §十一}
   * 消费位③），而展开的实现只有一处（{@code AccessScopeAdapter.myOrgIds}）—— 端口返回<strong>单个</strong> {@link
   * #orgId}，展开留给构造范围那一处， 于是"登录面读令牌 / 公开面读库" 两条来源汇进<strong>同一个</strong>构造器（{@code 08 §4.4.4} 纪律 4）。
   *
   * @param userId 主体 id（= {@code iam_user.id}）
   * @param dataScope 角色上的数据范围（自己的 / 被共享的 / 全平台）
   * @param roleCodes 角色代码（{@code iam_role.code}，不是 id —— 与令牌里的 {@code role} 声明同名同值）。 ★ MVP 一人一角色 ⇒
   *     通常一个元素；用集合是因为{@code ScopeFilter.myRoles} 本来就是集合， 而"以后一人多角色"不该变成改签名
   * @param clearance 角色上的密级上限（{@code iam_role.max_classification}）。★ 与知识库的 {@code
   *     maxClassification}（ceiling，"这个库允许装多密的"）正交
   * @param clLabels 可涉的密级标签；★ 空集表示<strong>不按标签限制</strong>（不是"什么标签都看不到"）
   * @param orgId 所属部门；未归类时为 {@code null}（⇒ 看不到任何部门共享，fail-closed）
   */
  record SubjectIdentity(
      UUID userId,
      DataScope dataScope,
      Set<String> roleCodes,
      ClassificationLevel clearance,
      Set<String> clLabels,
      UUID orgId) {

    /**
     * 集合一律<strong>防御性拷贝</strong>：身份是"构造范围的材料"，而范围会在多个请求里被并发读；
     * 一个可变集合漏出去，症状是"某个请求改了角色集合，另一个请求的可见范围跟着变"，且不报错。
     */
    public SubjectIdentity {
      roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
      clLabels = clLabels == null ? Set.of() : Set.copyOf(clLabels);
    }
  }
}
