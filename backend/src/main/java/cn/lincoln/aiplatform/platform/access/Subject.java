package cn.lincoln.aiplatform.platform.access;

/**
 * 一个共享主体。
 *
 * <p>★ 判定里必须按 {@code kind} + {@code id} <strong>一起</strong>比对 —— 部门「3」不是角色「3」（{@code 07 §8.2}）。只比
 * id 的写法在"部门与角色恰好同号"时 会静默放行一个不该看见资源的人，而且这种撞号很容易发生（都是自增/UUID 前缀截断）。
 */
public record Subject(SubjectKind kind, String id) {}
