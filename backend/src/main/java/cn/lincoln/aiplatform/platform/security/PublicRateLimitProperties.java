package cn.lincoln.aiplatform.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 公开面（免登录）限流口径（{@code app.security.public-rate-limit.*}，{@code #214}）。
 *
 * <h3>★★ 为什么需要它（这一格缺的不是读数，是实现）</h3>
 *
 * <p>A8「免登录安全」在 {@code ROADMAP §2.2} 里是一条验收判据，而 2026-10-27 的实测是： 全仓 grep {@code
 * rate.?limit|bucket4j|throttl|限流} <strong>0 命中</strong> —— 也就是说 {@code GET
 * /api/v1/public/{token}}、{@code GET /api/v1/public/{token}/messages}、 {@code POST
 * /api/v1/public/{token}/messages/{id}/stop} 与带 {@code X-Access-Credential} 的 {@code POST
 * /api/v1/conversations/chat} <strong>可以被同一个来源无限刷</strong>。
 * 这几条端点<strong>不需要登录</strong>，而且每条提问都会真的调一次上游模型 —— 所以它既是可用性问题（配额被一个访客吃光），也是成本问题。
 *
 * <h3>三个默认值的理由</h3>
 *
 * <ul>
 *   <li>{@code enabled} 默认 <strong>true</strong>：安全开关的默认值必须是"开着"， 否则任何一个漏配的环境都会静默地退回无限流；
 *   <li>{@code capacity = 60} / {@code window-seconds = 60}：一个访客正常读一条公开链接 （解析 → 读历史 → 问几轮 →
 *       偶尔停止）一分钟内到不了 60 次； 而 60 次/分钟已经足以让上游成本可控。★ 具体数字不是判据，可核对的是 "存在一个有上限的口径，且它可配"（真栈读数见 {@code
 *       PublicRateLimitHttpTest}）；
 *   <li>窗口用<strong>固定窗口</strong>而不是令牌桶：固定窗口只需要一个整数，
 *       <strong>没有后台线程、没有过期调度</strong>，出问题的形态也好解释（跨窗口边界最多放过 2 倍）。
 *       令牌桶更平滑，但要引入"填充速率"这个新的可配置项，且本项目现在没有第二个消费方。
 * </ul>
 *
 * <p>★ <strong>它<em>不</em>解决什么</strong>（写清，免得被读成"限流做完了"）：它是 <strong>单进程内存</strong>口径 ——
 * 多副本部署时每个实例各限各的；也没有"按用户/IP 分别配额"、 没有持久化、没有指标。这些属 {@code 0.5.x} 之后（{@code #214} 的评论里记了取舍）。
 *
 * @param enabled 总开关（关掉 = 完全不计数，用于"先让闸门跑通"的极端场景）
 * @param capacity 一个窗口内允许的请求数上限（对同一个键）
 * @param windowSeconds 窗口长度（秒）
 */
@ConfigurationProperties(prefix = "app.security.public-rate-limit")
public record PublicRateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("60") int capacity,
    @DefaultValue("60") int windowSeconds) {}
