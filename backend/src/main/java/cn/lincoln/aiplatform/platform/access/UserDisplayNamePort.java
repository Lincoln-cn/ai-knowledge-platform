package cn.lincoln.aiplatform.platform.access;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 按 id <strong>批量</strong>读用户<strong>当前</strong>显示名的端口（{@code #198} 第五块的显示层补名）。
 *
 * <h3>★ 契约：这个方法<strong>不许抛异常</strong></h3>
 *
 * <p>调用方是"<strong>展示层补名字</strong>"：发布历史、审计行、任务指派这类只读列表，在人名那一格填上 {@code
 * iam_user.display_name}。名字是<strong>装饰性信息</strong> —— 一次用户表查询的抖动，不该把整页列表变成 500。
 * 所以本方法把"查不到"和"查询失败"<strong>归成同一种结果</strong>：
 *
 * <ul>
 *   <li>id 不存在、或用户已<strong>软删</strong>（{@code iam_user.deleted_at} 非空）⇒ 那个 id
 *       <strong>不在</strong>返回表里；
 *   <li>底层查询抛了任何 {@link RuntimeException} ⇒ 返回<strong>出事前已经取到的那些</strong>（可能为空表），并记一条 {@code
 *       warn}。<strong>不许</strong>向上抛。
 * </ul>
 *
 * <p>★ 调用方据此写代码：{@code map.get(id)} 得到 {@code null} 就是"现在解析不到"，界面上显示「（未知用户）」
 * 之类的占位，<strong>不要</strong>把 uuid 塞进人名那一格，也<strong>不要</strong>为它把整页请求改成失败。 反过来说，调用方<strong>
 * 不需要</strong>再包一层 {@code try/catch} —— 兜底只有一处，就是实现里那个 {@code catch}（否则"哪个 catch 在起作用"
 * 会变成没人说得清的事，破坏"这条兜底可被一次故意的破坏证伪"）。
 *
 * <h3>★ 为什么是"读时解析"，不是发布时存快照</h3>
 *
 * <p>人名会改、会软删：改名之后历史里应该显示新名，软删之后不该还显示一个已经不存在的人。所以调用方只存 id， 每次读的时候现查。这与审计表刻意相反 —— {@code
 * pf_audit_log.actor_name} 记的是<strong>当时</strong>那个人是谁， 审计要回答"那一刻发生了什么"，所以那边必须存快照。
 *
 * <h3>★ 形状：接口在 {@code platform}、实现在 {@code identity}</h3>
 *
 * <p>与本项目其余跨模块 SPI 同一条口径（{@code 08 §4.4.2}，参照 {@code platform.watermark.ConversationWatermarkPort}
 * 与 {@code conversation.infrastructure.ConversationWatermarkAdapter} 那一对）：所有模块都已依赖 {@code platform}
 * ⇒ 依赖矩阵零改动；而真正读 {@code iam_user} 的实现必须住在 {@code identity}（那张表只有它看得见， {@code ModuleBoundaryTest}
 * 会拦）。
 *
 * <p>★ 为什么<strong>批量</strong>：列表是整页读的，按行查就是 N+1 —— 一个应用上百版时，页面等的是上百次数据库往返。
 */
public interface UserDisplayNamePort {

  /**
   * 把一批用户 id 解析成<strong>当前</strong>显示名。
   *
   * <p>★ 不抛异常（见类注释）：查不到 / 查询失败都只返回"能给的那么多"。
   *
   * @param ids 用户 id；{@code null}、空集合、或全是 {@code null} ⇒ 返回空表（不查库）。重复 id 由实现去重
   * @return id → 显示名。★ <strong>表里没有的 id = 现在解析不到</strong>（不存在 / 已软删 / 这次查询失败），
   *     调用方按"未知"处理；返回值<strong>永不为 {@code null}</strong>
   */
  Map<UUID, String> displayNamesOf(Collection<UUID> ids);
}
