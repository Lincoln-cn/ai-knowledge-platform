package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.platform.security.ApiKeyAuthenticator;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全责任链（{@code 08 §4.2}）—— ★ 全部走 Spring Security 官方能力，不自研 filter。
 *
 * <p>第 1 周这里只有"链起来了但还没有认证方式"；本周补上三件事： JWT 资源服务器（验签交给官方）、方法级授权（{@code @PreAuthorize}）、 401/403
 * 的统一出口（官方扩展点）。
 *
 * <p>★ <strong>JWT 用 HS256（对称密钥）</strong>，不是 RSA。理由：签发与验签都在同一个进程里，
 * 不存在"把公钥分发给别方"的需求；对称密钥少一个密钥对的管理面。真需要外部验签时 （例如引入独立网关），换成 RSA 是改这几个 bean 的事，业务代码不受影响。
 *
 * <p>★ 第 3 周起本类还要长两样东西（此处不预埋，避免"看起来已经做了"）： ① 数据范围判定接入 {@code AuthorizationManager}（{@code 08
 * §4.3.3}）； ② 公开令牌的 {@code OncePerRequestFilter}（{@code 08 §4.2.3} 第 2 项，M4 才需要）。
 */
@Configuration
@EnableWebSecurity
// ★ Security 7 里 @PreAuthorize 默认仍启用（09 §4.4）；显式声明是为了让"这个应用有方法级授权"
//   在代码里可见 —— 不然新人只能靠翻文档才知道
@EnableMethodSecurity
@EnableConfigurationProperties({
  JwtProperties.class,
  CaptchaProperties.class,
  LoginFailureProperties.class
})
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiFilterChain(
      HttpSecurity http,
      JwtAuthenticationConverter authorityConverter,
      RestAuthenticationEntryPoint entryPoint,
      RestAccessDeniedHandler deniedHandler,
      ApiKeyAuthenticationFilter apiKeyFilter)
      throws Exception {
    http
        // 令牌式无状态 API：CSRF 不适用（官方做法）
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // 健康探针
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // ★ 登录、刷新、验证码必须免认证。刷新免认证是因为"令牌过期了才能刷新、
                    //   可刷新又要求令牌有效"本身就是循环的；验证码则是给"还没登录的人"用的
                    .requestMatchers(
                        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/captcha")
                    .permitAll()
                    // 免登录问答面（公开上下文端点：GET /api/v1/public/{token}）
                    .requestMatchers("/api/v1/public/**")
                    .permitAll()
                    // ★★ 免登录问答**流**（第 17 周 · 0.4.5）—— 放行的是**一条方法 + 一条路径**。
                    //
                    //   为什么它能匿名：前端 lib/sse.ts 的免登录形状打的就是**这个** URL，
                    //   只是把 Authorization 换成 X-Access-Credential（契约 stream-types.ts 的
                    //   `credentialToken`）⇒ 访客没有 JWT，不给这条放行的话公开页永远 401。
                    //
                    //   ★ 边界（三条，都不许放宽）：
                    //     ① 只放行 POST + 这一条路径，**不是**整个 /api/v1/conversations/**；
                    //     ② 放行的是**认证**，授权仍在方法级：ChatController.chat 的
                    //        `@PreAuthorize("hasAuthority('conversation:view') or #credentialToken
                    // != null")`
                    //        —— 两个条件缺一不可，少写一个就是"这条端点对匿名全开"；
                    //     ③ 匿名**不**意味着范围更宽：公开面由 PublicChatService 走
                    //        AccessScopePort#asSubject(归属人, 冻结密级)，且带登录身份时再收窄。
                    .requestMatchers(HttpMethod.POST, "/api/v1/conversations/chat")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // ★ X-API-Key（#195 使用侧）：排在 Bearer 之前 —— 两者同时出现时本过滤器让路，登录身份优先。
        .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
        // ★ JWT 的验签与解析交给官方（08 §4.2.2：不手写 JWT 解析 Filter）
        .oauth2ResourceServer(
            o ->
                o.jwt(jwt -> jwt.jwtAuthenticationConverter(authorityConverter))
                    .authenticationEntryPoint(entryPoint))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));

    return http.build();
  }

  /**
   * 把令牌里的 {@code authorities} 声明翻成 {@code GrantedAuthority}。
   *
   * <p>★ 少了这个 bean，{@code @PreAuthorize("hasAuthority('knowledge:create')")} 会
   * <strong>永远拒绝</strong>（而不是报错）—— 因为默认转换器只会给一个 {@code SCOPE_*} 前缀的权限。
   * 这种"配错就变成全拒"还算好；反过来配成"全放"才是灾难，所以它是金丝雀要盯的地方之一。
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          List<String> authorities = jwt.getClaimAsStringList("authorities");
          if (authorities == null) {
            return List.of();
          }
          return authorities.stream()
              .map(SimpleGrantedAuthority::new)
              .map(GrantedAuthority.class::cast)
              .toList();
        });
    return converter;
  }

  /** ★ 密码编码用官方实现，不手写哈希与加盐（{@code 08 §4.2.2}）。 */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * {@code X-API-Key} 的认证过滤器。
   *
   * <p>★ 在这里声明成 bean 而不是给过滤器类加 {@code @Component}：Spring Boot 会把容器里的每个 {@code Filter} bean 自动注册到
   * servlet 链上，那会让它在 Security 链之外**再跑一次**。这里只让 Security 链持有它。
   */
  @Bean
  ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
      ApiKeyAuthenticator authenticator,
      UserRepository users,
      RoleRepository roles,
      RolePermissionRepository rolePermissions,
      JwtTokenService tokens,
      JwtDecoder decoder,
      JwtAuthenticationConverter converter,
      RestAuthenticationEntryPoint entryPoint) {
    return new ApiKeyAuthenticationFilter(
        authenticator, users, roles, rolePermissions, tokens, decoder, converter, entryPoint);
  }

  @Bean
  SecretKey jwtSecretKey(JwtProperties properties) {
    return new SecretKeySpec(Base64.getDecoder().decode(properties.secret()), "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return NimbusJwtEncoder.withSecretKey(jwtSecretKey).build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
    return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
