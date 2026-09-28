package cn.lincoln.aiplatform.knowledge.application;

/**
 * 分类清单项（契约 {@code KnowledgeCategory}）。
 *
 * <p>★ {@code id} 与 {@code name} 当前<strong>同值</strong>：分类在创建请求里是显示名（{@code category: string}），
 * 还没有独立的分类字典表（FR-03-03 后补树形字典时再拆）。 契约要两个字段就都给，前端不必为"有没有 id"写分支。
 *
 * @param baseCount 该分类下<strong>当前用户可见</strong>的库数（范围谓词与列表同口径，不泄露他人私有库的存在）
 */
public record KnowledgeCategoryView(String id, String name, long baseCount) {}
