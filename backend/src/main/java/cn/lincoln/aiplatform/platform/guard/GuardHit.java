package cn.lincoln.aiplatform.platform.guard;

import java.util.UUID;

/**
 * 一次匹配命中的结果（{@code ROADMAP §11.53.4}）。
 *
 * <p>★ <strong>不装原文</strong>：审计要的不是"用户说了什么"，而是"哪条规则、什么类型、哪个分类"。 存原文有两个后果 —— 日志变成一份会话副本（留存与隐私问题）， 以及
 * {@link #contentHashPrefix} 想避免的那件事：留痕本身成了"哪些词会被拦"的清单。
 *
 * @param ruleId 命中的规则
 * @param categoryId 所属分类
 * @param categoryCode 分类机器码（审计里用它，不用展示名 —— 展示名会改）
 * @param categoryName 分类展示名
 * @param kind 匹配类型
 * @param contentHashPrefix 归一化内容的 SHA-256 前 8 位（排障时用它与规则表对号，但不暴露词面）
 * @param categoryRefusalText 该分类的专属话术（可空；只在开关打开时被采用）
 */
public record GuardHit(
    UUID ruleId,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    GuardRuleKind kind,
    String contentHashPrefix,
    String categoryRefusalText) {}
