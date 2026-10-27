package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.platform.security.ApiKeyAuthenticator;
import cn.lincoln.aiplatform.platform.security.PublicRateLimitFilter;
import cn.lincoln.aiplatform.platform.security.PublicRateLimitProperties;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
  LoginFailureProperties.class,
  PublicRateLimitProperties.class
})
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiFilterChain(
      HttpSecurity http,
      JwtAuthenticationConverter authorityConverter,
      RestAuthenticationEntryPoint entryPoint,
      RestAccessDeniedHandler deniedHandler,
      ApiKeyAuthenticationFilter apiKeyFilter,
      PublicRateLimitFilter publicRateLimitFilter)
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
        // ★ 公开面限流（#214）：排在认证之前 —— 它判的是"这个来源/这条链接还允许提问吗"，
        //   与"你是谁"无关；放后面会让每个被限流的请求先白跑一次验签。
        //   ★ 它只认 /api/v1/public/** 与带 X-Access-Credential 的 POST /conversations/chat
        //   （见 PublicRateLimitFilter 的类注释：登录面不该按 IP 掐）。
        .addFilterBefore(publicRateLimitFilter, BearerTokenAuthenticationFilter.class)
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
   * 公开面（免登录）限流器（{@code #214}）。
   *
   * <p>★ 它由 Security 链持有（{@code apiFilterChain} 里的 {@code addFilterBefore(…,
   * BearerTokenAuthenticationFilter.class)}）。 ★★ <strong>但只写那一行是不够的</strong> —— 还必须把它从 Boot
   * 的容器级自动注册里摘掉，见 {@link #publicRateLimitFilterRegistration}。
   */
  @Bean
  PublicRateLimitFilter publicRateLimitFilter(PublicRateLimitProperties properties) {
    return new PublicRateLimitFilter(properties);
  }

  /**
   * ★★ 关掉 {@link PublicRateLimitFilter} 的<strong>容器级自动注册</strong>，只留 Security 链里那一条。
   *
   * <h3>为什么需要这一条 —— 这是实测出来的，不是推理出来的</h3>
   *
   * <p>Spring Boot 会把容器里<strong>每一个 {@code Filter} 类型的 bean</strong> 自动注册到 servlet 链上， 与它是不是
   * {@code @Component} <strong>无关</strong>。所以"声明成 bean 就不会双重注册"这个说法是错的。
   *
   * <p>● <strong>它是怎么被发现的</strong>：{@code PublicRateLimitHttpTest} 的反证实验 —— 把 {@code
   * apiFilterChain} 里的 {@code addFilterBefore(publicRateLimitFilter, …)} 整行删掉再跑， 预期是"不再
   * 429"，实测<strong>仍然 3/3 绿</strong>。说明真正在干活的是容器自动注册的那一条， 代码里写的位置<strong>不决定</strong>它实际跑在哪。
   *
   * <p>● <strong>为什么没表现为"每条请求算两次"</strong>：两个实例都是 {@code OncePerRequestFilter}， 它用请求属性做同请求去重 ⇒
   * 第二个实例直接放行。计数不翻倍，但"我把它放在认证之前"这句话是假的 —— 实际位置取决于容器注册的 order。
   *
   * <p>● <strong>处置</strong> = 只让一个位置生效：摘掉自动注册、保留 Security 链里那一条。 这样"位置"既可读又可测（反证实验会真的变红）。
   *
   * <p>⚠️ 同一个形状在 {@code ApiKeyAuthenticationFilter} 上<strong>也存在</strong>（它同样是被容器自动注册的 {@code
   * OncePerRequestFilter}）—— 那是既有欠账，已登记 {@code #218}；本类只修自己这一处， 不顺手改动鉴权链。
   */
  @Bean
  FilterRegistrationBean<PublicRateLimitFilter> publicRateLimitFilterRegistration(
      PublicRateLimitFilter filter) {
    FilterRegistrationBean<PublicRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
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
