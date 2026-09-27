package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.UserStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * 用户视图（契约 {@code User}，{@code frontend/src/api/identity.ts:18-26}）。
 *
 * <p>★ 它<strong>不含</strong> {@code passwordHash} —— 契约里没有这个字段， 而"视图对象带着密码哈希"是那种"只要有人顺手 {@code
 * return user} 就泄露了"的形状。 这里显式列出每个字段，等于把"哪些能出网"变成一份可阅读的清单。
 *
 * @param roleName 角色的**显示名**（不是 code）。契约里前端直接拿它渲染表格列， 所以后端要 join 出来 —— 让前端再查一次角色表会让列表页产生 N+1 次请求
 * @param lastLoginAt ISO-8601。⚠️ 前端 mock 用的是 {@code '2026-02-13 08:30'} 这种 空格分隔的本地时间格式，两者形状不同（见
 *     CHANGELOG 0.1.2 的待确认项）
 * @param initialPassword 初始口令。★ **仅** {@code POST /users} 且调用方未传 password 时才有值，
 *     其余路径（列表/详情/更新、以及调用方自己传了口令的创建）一律 null。 只在非 null 时序列化该字段（{@code NON_NULL} 标在组件上而不是整个 record 上
 *     —— {@code lastLoginAt} 为 null 时契约要求字段在，不能一起省掉）。 一次性的秘密只该出现在一次响应里
 */
public record UserView(
    UUID id,
    String username,
    String displayName,
    UUID roleId,
    String roleName,
    UserStatus status,
    Instant lastLoginAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) String initialPassword) {}
