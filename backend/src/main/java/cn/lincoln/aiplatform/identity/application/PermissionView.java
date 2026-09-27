package cn.lincoln.aiplatform.identity.application;

/**
 * 权限点视图（契约 {@code PermissionItem}，{@code frontend/src/api/identity.ts:54-60}）。
 *
 * <p>它让「角色管理」页不必硬编码任何权限点名称 —— 界面按 {@code module} 分组、 显示 {@code label}，而"一共有哪些权限点"由后端说了算。
 * 新增一个权限点时前端无需改代码（{@code Permissions} 的类注释里写了这条）。
 */
public record PermissionView(String code, String label, String module) {}
