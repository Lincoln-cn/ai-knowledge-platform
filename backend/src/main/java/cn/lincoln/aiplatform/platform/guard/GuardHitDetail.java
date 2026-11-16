package cn.lincoln.aiplatform.platform.guard;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条拦截图：管理员在「最近 N 天拦截」表里看到的一行。
 *
 * <h3>★ 为什么给明细，而不只给"按分类计数"</h3>
 *
 * <p>只给次数时，管理员拿到的是一句"色情 2 次" —— 他马上会问的是<b>谁、什么时候、问的哪句</b>
 * （这也是用户原话："你不是这个列表里可以看到谁什么时候问了什么吗"）。次数不能回答其中任何一个， 而回答它们的材料在拦截那一刻就已经在手上，只是当时只往下记了分类。
 *
 * <h3>★★ 原话仍然不进审计，进的是会话 id</h3>
 *
 * <p>{@code conversation} 让管理员一路点回那条会话看原话；而原话本身<b>只存在消息表一份</b> ——
 * 它受会话的访问控制与留存期管辖。把原话复制进审计等于多一份删不掉的副本， 而审计是长期留存的（{@code 13 §七}）。同一课：审计记的是"发生了什么事"，不是"内容是什么"。
 *
 * @param at 什么时候
 * @param categoryCode 哪一类（{@code POLITICS} / {@code PORN} / …）
 * @param kind 关键词还是敏感问题
 * @param ruleId 命中的那条规则（管理员据此知道"我写的哪条在拦"）
 * @param contentHashPrefix 归一化内容的哈希前 8 位（跨环境核对用；不还原成词）
 * @param actorId 谁问的；{@code null} = 匿名访客或拿不到身份
 * @param actorName 写入时刻的名字快照；{@code null} = 同上
 * @param conversationId 哪个会话；{@code null} = 这一轮还没有会话
 */
public record GuardHitDetail(
    Instant at,
    String categoryCode,
    String kind,
    String ruleId,
    String contentHashPrefix,
    UUID actorId,
    String actorName,
    String conversationId) {}
