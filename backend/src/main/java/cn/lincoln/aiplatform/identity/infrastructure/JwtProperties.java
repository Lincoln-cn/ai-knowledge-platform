package cn.lincoln.aiplatform.identity.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 与令牌的配置（{@code application.yml} 的 {@code app.security.jwt.*}）。
 *
 * <p>★ 用自定义前缀 {@code app.security.jwt} 而不是 {@code spring.security.*}： 后者是 Boot/Security
 * 自己的命名空间，往里塞自定义键会在某次升级后撞名。
 *
 * @param issuer 签发者标识。★ 它会进令牌的 {@code iss} 声明，验签时一并核对 —— 将来若引入网关或第二个服务，这一项能让"别处签的令牌"被明确拒绝。
 * @param accessTtl 访问令牌有效期。契约侧是 30 分钟（{@code 08 §4.0} 第 2 行）。 它同时决定"权限点改动多久生效"—— 权限点在令牌里（见 {@link
 *     JwtTokenService}）。
 * @param refreshTtl 刷新令牌有效期（7 天）。
 * @param secret **Base64 编码**的 HS256 密钥（至少 32 字节）。 ⚠️ {@code application.yml}
 *     里那个是**本地开发默认值**，任何非本地环境都必须覆盖。
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    String issuer, Duration accessTtl, Duration refreshTtl, String secret) {}
