package cn.lincoln.aiplatform.platform.model;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;

/**
 * 出站闸门（{@code #72}）：追问「<strong>这次调用允许把多密的内容发到这个模型</strong>」。
 *
 * <h3>★ 为什么它必须与密级过滤分成两个东西</h3>
 *
 * <p>{@code 13 §三} 的 pre-filter 与 {@code ScopeFilter.canSeeClassification} 回答的是
 * 「<strong>哪些分段能进候选</strong>」（谁能读）；本端口回答的是
 * 「<strong>选中之后允许送到哪里</strong>」（能发到哪）。两者<strong>正交</strong> —— 一个高密级分段可以合法地被本地模型读，但绝不能被发去公网模型； ★
 * 只做前者等于把「出口」这一侧完全敞开，而它<strong>既不拦截、也不留痕、也不报错</strong> （本项目头号病 {@code silent-failure} 的形状）。
 *
 * <h3>★ 判据语义（调用方照抄即可，别自己发明）</h3>
 *
 * <ul>
 *   <li>返回非 {@code null}：把它当<strong>上限</strong>用 —— 分段密级 {@code within(上限)} 才允许进入本次上下文；
 *   <li>★ 返回 {@code null}：<strong>fail-closed</strong>（一个模型都认不出来时，不许当作"没有限制"）。 调用方必须把 {@code null}
 *       与"上限 = 最高档"区分开 —— 前者是取不到身份，后者是明确的放行。
 * </ul>
 *
 * <h3>★★ 当前实现边界（写在这里，防止被读成"数据边界已经管住了"）</h3>
 *
 * <p>本端口只覆盖<strong>模型侧</strong>那一层（按 {@code ai_model.supplier} 与平台配置定上限）。 {@code #72}
 * 原文要求的另两层<strong>尚未落地</strong>：
 *
 * <ol>
 *   <li><strong>模型级</strong>的 {@code allowed ceiling} 列（沿用 {@code
 *       kb_knowledge_base.max_classification} 的同形口径） —— 现在是<strong>平台级</strong>配置，所有 {@code
 *       EXTERNAL_API} 模型共用一个上限；
 *   <li><strong>出网侧</strong>白名单（域名/IP）。与 {@code 06 §3.0.2} 给工具调用设计的那条<strong>同一套机制</strong>， 不要两套。
 * </ol>
 *
 * <p>★ 也<strong>没有</strong>审计留痕（{@code #72} 判据 4：谁、哪个应用、最高密级、目标模型）—— 现在只有一条 WARN 日志。写清楚比假装做了强。
 */
public interface ModelEgressPort {

  /**
   * 该模型允许处理的内容密级上限。
   *
   * @param modelId 模型目录里的 id（{@code mc/ai_model} 同口径）
   * @return 上限；★ {@code null} = 取不到（<strong>fail-closed</strong>，调用方必须一条都不给）
   */
  ClassificationLevel ceilingOf(UUID modelId);
}
