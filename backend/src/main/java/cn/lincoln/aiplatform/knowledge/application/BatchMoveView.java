package cn.lincoln.aiplatform.knowledge.application;

/**
 * 批量迁移分段的回执（契约 {@code { moved: number }}）。
 *
 * <p>★ 只有 {@code moved} 一个数：迁移是<strong>全成或全不成</strong>的动作（选了 3 段、目标是同一篇文档、
 * 目标是不可见的……都是整批拒绝），不存在"部分成功"。所以不需要 {@code failed} 那一半 —— 硬加一个永远是空数组的字段，只会让调用方以为它会填。
 *
 * @param moved 实际搬移的段数
 */
public record BatchMoveView(int moved) {}
