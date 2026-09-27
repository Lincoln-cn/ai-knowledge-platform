package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@link AccessScopePort} 的**唯一实现**（{@code 08 §4.4.2}）。
 *
 * <p>★ "可见性判定只在后端一处实现"（{@code 07 §3.3}）这句话要有<em>一个具体的类</em>来承担， 否则它只是一句共识。这个类就是那一处 —— 而 {@code
 * AccessScopeUniquenessTest} 会用一条 架构规则保证"第二处实现出现时构建失败"。
 *
 * <p>★ <strong>零查询</strong>：范围、密级上限、标签都从**令牌**里读（{@code JwtTokenService} 签发时写进去）。代价与权限点一样 ——
 * 改了角色的范围或密级，最长 {@code accessTtl}（30 分钟） 才生效。相比"每个请求查一次角色表"，这笔账在本项目里是划算的。
 *
 * <p>★ 未认证时返回 {@link ScopeFilter#nobody()}：**什么都看不见**。 特别注意它<em>不是</em>"看得见 public 的资源" ——
 * 免登录面走的是公开凭证那条独立链路， 不经过这个判定（{@code 07 §3.1}：{@code public} 是"登录用户里人人可见"）。
 */
@Component
public class AccessScopeAdapter implements AccessScopePort {

  private static final Logger log = LoggerFactory.getLogger(AccessScopeAdapter.class);

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

  private static ScopeFilter fromJwt(Jwt jwt) {
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
        parseLabels(jwt.getClaimAsStringList("clLabels")));
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
      return DataScope.own;
    }
    try {
      return DataScope.valueOf(raw);
    } catch (IllegalArgumentException e) {
      log.warn("令牌里的 scope 无法识别：{} ⇒ 按最窄范围 own 处理", raw);
      return DataScope.own;
    }
  }

  /** 同上：读不出密级上限时取最低档（只能看"公开"）。 */
  private static ClassificationLevel parseClearance(String raw) {
    if (raw == null) {
      log.warn("令牌里没有 clearance 声明 ⇒ 按最低密级处理");
      return ClassificationLevel.公开;
    }
    try {
      return ClassificationLevel.valueOf(raw);
    } catch (IllegalArgumentException e) {
      log.warn("令牌里的 clearance 无法识别：{} ⇒ 按最低密级处理", raw);
      return ClassificationLevel.公开;
    }
  }

  private static Set<String> parseLabels(List<String> raw) {
    return raw == null ? Set.of() : Set.copyOf(raw);
  }
}
