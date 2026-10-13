package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.SubjectIdentityPort;
import cn.lincoln.aiplatform.platform.access.SubjectIdentityPort.SubjectIdentity;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@link AccessScopePort} 的**唯一实现**（{@code 08 §4.4.2}）。
 *
 * <p>★ "可见性判定只在后端一处实现"（{@code 07 §3.3}）这句话要有<em>一个具体的类</em>来承担， 否则它只是一句共识。这个类就是那一处 —— 而 {@code
 * AccessScopeUniquenessTest} 会用一条 架构规则保证"第二处实现出现时构建失败"。
 *
 * <p>★ <strong>几乎零查询</strong>：范围、密级上限、标签都从**令牌**里读（{@code JwtTokenService} 签发时写进去）。代价与权限点一样 ——
 * 改了角色的范围或密级，最长 {@code accessTtl}（30 分钟） 才生效。相比"每个请求查一次角色表"，这笔账在本项目里是划算的。
 *
 * <p>★ 唯一的那一次查询是"<strong>我的部门及其全部上级</strong>"（{@code 07 §十一} 消费位③）：令牌里只有我这一个部门 id，祖先链在
 * <strong>构造范围时</strong>展开一次，并缓存在<strong>请求作用域</strong>上（同一请求内 {@link #currentScope()} 调几次都只查一次）。
 * <em>方向</em>是这里最容易搞反的地方，理由见 {@link #myOrgIds(Jwt)}。
 *
 * <p>为什么不在签发令牌时就把祖先链塞进令牌：链长与部门树的深度耦合（令牌体积会随组织变化）， 而且那份集合会被冻结到令牌过期 —— 上级部门被挂到更深的层级下也要等 30 分钟。
 *
 * <p>★ 未认证时返回 {@link ScopeFilter#nobody()}：**什么都看不见**。 特别注意它<em>不是</em>"看得见 public 的资源" ——
 * 免登录面走的是公开凭证那条独立链路， 不经过这个判定（{@code 07 §3.1}：{@code public} 是"登录用户里人人可见"）。
 *
 * <p>★ <strong>两个委派入口（{@code 08 §4.4.4}）</strong>：{@link #asScope} 面向<strong>登录面</strong> （正当性 =
 * "发起人就是当前登录用户"），{@link #asSubject} 面向<strong>公开面</strong> （正当性 = 调用方已经用 {@code
 * PublicTokenResolver} 证明令牌有效，本类不复核）。 两者<strong>共用同一个"构造范围"实现</strong>（{@link
 * #buildScope}）与<strong>同一个"收窄 + 装上"实现</strong>（{@link #narrow} / {@link #withScope}）——
 * 所以"判定只有一处"这条纪律不变，变的只是"谁有资格调它"、以及"身份从哪里来"（登录面读令牌、公开面按 {@code subjectId} 读库）。
 *
 * <p>★ {@link #asSubject} 已于第 17 周落地（{@code #92}）：<strong>载入主体身份</strong>（角色 / 数据范围 / 密级 / 部门） 走
 * {@link SubjectIdentityPort}，而它<strong>不许</strong>退化成"照抄当前请求者" —— 匿名面上没有"请求者"可抄。
 */
@Component
public class AccessScopeAdapter implements AccessScopePort {

  private static final Logger log = LoggerFactory.getLogger(AccessScopeAdapter.class);

  /**
   * 请求作用域缓存的键（"我的部门及其全部上级"的 id 集合）。
   *
   * <p>★ 缓存挂在请求属性上、而不是字段/静态变量：字段会跨请求串号（adapter 是单例）， 而 {@code ThreadLocal}
   * 在池化线程上需要自己清、漏清就把一个人的可见部门带给下一个人。
   *
   * <p>★ 值里带着"这条链属于**哪个**部门"：只按请求缓存的话，同一请求里身份变过（令牌刷新、或测试里换个人） 就会沿用前一个身份的部门链 —— 那是一次"看到别人部门"的静默越权。带上
   * orgId 之后身份一变必然 cache miss。 （★ 这不是理论风险：{@code OrgScopeWiringTest} 第一版就是"同一线程里连着问了两个人的范围"，
   * 它当场读到的是**上一个人的**部门链。）
   */
  private static final String MY_ORG_IDS_ATTRIBUTE =
      AccessScopeAdapter.class.getName() + ".myOrgIds";

  /** 请求作用域缓存的值：{@code orgId} 回答"这条链是谁的"。 */
  private record OrgChain(UUID orgId, Set<String> ids) {}

  /**
   * ★ 当前生效的<strong>委派范围</strong>（{@link #asScope} 装上的那一份）。
   *
   * <p>★ 用 {@code ThreadLocal} 而不是"往 {@code SecurityContext} 里塞一个合成 JWT"：合成出来的令牌
   * 是一份**可以自己造**的凭据，而这里要回答的问题只有一个 —— "当前生效的范围是哪一份"。 存 {@link ScopeFilter}
   * 同时让"密级是冻结值、其余分量照抄请求者"这件事<strong>逐字</strong>成立 （不必把 {@code myOrgIds} 那种已展开的集合塞回单个 {@code orgId}
   * 再展开一遍）。
   *
   * <p>★ 必须成对清除（{@code try/finally}）：它是 {@code ThreadLocal}，而请求线程是池化的 —— 漏清就把一条链接的冻结密级带给下一个请求（与
   * {@code MY_ORG_IDS_ATTRIBUTE} 那条注释同一个理由）。
   */
  private static final ThreadLocal<ScopeFilter> DELEGATED = new ThreadLocal<>();

  private final OrgDirectoryPort orgDirectory;
  private final SubjectIdentityPort subjectIdentities;

  public AccessScopeAdapter(OrgDirectoryPort orgDirectory, SubjectIdentityPort subjectIdentities) {
    this.orgDirectory = orgDirectory;
    this.subjectIdentities = subjectIdentities;
  }

  @Override
  public ScopeFilter currentScope() {
    // ★ 委派期间读委派那一份：否则 asScope 里再问一次 currentScope() 会立刻丢掉冻结密级
    //   （症状是"冻结值只在最外层那个查询生效"，而内层照旧用请求者自己的密级）
    ScopeFilter delegated = DELEGATED.get();
    if (delegated != null) {
      return delegated;
    }
    Jwt jwt = currentJwt();
    if (jwt == null) {
      return ScopeFilter.nobody();
    }
    return fromJwt(jwt);
  }

  @Override
  public boolean canSee(OwnedResource resource) {
    return currentScope().canSee(resource, Instant.now());
  }

  /**
   * {@inheritDoc}
   *
   * <p>★★ 本实现的三条纪律（每一条都对应一种"静默放大"）：
   *
   * <ol>
   *   <li><strong>发起人必须是当前请求者</strong>：{@code requesterId} 与 JWT 的 {@code sub} 不同 ⇒ 拒。 少了这一句，{@link
   *       AccessScopePort#asScope} 就是"以任意人身份取数"的后门 —— 而它<strong>不会报任何错</strong>（取数照常成功）。
   *   <li><strong>密级取更低</strong>：{@code narrower(委派值, 当前请求者值)}。取反了也能跑， 症状是"用别人给的链接能看到比自己更高密级的内容"。
   *   <li><strong>其余分量照抄请求者</strong>：数据范围 / 角色 / 标签 / 部门链一律用当前范围 ——
   *       委派只允许在<strong>密级</strong>这一维上更窄。这一条是"别把委派做成第二个范围构造器"的落实。
   * </ol>
   *
   * <p>★ 它<strong>不</strong>自己去拼一份共享的 {@code ScopeFilter}：密级之外的分量（数据范围 / 角色 / 标签 /
   * 部门链）一律照抄当前请求者，而"当前请求者是谁"只有一条解析路径（{@link #fromJwt}）； "把两者合成一份"也只有一条路径（{@link #narrow}）。
   *
   * <p>★ 委派期间 {@code currentScope()} 直接返回这一份（见 {@link #DELEGATED}）。
   *
   * <p>⚠️ 本批（0.4.4）没有 HTTP 公开端点，所以调用点只有服务层；第 17 周接公开面时，
   * <strong>撤销必须与解析在同一入口</strong>（否则"同一个请求周期内撤销即失效"这条判据做不到）。
   */
  @Override
  public <T> T asScope(ScopeFilter delegated, UUID requesterId, Supplier<T> body) {
    Jwt current = currentJwt();
    UUID currentUserId = current == null ? null : parseUuid(current.getSubject());
    if (requesterId == null || !requesterId.equals(currentUserId)) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, "无法以别人的身份解析公开令牌", "委派范围只能由发起解析的那个登录用户行使；请重新登录后再试");
    }
    // ★ 收窄与"装上"都只有一处实现（narrow / withScope），与 asSubject 共用 —— 见它们的注释
    return withScope(narrow(delegated, fromJwt(current)), body);
  }

  /**
   * {@inheritDoc}
   *
   * <p>★★ <strong>公开面的入口（第 17 周实现，{@code 08 §4.4.4} · Issue {@code #92}）</strong>。与 {@link
   * #asScope} 的差别<strong>只有一条</strong>：<strong>正当性怎么证明</strong>。
   *
   * <table>
   *   <tr><th>入口</th><th>正当性来源</th><th>身份从哪来</th></tr>
   *   <tr><td>{@link #asScope}</td><td>"发起人 = 当前登录用户"（{@code requesterId} 等于 JWT 的 {@code sub}）</td><td>当前请求者</td></tr>
   *   <tr><td>{@code asSubject}</td><td>★ <strong>由调用方证明</strong>：公开面 = 令牌解析成功（{@code PublicTokenResolver}）；<strong>本类不复核</strong></td><td>★
   *       <strong>按 {@code subjectId} 载入该主体本人</strong>（角色 / 数据范围 / 部门链）</td></tr>
   * </table>
   *
   * <p>★★ <strong>为什么必须重载而不是照抄</strong>：公开面的正常使用者是<strong>未登录访客</strong>；"照抄当前请求者"在匿名面上没有定义 ——
   * 照抄的结果是 {@link ScopeFilter#nobody()}（免责地什么都看不见，而这与"令牌有效却查不到"不可区分）。 <strong>密级仍取冻结值</strong>，方向仍是
   * {@link ClassificationLevel#narrower}（与 {@code asScope} 同源：只许收窄）。
   *
   * <p>★ <strong>与 {@code asScope} 共用同一个"构造范围"的实现</strong>（{@code 08 §4.4.4} 纪律 4）：那就是私有方法 {@link
   * #buildScope}（身份材料 + 生效密级 ⇒ 一份 {@code ScopeFilter}）。两个入口的身份<strong>来源</strong>不同 （登录面读令牌 {@link
   * #fromJwt}、公开面按 {@code subjectId} 读库 {@link SubjectIdentityPort}）， 但"材料 ⇒
   * 范围"这一步<strong>物理上只有一份</strong> —— 将来范围多加一个分量时只会有一个改动点。
   *
   * <p>⚠️ <strong>两件"本方法必须<em>不</em>做"的事</strong>（做了都会造出静默放大）：
   *
   * <ol>
   *   <li><strong>不校验令牌</strong>：令牌有效性（存在 / 未撤销 / 未过期 / 未停用）是 {@code PublicTokenResolver} 的职责；
   *       在这里再判一次会造成"两处都判、口径不同"。撤销 / 过期时<strong>本方法照常构造范围</strong>，可见内容为 <strong>空</strong> ——
   *       这是有意的 fail-closed：错误由解析入口报，见 {@code 13 §5.2} 第 ② 级。
   *   <li><strong>不回落到"照抄调用者"</strong>：主体载入不出来（不存在 / 已删 / 角色缺失）时返回 {@link ScopeFilter#nobody()}，
   *       <strong>绝不</strong>退回当前请求者的范围。★ 金丝雀：把这一句改成"照抄当前请求者" ⇒ 匿名面（不带 {@code Authorization}
   *       头）上"用有效凭证检索"那条判据必须<strong>变红</strong>（{@code 08 §4.4.4} 判据 ③）。
   * </ol>
   *
   * <p>★★ <strong>登录调用者由本方法自己收窄</strong>（{@code 08 §4.4.4} 判据 ② / 纪律 3）：有认证上下文时， 生效范围再经 {@link
   * #narrow} 压到<strong>不大于调用者自身那一份</strong> —— 收窄用的<strong>就是</strong> {@code asScope}
   * 那一条（同一个私有方法，"其余分量照抄调用者 + 密级取更低"），所以没有第二套合并规则。
   *
   * <p>★ 这一条比"要求调用方先算"更强：调用方忘了算、或者图省事对所有人都调 {@code asSubject}， 结果都<strong>不会</strong>放大 ——
   * 而"忘了算"这件事的症状正是本项目头号病（不报错的越权）。 ★ 代价与口径偏差写在交付报告里（本条与 {@code 08 §4.4.4} 纪律 5 的字面"不读 {@code
   * SecurityContext}" 不一致：这里<strong>读</strong>它，但只用于<strong>收窄</strong>，从不用于<strong>正当性</strong> ——
   * 匿名时读不到就是匿名）。
   */
  @Override
  public <T> T asSubject(UUID subjectId, ClassificationLevel frozenClearance, Supplier<T> body) {
    SubjectIdentity subject = subjectIdentities.load(subjectId).orElse(null);
    if (subject == null) {
      // ★ 判据③ 守的就是这一句：载入不出来 ⇒ nobody()，绝不退回"当前请求者那一份"
      log.warn("公开面的主体 {} 载入不出身份 ⇒ 本次委派什么都看不见（fail-closed）", subjectId);
      return withScope(ScopeFilter.nobody(), body);
    }
    // ★ 密级仍取冻结值，且与主体自己的 clearance 求更窄（08 §4.4.4 纪律 2）：一条冻结 INTERNAL 的链接
    //   被一个 SECRET 的归属人打开，生效的仍是 INTERNAL
    ScopeFilter linkScope =
        buildScope(subject, ClassificationLevel.narrower(frozenClearance, subject.clearance()));

    ScopeFilter caller = currentCallerScope();
    if (caller == null) {
      // ★ 判据①：匿名访客 —— 生效的就是主体那一份（没有"请求者"可抄，这正是 #92 的病根）
      return withScope(linkScope, body);
    }
    // ★ 判据②：登录用户拿同一条链接不放大
    return withScope(narrow(linkScope, caller), body);
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
  }

  /**
   * ★★ <strong>唯一的"构造范围"实现</strong>（{@code 08 §4.4.4} 纪律 4）：身份材料 + 生效密级 ⇒ 一份 {@link ScopeFilter}。
   *
   * <p>两个入口的身份来源不同（登录面 {@link #identityOf} 读令牌、公开面 {@link SubjectIdentityPort} 读库）， 但"材料 ⇒
   * 范围"这一步只有这一份 —— 所以"可见性判定只有一处"（{@code 07 §3.3}）在<strong>构造侧</strong>也成立。
   * 谁想给范围加一个分量（例如将来的"部门档"），改动点只有这里一行；抄成两份的话，第二份不会跟着改， 而症状是"某个入口能看见不该看见的东西"（{@code
   * AccessScopeUniquenessTest} 守不住这一点 —— 它只看"实现有几个类"）。
   *
   * <p>★ 部门链的<strong>展开</strong>也收在这里（{@link #myOrgIds}）：两条来源给的都是"我一个部门 id"，
   * 展开成"我的部门及全部上级"这件事只发生一次。
   */
  private ScopeFilter buildScope(SubjectIdentity identity, ClassificationLevel clearance) {
    return new ScopeFilter(
        identity.userId(),
        identity.dataScope(),
        identity.roleCodes(),
        clearance,
        identity.clLabels(),
        myOrgIds(identity.orgId()));
  }

  /**
   * ★★ <strong>唯一的"收窄"实现</strong>：把 {@code requested}（链接 / 委派希望行使的范围）压到不大于 {@code
   * caller}（当前登录者）那一份。
   *
   * <p>三条纪律（每一条都对应一种"静默放大"）：
   *
   * <ol>
   *   <li><strong>{@code me} 取 {@code requested}</strong>：取数行使的是"链接那一份"的身份（公开面 = 归属人）；
   *   <li><strong>密级取更低</strong>：{@link
   *       ClassificationLevel#narrower}。取反了也能跑，症状是"用别人给的链接能看到比自己更高密级的内容"；
   *   <li><strong>其余分量照抄调用者</strong>：数据范围 / 角色 / 标签 / 部门链一律用调用者自己的 ——
   *       委派只允许在<strong>密级</strong>这一维上更窄。这一条是"别把委派做成第二个范围构造器"的落实。
   * </ol>
   *
   * <p>★ 它被 {@link #asScope}（无条件）与 {@link #asSubject}（有登录调用者时）共用 —— 两处若各写一遍， 迟早有一处忘了取更低。
   *
   * <p>★ <strong>读不出调用者身份</strong>（{@code me == null}，理论上只有"有认证上下文却没有 sub"这一种）⇒ 返回 {@link
   * ScopeFilter#nobody()}：这是 fail-closed，而<strong>不是</strong>"当作匿名、把链接那一份给出去" ——
   * 后者会让一个半截令牌反而拿到归属人的范围。
   */
  private static ScopeFilter narrow(ScopeFilter requested, ScopeFilter caller) {
    if (caller == null || caller.me() == null) {
      return ScopeFilter.nobody();
    }
    return new ScopeFilter(
        requested.me(),
        caller.scope(),
        caller.myRoles(),
        ClassificationLevel.narrower(requested.maxClassification(), caller.maxClassification()),
        caller.clLabels(),
        caller.myOrgIds());
  }

  /**
   * 把一份范围装上（{@link #DELEGATED}），执行取数逻辑后<strong>成对还原</strong>。
   *
   * <p>★ 记住"上一份范围"而不是上一份令牌：能自己给自己签发的令牌是伪造面。 而这里需要回答的问题只有一个 —— "当前生效的范围是哪一份"。
   *
   * <p>★ 必须成对清除（{@code try/finally}）：它是 {@code ThreadLocal}，而请求线程是池化的 —— 漏清就把一条链接的冻结密级带给下一个请求（与
   * {@link #MY_ORG_IDS_ATTRIBUTE} 那条注释同一个理由）。
   */
  private static <T> T withScope(ScopeFilter scope, Supplier<T> body) {
    ScopeFilter previous = DELEGATED.get();
    DELEGATED.set(scope);
    try {
      return body.get();
    } finally {
      if (previous == null) {
        DELEGATED.remove();
      } else {
        DELEGATED.set(previous);
      }
    }
  }

  /**
   * 当前调用者自己的范围；<strong>匿名</strong>时返回 {@code null}（"没有调用者这一维"）。
   *
   * <p>★★ 这一条是 {@code asSubject} 与 {@code 08 §4.4.4} 纪律 5 的<strong>唯一</strong>偏差，理由写在这里：
   * 登录用户拿别人的链接时，生效范围必须是「归属人范围 ∩ 冻结密级 ∩ 调用者自身范围」里更窄的那一份（判据 ②）。 把它留给"调用方自己算"意味着：调用方忘了算、或图省事对所有人都调
   * {@code asSubject}，就会<strong>静默</strong>放大 ——
   * 而"不报错的越权"正是本项目头号病。所以这里读一次认证上下文，但<strong>只用于收窄</strong>：
   *
   * <ul>
   *   <li>不用于<strong>正当性</strong>：匿名（{@code null}）是<strong>完全支持</strong>的一条路，不是"降级"；
   *   <li>不用于<strong>校验令牌</strong>：令牌有效性仍是 {@code PublicTokenResolver} 的事。
   * </ul>
   *
   * <p>★ 在委派期间调用它拿到的是<strong>真实的当前请求者</strong>（读令牌，不读 {@link #DELEGATED}）—— 那正是这条收窄要压的对象。
   */
  private ScopeFilter currentCallerScope() {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      return null;
    }
    SubjectIdentity identity = identityOf(jwt);
    // ★ 有认证上下文却读不出身份（sub 缺失 / 不是 UUID）⇒ nobody()，**不**当作匿名放过去
    return identity == null ? ScopeFilter.nobody() : buildScope(identity, identity.clearance());
  }

  private ScopeFilter fromJwt(Jwt jwt) {
    SubjectIdentity identity = identityOf(jwt);
    // 令牌没有 sub（理论上不该发生）⇒ 当作未认证，而不是"无限制"
    return identity == null ? ScopeFilter.nobody() : buildScope(identity, identity.clearance());
  }

  /**
   * 令牌声明 ⇒ 身份材料（与 {@link SubjectIdentityPort} 返回的是<strong>同一个</strong> record）。
   *
   * <p>★ 两条身份来源在形状上必须是同一件事，否则"共用同一个构造器"（{@link #buildScope}）立刻退化成两个重载。
   * 差别只在<strong>值从哪来</strong>：这里从令牌（零查询），公开面从库（一次查询）。
   *
   * <p>★ 读不出 {@code sub} ⇒ {@code null}（调用方按"未认证"处理）；其余声明读不出时按<strong>最窄档</strong>兜底 （见 {@link
   * #parseDataScope} / {@link #parseClearance}）—— 方向是刻意的：兜底只会"少看见"。
   */
  private static SubjectIdentity identityOf(Jwt jwt) {
    UUID me = parseUuid(jwt.getSubject());
    if (me == null) {
      return null;
    }
    String roleCode = jwt.getClaimAsString("role");
    return new SubjectIdentity(
        me,
        parseDataScope(jwt.getClaimAsString("scope")),
        roleCode == null ? Set.of() : Set.of(roleCode),
        parseClearance(jwt.getClaimAsString("clearance")),
        parseLabels(jwt.getClaimAsStringList("clLabels")),
        parseUuid(jwt.getClaimAsString("orgId")));
  }

  /**
   * "我所属部门 + 它的全部<strong>上级</strong>"（{@code 07 §十一} 消费位③ → {@code ScopeFilter} 的授权基）。
   *
   * <p>★★ <strong>方向（这里最容易搞反，而且两个方向都能跑）</strong>：判定侧写的是 {@code
   * 我的集合.contains(共享目标)}，而共享给「技术部」的含义是「技术部及下级都看得见」（用户口径，{@code 07 §10.5} 判据 1）。
   * "我在技术部的下级里"换一个说法就是"<strong>技术部在我的祖先链上</strong>" ⇒ 集合必须是<strong>祖先链</strong>（含自己）。
   *
   * <p>取成子树（"我的部门 + 下级"）得到的是<strong>镜像语义</strong>："共享给我的下级部门 ⇒ 我看得见" ——
   * 那不是任何一条产品承诺，还会让上级自动看见下级的共享。两者都不报错、症状相反 ⇒ 宁可有这条注释。 {@code AccessScopeOrgScopeTest} 的
   * 金丝雀就是把方向写死：把 {@code ancestorsAndSelf} 换成"只返回我自己" ⇒ 它必须红。
   *
   * <p>★ <strong>一处展开、一次请求</strong>：这里做的那一次前缀查询是按请求缓存的。 绝不要把它挪进 {@code canSee(resource)} ——
   * 那是逐资源调用的（N+1），而"每请求一次"正是本方法存在的理由。
   *
   * <p>★ 解析不出部门（没有部门归属 / 不是合法 UUID）⇒ 返回<strong>空集</strong>， 不是"不限"。方向是刻意的：漏配只会"少看见"，不会"多看见"。
   *
   * <p>★ 参数从 {@code Jwt} 改成 {@code UUID}（第 17 周）：<strong>两条身份来源都要走这一步</strong> （令牌里的 {@code orgId}
   * 声明 / {@code iam_user.org_id}），而"展开成祖先链"只允许有一处实现。
   */
  private Set<String> myOrgIds(UUID myOrg) {
    if (myOrg == null) {
      return Set.of();
    }
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      Object cached =
          attributes.getAttribute(MY_ORG_IDS_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
      if (cached instanceof OrgChain hit && hit.orgId().equals(myOrg)) {
        return hit.ids();
      }
    }
    Set<String> resolved =
        orgDirectory.ancestorsAndSelf(myOrg).stream()
            .map(UUID::toString)
            .collect(Collectors.toUnmodifiableSet());
    if (attributes != null) {
      attributes.setAttribute(
          MY_ORG_IDS_ATTRIBUTE, new OrgChain(myOrg, resolved), RequestAttributes.SCOPE_REQUEST);
    }
    return resolved;
  }

  private static UUID parseUuid(String raw) {
    try {
      return raw == null ? null : UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * 令牌里读不出范围时取 {@code own}。
   *
   * <p>★ 兜底方向必须是<strong>收窄</strong>：取 {@code all} 的话，任何一次令牌结构变更 （漏了
   * claim、rename、序列化问题）都会<em>静默地</em>把所有人变成全平台可见。 而取 {@code own} 的失败是"用户看不见自己的东西" —— 会被立刻报上来。
   */
  private static DataScope parseDataScope(String raw) {
    if (raw == null) {
      log.warn("令牌里没有 scope 声明 ⇒ 按最窄范围 own 处理（是不是签发时漏了？）");
      return DataScope.OWN;
    }
    try {
      return DataScope.valueOf(raw);
    } catch (IllegalArgumentException e) {
      log.warn("令牌里的 scope 无法识别：{} ⇒ 按最窄范围 own 处理", raw);
      return DataScope.OWN;
    }
  }

  /** 同上：读不出密级上限时取最低档（只能看"公开"）。 */
  private static ClassificationLevel parseClearance(String raw) {
    if (raw == null) {
      log.warn("令牌里没有 clearance 声明 ⇒ 按最低密级处理");
      return ClassificationLevel.PUBLIC;
    }
    try {
      return ClassificationLevel.valueOf(raw);
    } catch (IllegalArgumentException e) {
      log.warn("令牌里的 clearance 无法识别：{} ⇒ 按最低密级处理", raw);
      return ClassificationLevel.PUBLIC;
    }
  }

  private static Set<String> parseLabels(List<String> raw) {
    return raw == null ? Set.of() : Set.copyOf(raw);
  }
}
