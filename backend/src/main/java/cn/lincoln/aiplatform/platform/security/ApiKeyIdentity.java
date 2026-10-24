package cn.lincoln.aiplatform.platform.security;

import java.util.UUID;

/**
 * 一把应用 API Key 解析出来的身份（{@code #195} 的「可用鉴权链」）。
 *
 * <p>★ 为什么是<strong>两个</strong> id，而不是直接把调用者当成 owner：
 *
 * <ul>
 *   <li>{@code ownerId} 决定"以谁的名义执行"——数据范围、可见性判定都要用它；Key 是所有者签发的，行为后果归属所有者。
 *   <li>{@code assistantId} 决定"这把 Key 只能代表哪个应用"——调用问答时必须带同一个 appId，否则就是"用 A 应用的 Key 问 B 应用"，那等于把
 *       Key 的权限放大成所有者的全部应用。
 * </ul>
 *
 * <p>★ 它放在 {@code platform} 而不是 assistant 模块：身份过滤器（identity）要读它，assistant 模块要产出它；两边都不该互相依赖。
 */
public record ApiKeyIdentity(UUID assistantId, UUID ownerId) {}
