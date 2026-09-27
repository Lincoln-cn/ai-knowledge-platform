package cn.lincoln.aiplatform.identity.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录失败处理配置（{@code app.security.login-failure.*}）。
 *
 * <p>★ 等保要求"对登录失败做出处理"，但具体阈值与时长各系统可以自定。 这里取 3 次 / 30 分钟（用户指定的口径）。两个值都可配，
 * 是因为不同环境的容错需求不同：内网演示环境可能想放宽，而生产要收紧。
 *
 * @param maxAttempts 连续失败多少次触发锁定
 * @param lockDuration 锁定持续多久
 */
@ConfigurationProperties(prefix = "app.security.login-failure")
public record LoginFailureProperties(int maxAttempts, Duration lockDuration) {}
