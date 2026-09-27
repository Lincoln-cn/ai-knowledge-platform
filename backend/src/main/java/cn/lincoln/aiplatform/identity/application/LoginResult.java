package cn.lincoln.aiplatform.identity.application;

/**
 * 登录 / 刷新的返回（契约 {@code LoginResult}，{@code frontend/src/api/identity.ts:11-16}）。
 *
 * @param accessToken 访问令牌，放在 {@code Authorization: Bearer} 里
 * @param refreshToken 刷新令牌。★ 它只在这一次响应里以明文出现，服务端只存哈希
 * @param mustChangePassword 首次登录必须改密；前端据此强制跳改密页
 */
public record LoginResult(String accessToken, String refreshToken, boolean mustChangePassword) {}
