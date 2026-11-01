package cn.lincoln.aiplatform.platform.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条审计记录（读侧的形状，`#258`）。
 *
 * <p>★ 与写侧的 {@link AuditLogPort#record} 刻意不对称：写侧收的是"要记什么"（动作、资源、明细）， 读侧还回来的却是"当时记下了什么" —— 多出来的
 * {@code actorName}、{@code result}、{@code at} 都是**写那一刻的快照**，
 * 不是现在再去查别人的当前值。这两个方向混成一个模型，才会写出现在这个形状：读出来的名字 按现在的库重新解析了一遍，于是"当时的操作人"变成了"这个人现在的名字"（甚至查不到）。
 *
 * @param at 记录时间（UTC 存、按客户端时区展示）
 * @param actorId 可空：系统自动动作没有"谁"
 * @param actorName ★ 快照，不是外键
 * @param action 动作编码（{@link AuditAction#code()}）
 * @param resourceType 资源类别（DOCUMENT / ROLE / ASSISTANT …）
 * @param resourceId 资源标识
 * @param classificationLevel 记录时的密级（今天恒为 null，见 `13 §7.3`）
 * @param clearanceAtAction 记录时的 clearance（今天恒为 null，见 `13 §7.3`）
 * @param detail 明细的 JSON 原文（读侧不在服务端解析结构 —— 键名随动作而变，见 `13 §7.3` 第 4 条）
 * @param result 结果（今天恒为 SUCCESS：失败会整体回滚）
 */
public record AuditRecordView(
    UUID id,
    Instant at,
    UUID actorId,
    String actorName,
    String action,
    String resourceType,
    String resourceId,
    String classificationLevel,
    String clearanceAtAction,
    String detail,
    String result) {}
