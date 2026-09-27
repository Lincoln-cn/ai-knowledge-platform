package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import java.util.List;
import java.util.UUID;

/**
 * 角色视图（契约 {@code Role}，{@code frontend/src/api/identity.ts:39-66}）。
 *
 * <p>★ {@code permissions} / {@code dataScope} / {@code maxClassification} 与 {@code labels}
 * 是<strong>两个正交维度</strong>（{@code 08 §4.4.1}）：前三者回答"能做什么"， 后三者回答"能看到哪些、多敏感的"。它们一起回给前端，但改的时候是两个端点
 * （{@code PUT /roles/:id/permissions} 与 {@code PUT /roles/:id/data-scope}）——
 * 因为"这个岗位能做什么"与"它能看谁的东西"是两个不同的人在不同场合决定的事。
 *
 * @param memberCount 该角色下的用户数。契约里有这一列，且它决定"能不能删这个角色"
 */
public record RoleView(
    UUID id,
    String name,
    String code,
    long memberCount,
    List<String> permissions,
    DataScope dataScope,
    String dataScopeRule,
    ClassificationLevel maxClassification,
    List<String> labels) {}
