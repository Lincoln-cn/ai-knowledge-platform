package cn.lincoln.aiplatform.platform.security;

import java.util.Optional;

/**
 * 把 {@code X-API-Key} 的明文解析成 {@link ApiKeyIdentity}（{@code #195} 使用侧）。
 *
 * <p>★ 契约边界：实现负责"查表 + 只认启用中的 Key + 回写 {@code last_used_at}"，<strong>不</strong>负责构造 Spring Security
 * 的 认证对象——那是过滤器的事。这样 assistant 模块不依赖 Security 的类型，identity 模块也不依赖 assistant 的仓储。
 *
 * <p>★ 失败一律返回 {@link Optional#empty()}：调用方只区分"认证成功"与"没通过"， 不区分"不存在"与"已停用"——把差别暴露给未认证的调用者只会帮人枚举 Key。
 */
public interface ApiKeyAuthenticator {

  Optional<ApiKeyIdentity> authenticate(String rawKey);
}
