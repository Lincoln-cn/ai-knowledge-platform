package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.platform.security.ApiKeyAuthenticator;
import cn.lincoln.aiplatform.platform.security.ApiKeyIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-API-Key} 的认证过滤器（{@code #195} 的「可用鉴权链」）。
 *
 * <h3>它做什么</h3>
 *
 * <p>把一把应用 API Key 换成<strong>该应用所有者</strong>的身份：查表（{@link ApiKeyAuthenticator}）→ 取所有者与角色 → 用同一套
 * {@code JwtTokenService} 签发一个<strong>内存里的</strong>令牌 → 解码成 {@link Jwt} → 放进 {@code
 * SecurityContext}。于是 {@code @PreAuthorize}、数据范围、审计全都沿用既有那一套，没有第二条权限判定路径。
 *
 * <p>★ 为什么不直接手搓 {@code Authentication}：{@code AccessScopeAdapter} 是从 JWT 声明读角色/范围/密级的 （见 {@code
 * SubjectIdentityPort} 的注释）。手搓一个只有 {@code sub} 的认证对象会让"范围"退化成空值——那正是 本仓反复记过的"两套判定"坑。
 *
 * <h3>三条边界（都不许放宽）</h3>
 *
 * <ol>
 *   <li><strong>只认启用中的 Key</strong>：停用/未知/应用已软删一律 401，且失败不写 {@code last_used_at}。
 *   <li><strong>与 JWT 互斥</strong>：请求同时带 {@code Authorization} 时本过滤器让路——登录身份优先，API Key
 *       只是"没有登录时的程序化身份"。
 *   <li><strong>Key 绑定单个应用</strong>：把 {@code assistantId} 写进 {@code
 *       Authentication.details}，问答端点据此拒绝"拿 A 的 Key 问 B"（见 {@code ChatController}）。
 * </ol>
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  /** 契约头名（与 {@code ApiKeysPanel} 文案"用于程序化调用"对应）。 */
  public static final String HEADER = "X-API-Key";

  private final ApiKeyAuthenticator authenticator;
  private final UserRepository users;
  private final RoleRepository roles;
  private final RolePermissionRepository rolePermissions;
  private final JwtTokenService tokens;
  private final JwtDecoder decoder;
  private final JwtAuthenticationConverter converter;
  private final RestAuthenticationEntryPoint entryPoint;

  public ApiKeyAuthenticationFilter(
      ApiKeyAuthenticator authenticator,
      UserRepository users,
      RoleRepository roles,
      RolePermissionRepository rolePermissions,
      JwtTokenService tokens,
      JwtDecoder decoder,
      JwtAuthenticationConverter converter,
      RestAuthenticationEntryPoint entryPoint) {
    this.authenticator = authenticator;
    this.users = users;
    this.roles = roles;
    this.rolePermissions = rolePermissions;
    this.tokens = tokens;
    this.decoder = decoder;
    this.converter = converter;
    this.entryPoint = entryPoint;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw = request.getHeader(HEADER);
    // ★ 登录身份优先：同时带 Authorization 时不看 API Key（两条凭据同时出现，按更强的那个解释）。
    if (raw == null
        || raw.isBlank()
        || request.getHeader("Authorization") != null
        || SecurityContextHolder.getContext().getAuthentication() != null) {
      chain.doFilter(request, response);
      return;
    }

    Optional<ApiKeyIdentity> identity = authenticator.authenticate(raw);
    if (identity.isEmpty()) {
      entryPoint.commence(request, response, new BadCredentialsException("API Key 无效或已停用"));
      return;
    }

    Optional<User> owner = users.findById(identity.get().ownerId());
    Optional<Role> role = owner.flatMap(user -> roles.findById(user.roleId()));
    if (owner.isEmpty() || role.isEmpty() || !owner.get().canLogin()) {
      // 所有者被删/停用/角色缺失 ⇒ Key 不再可用；用与"无效 Key"相同的外部读数，避免泄露账号状态。
      entryPoint.commence(request, response, new BadCredentialsException("API Key 无效或已停用"));
      return;
    }

    String token =
        tokens.issueAccessToken(
            owner.get(), role.get(), rolePermissions.findByRoleId(role.get().id()));
    Jwt jwt = decoder.decode(token);
    AbstractAuthenticationToken authentication = converter.convert(jwt);
    // ★ 绑定：问答端点据此拒绝"拿 A 应用的 Key 问 B"。
    authentication.setDetails(identity.get().assistantId());
    SecurityContextHolder.getContext().setAuthentication(authentication);

    chain.doFilter(request, response);
  }
}
