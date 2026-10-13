package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;

/**
 * 一条公开令牌解析出来的东西（已通过状态检查，<strong>未</strong>通过权限检查）。
 *
 * <p>★ 它<strong>已经被冻结</strong>：这里只有值，没有指向实体的引用 —— 缓存的就是这份 record， 所以"缓存里的东西被谁改了一下"这类问题不存在。★
 * 也<strong>不含</strong>"这次访问看得见什么"： 内容每次都必须重算（{@code 13 §5.2} 第 ② 级），缓存它正是那条判据禁止的写法。
 *
 * @param credentialId 凭证 id ★ 审计要用它（{@code 13 §十二} 第 4 行：免登录面没有 {@code actor_id}， 不记 {@code
 *     credential_id} 这条记录说不清"是谁进来的"）
 * @param assistantId 这条凭证指向的应用。★ 归属与可见性判定走它 —— 凭证自己不带 owner/visibility
 * @param subjectId ★ 访客行使的<strong>数据范围</strong>属于谁 —— 当前取"应用归属人" （"这条链接行使的是归属人的范围"）。★ 另一种口径见 {@code
 *     PublicTokenResolver.PublicAccess} 的 javadoc，它是本轮明确记下的一处待裁决
 * @param frozenClearance ★★ <strong>创建时冻结</strong>的密级上限 = 创建者当时的 clearance （{@code 13 §5.2} 第 ①
 *     级）。访客每次访问都用它重新过滤（第 ② 级）
 */
public record ResolvedCredential(
    UUID credentialId, UUID assistantId, UUID subjectId, ClassificationLevel frozenClearance) {}
