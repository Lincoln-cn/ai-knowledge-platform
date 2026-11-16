package cn.lincoln.aiplatform.platform.guard;

import java.util.UUID;

/**
 * 一条围栏规则（表 {@code pf_guard_rule}，迁移 {@code V102}）。
 *
 * @param id 主键
 * @param categoryId 所属分类
 * @param categoryCode 所属分类的机器码（随行带出：审计要记它，界面要显示它，避免前端再查一次分类表）
 * @param categoryName 所属分类的展示名
 * @param kind 匹配类型
 * @param content 原始写法（运营看到的那一行）
 * @param enabled 停用 = 不参与匹配
 * @param note 备注（为什么加这一条）
 */
public record GuardRule(
    UUID id,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    GuardRuleKind kind,
    String content,
    boolean enabled,
    String note) {}
