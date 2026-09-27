package cn.lincoln.aiplatform.identity.domain;

/**
 * 用户状态。
 *
 * <p>★ 枚举名刻意用**中文字面量**，而不是 {@code ACTIVE/SUSPENDED} + 一个 Converter —— 因为契约里就是这三个中文值（{@code
 * frontend/src/api/identity.ts:24} 的 {@code status: '正常' | '停用' | '待审核'}）。用中文名配
 * {@code @Enumerated(STRING)}， 库、代码、契约三处是同一个字面量，中间少一层映射就少一处可能对不上的地方。
 *
 * <p>代价是要接受"枚举名不是英文"这件事 —— 在本项目的语境里（全中文契约）这笔账是划算的。
 */
public enum UserStatus {
  正常,
  停用,
  待审核
}
