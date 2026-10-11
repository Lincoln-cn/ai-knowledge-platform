package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

  private final OrgDirectoryPort orgDirectory;

  public AccessScopeAdapter(OrgDirectoryPort orgDirectory) {
    this.orgDirectory = orgDirectory;
  }

  @Override
  public ScopeFilter currentScope() {
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

  // ────────────────────────── 内部 ──────────────────────────

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
  }

  private ScopeFilter fromJwt(Jwt jwt) {
    UUID me = parseUuid(jwt.getSubject());
    if (me == null) {
      // 令牌没有 sub（理论上不该发生）⇒ 当作未认证，而不是"无限制"
      return ScopeFilter.nobody();
    }

    String roleCode = jwt.getClaimAsString("role");
    Set<String> myRoles = roleCode == null ? Set.of() : Set.of(roleCode);

    return new ScopeFilter(
        me,
        parseDataScope(jwt.getClaimAsString("scope")),
        myRoles,
        parseClearance(jwt.getClaimAsString("clearance")),
        parseLabels(jwt.getClaimAsStringList("clLabels")),
        myOrgIds(jwt));
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
   * <p>★ 解析不出部门（没这个声明 / 不是合法 UUID）⇒ 返回<strong>空集</strong>， 不是"不限"。方向是刻意的：漏配只会"少看见"，不会"多看见"。
   */
  private Set<String> myOrgIds(Jwt jwt) {
    UUID myOrg = parseUuid(jwt.getClaimAsString("orgId"));
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
