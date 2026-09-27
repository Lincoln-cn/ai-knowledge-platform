package cn.lincoln.aiplatform.identity.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 验证码配置（{@code app.security.captcha.*}）。
 *
 * @param enabled 是否强制校验验证码。★ 等保要求开启，所以默认 {@code true}； 留这个开关是为了本地压测/自动化测试能关掉 —— 而不是为了生产能关。
 * @param ttl 验证码有效期。★ 短一点更好：它只需要"够人输入一次"， 而每多一分钟就多一分钟被暴力试的窗口。
 */
@ConfigurationProperties(prefix = "app.security.captcha")
public record CaptchaProperties(boolean enabled, Duration ttl) {}
