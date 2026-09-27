package cn.lincoln.aiplatform.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全责任链（{@code 08 §4.2}）—— ★ 用 Spring Security 官方过滤链，不自研 filter。
 *
 * <p>为什么这一段由 {@code identity} 装配：链上的放行规则、令牌解析、权限判定都是身份域的事 （{@code 08 §4.0} 第 2~4 行的"实现在 identity"）。
 *
 * <p>★ <strong>第 1 周（0.0.1）的状态是"链起来了，但还没有认证方式"</strong>： 除下面放行的三个面之外，一切请求都会被拒。这是刻意的 —— 第 2
 * 周（0.1.0）接上 {@code oauth2ResourceServer().jwt()} 与双令牌后，受保护面才有真正的准入判断。 现在就把链摆好，是为了让"哪些路径免登录"这件事从第 1
 * 周起就只有一个地方可改。
 *
 * <p>⚠️ 第 2 周必须补的两件事（此处不预埋代码，避免"看起来已经做了"）： ① {@code .oauth2ResourceServer(o -> o.jwt(...))} +
 * {@code JwtDecoder}（Security 7 的 API 形态见 {@code 09 §4.4}）； ② {@code @EnableMethodSecurity} +
 * {@code @PreAuthorize} 的权限点校验，以及它的金丝雀 （去掉某个 {@code @PreAuthorize} 必须让构建变红，{@code ROADMAP §4.7.1}）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    http
        // 令牌式无状态 API：CSRF 不适用（官方做法）
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // 健康探针：第 1 周的"能起来"判据要 curl 它，必须免认证
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // 认证入口（第 2 周实装）
                    .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh")
                    .permitAll()
                    // 免登录问答面：凭证由专用 filter 建立上下文（08 §4.2.1）
                    .requestMatchers("/api/v1/public/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated());

    return http.build();
  }
}
