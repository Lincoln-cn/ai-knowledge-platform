package cn.lincoln.aiplatform.platform.guard;

import java.util.UUID;

/**
 * 围栏分类（表 {@code pf_guard_category}，迁移 {@code V102}）。
 *
 * @param id 主键
 * @param code 机器码（{@code POLITICS} / {@code PORN} …）。★ 它进审计，所以**不许改** —— 改了历史留痕里的分类就对不上显示名
 * @param name 展示名（运营可改）
 * @param sortOrder 排序
 * @param enabled 停用 = 该分类下的规则全部不参与匹配，但规则行还在（可恢复）
 * @param builtin 内置分类不许删（暂停用与改名）
 */
public record GuardCategory(
    UUID id, String code, String name, int sortOrder, boolean enabled, boolean builtin) {}
