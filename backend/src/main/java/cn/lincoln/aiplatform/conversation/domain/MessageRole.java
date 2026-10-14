package cn.lincoln.aiplatform.conversation.domain;

/**
 * 消息角色（契约 {@code frontend/src/api/conversations.ts} 的 {@code ChatRole}）。
 *
 * <p>两个取值逐字对齐契约：{@code USER} / {@code ASSISTANT}（{@code 02 §2.6} 的 {@code MessageRole} 同名同域）。
 *
 * <h3>★ 为什么 role 不是一个"展示用"的字段</h3>
 *
 * <p>它同时决定三件事，所以取值写错的症状散在很多地方：
 *
 * <ol>
 *   <li><strong>谁能进入状态机</strong>：只有 {@link #ASSISTANT} 有"生成中"这个阶段。 {@code 02 §2.6} 的 MSG-I3
 *       原话是"用户消息的状态只能是 {@code DONE}" —— 一条"生成中"的用户消息
 *       在界面上会<strong>永远转圈</strong>（没有任何生成任务会去收尾它），而它不会报错。 这一条落在库层的 {@code
 *       chk_msg_user_is_generated} 与领域层的工厂方法上，两边都要有。
 *   <li><strong>引用挂在哪一边</strong>：MSG-I4 —— 引用只存在于助手消息上。
 *   <li><strong>界面的左右两侧</strong>：role 错了会让一条回答渲染成用户气泡（看着像"我居然问了这句"）。
 * </ol>
 */
public enum MessageRole {

  /** 用户提问。 */
  USER,

  /** 模型回答（唯一参与"生成"这一过程的角色）。 */
  ASSISTANT
}
