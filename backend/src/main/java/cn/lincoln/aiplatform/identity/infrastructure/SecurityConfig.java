package cn.lincoln.aiplatform.identity.infrastructure;

import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
      RestAccessDeniedHandler deniedHandler)
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
                    // 免登录问答面（M4 才有实体，先留面）
                    .requestMatchers("/api/v1/public/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
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
