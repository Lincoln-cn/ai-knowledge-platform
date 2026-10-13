package cn.lincoln.aiplatform.platform.access;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 数据范围与密级的<strong>判定端口</strong>（{@code 08 §4.4.2}）。
 *
 * <p>★ 接口放 {@code platform}、实现放 {@code identity}，是这份设计的核心取舍：
 *
 * <table>
 *   <tr><th>选择</th><th>结果</th></tr>
 *   <tr><td>端口在 platform</td><td>所有模块已依赖 platform ⇒ {@code 01} 的依赖矩阵<strong>零改动</strong></td></tr>
 *   <tr><td>实现在 identity</td><td>"可见性判定只在后端一处实现"有<strong>一个具体的类</strong>来承担，可评审、可测试</td></tr>
 * </table>
 *
 * <p>★ 实现必须<strong>唯一</strong>：判据见 {@code AccessScopeAdapter} 的类注释与 {@code
 * AccessScopeUniquenessTest} 的金丝雀。第二处实现出现时，构建会红 —— 因为"两处判定"意味着迟早有一处不更新，而它不会报错。
 */
public interface AccessScopePort {

  /**
   * 当前请求的可见范围。
   *
   * <p>★ 调用方应把返回的范围<strong>拼进查询</strong>（{@code Specification}）， 而不是查完再筛 —— 后者会让 {@code total}
   * 与分页数全错： 用户看到"共 100 条"却只翻得出 3 条（{@code 08 §4.4.3} 纪律 1）。
   */
  ScopeFilter currentScope();

  /** 单条资源是否可见。★ 看不见的资源要返回 <strong>404</strong> 而不是 403（纪律 2）。 */
  boolean canSee(OwnedResource resource);

  /**
   * 以<strong>委派范围</strong>执行一段取数逻辑，返回它的结果（第 16 周 · 0.4.4 新增）。
   *
   * <h3>它解决的是什么</h3>
   *
   * <p>免登录面（公开凭证）没有当前用户，却必须<strong>用链接自己的冻结密级重新过滤一遍</strong> （{@code 13 §5.2} 第 ② 级）。而密级谓词在 SQL
   * 里读的是 {@code ScopeFilter}（{@code JdbcSegmentSearchQuery.classificationRank()}）——
   * 于是"以另一个范围跑一次查询"必须有一个 <strong>类型化</strong>的入口。★ 没有它，调用方只能自己往 {@code SecurityContext} 里塞一个 手工构造的
   * JWT —— 那是同一段构造逻辑的<strong>第二处实现</strong>（而它会在下次改声明名时不跟着改）。
   *
   * <h3>★ 只许<strong>收窄</strong>，绝不放大</h3>
   *
   * <p>实现必须把委派范围与当前请求者的范围<strong>求交</strong>，且方向是收窄：
   *
   * <ul>
   *   <li>密级取两者中<strong>更低</strong>的那个（{@link ClassificationLevel#narrower}）—— 一条冻结了 {@code SECRET}
   *       的链接被一个 {@code INTERNAL} 的人解析时，生效的必须是 {@code INTERNAL}；
   *   <li>数据范围、角色、标签、部门链<strong>照抄当前请求者</strong>。
   * </ul>
   *
   * <p>★ 这条纪律的理由与 {@code fail-closed} 同源：把一个范围"合并"成更宽的那种写法，
   * 症状是<strong>看不见的放大</strong>（链接能看到的比它能创建的人还多），而且不报错。
   *
   * @param delegated 链接希望行使的范围（通常由 {@code PublicTokenResolver} 给出）
   * @param requesterId ★ 发起解析的<strong>已登录用户</strong>；未登录传 {@code null}。
   *     实现必须校验它与当前请求身份是<strong>同一个人</strong> —— 否则这里就成了"以任意人身份取数"的后门
   * @param body 要执行的取数逻辑
   */
  default <T> T asScope(ScopeFilter delegated, UUID requesterId, Supplier<T> body) {
    // ★ 默认实现是"明确拒绝"而不是"直接执行 body"：后者会让一个没写这个方法的实现
    //   静默地把委派**当成没发生**（取数用的是请求者自己的范围）—— 那是一条不会报错的越权。
    throw new UnsupportedOperationException("这个 AccessScopePort 实现不支持委派范围（asScope）");
  }

  /**
   * 以<strong>某个主体的范围</strong>执行一段取数逻辑，返回它的结果（第 17 周要用 · {@code #92}）。
   *
   * <h3>为什么必须有第二个入口（不能复用 {@link #asScope}）</h3>
   *
   * <p>公开面（访问凭证 / 分享链接）的<strong>正常使用者是未登录访客</strong>，而 {@code asScope} 的正当性证明是
   * "发起人必须是当前登录用户"（{@code requesterId} 等于 JWT 的 {@code sub}）—— 访客没有 JWT ⇒ <strong>这条路走不通</strong>。
   *
   * <p>根因是<strong>两种正当性来源被混成了一个参数</strong>：登录面证明的是"证明你是你"， 公开面证明的是"**令牌本身有效**"（存在 / 未撤销 / 未过期 /
   * 未停用）。 ⇒ 本入口只认可后一种，而<strong>证明由谁做</strong>写在下面第 2 条。
   *
   * <h3>三条纪律（{@code 08 §4.4.4}）</h3>
   *
   * <ol>
   *   <li>★★ <strong>服务端按 {@code subjectId} 载入该主体的身份</strong>（角色 / 数据范围 / 部门链）再构造范围 ——
   *       <strong>绝不照抄请求者</strong>。匿名面上"照抄请求者"没有定义：照抄的结果要么是 {@link
   *       ScopeFilter#nobody()}（什么都看不见），要么迫使调用方自己拼一份范围（= 第二处构造）。
   *   <li><strong>正当性怎么证明由调用方负责</strong>，且两面的证明方式不同：公开面 = {@code PublicTokenResolver} 解析成功；登录面 =
   *       {@code asScope} 的 {@code requesterId} 校验。 本入口<strong>不做</strong>令牌校验，也<strong>不</strong>把
   *       {@code SecurityContext} 当作正当性来源 —— 匿名是<strong>完全支持</strong>的一条路，不是"降级"。 ★ 第 17
   *       周实现时补了一条（见下一段）：有登录调用者时它会<strong>读一次</strong>当前认证上下文，但<strong>只用于收窄</strong>。
   *   <li><strong>密级取冻结值</strong>，且与主体的 clearance 求<strong>更窄</strong> （{@link
   *       ClassificationLevel#narrower}，与 {@code asScope} 同源）。
   * </ol>
   *
   * <p>★ <strong>登录调用者由实现收窄到"更窄的那一份"</strong>：一条链接被<strong>登录用户</strong>打开时， 生效范围是「归属人范围 ∩ 冻结密级 ∩
   * <strong>调用者自身范围</strong>」里更窄的那一份 —— 否则登录用户可以借别人的链接拿到超出自己 clearance
   * 的内容，而它<strong>不会报错</strong>（本项目头号病 {@code silent-failure}）。
   *
   * <p>★★ <strong>这一条由实现保证，而不是留给调用方记得做</strong>（{@code 08 §4.4.4} 纪律 3 的原始措辞是 "调用方必须先算"）：收窄与 {@code
   * asScope} 用的是<strong>同一个</strong>私有实现 —— 调用方忘了算、 或者图省事对所有人都调 {@code
   * asSubject}，结果都<strong>不会</strong>放大。 代价是它读一次 {@code SecurityContext}（只用于收窄、 从不用于正当性），取舍写在实现类
   * {@code asSubject} 的注释里。
   *
   * <p>★ 两个入口<strong>共用同一个"构造范围"的实现</strong>（{@code 08 §4.4.4}）： 所以"可见性判定只有一处"（{@code 07
   * §3.3}）这条纪律不变，变的只是"谁有资格调它"。
   *
   * @param subjectId 要行使其范围的主体（公开面 = 凭证 / 分享的<strong>归属人</strong> userId）
   * @param frozenClearance 链接创建时冻结的密级上限（{@code share.maxClassification}，{@code 13 §5.2} ①）
   * @param body 要执行的取数逻辑
   */
  default <T> T asSubject(UUID subjectId, ClassificationLevel frozenClearance, Supplier<T> body) {
    // ★ 与 asScope 同一条理由：默认实现是"明确拒绝"而不是"直接执行 body" ——
    //   后者会让一个没写这个方法的实现把委派**当成没发生**（取数用的是调用者自己的范围），
    //   而公开面上那意味着"访客看到了调用者的东西"或"什么都看不到"，两种都不报错。
    throw new UnsupportedOperationException("这个 AccessScopePort 实现不支持公开面的主体委派（asSubject）");
  }
}
