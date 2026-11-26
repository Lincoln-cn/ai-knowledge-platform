package cn.lincoln.aiplatform.platform.conversation;

import java.util.UUID;

/**
 * 跨模块端口：对话模块提供的「同一问题跑两版」调试能力。
 *
 * <p>★ 为什么这条缝开在 {@code platform}：{@code assistant} 的提示词评测（{@code ROADMAP §11.63}）要
 * 「用已保存的角色设定跑一遍、再用草稿跑一遍」，而这段能力住在 {@code conversation}（检索、模型调用、
 * 拒答判定都在那边）。{@code assistant} 的 {@code @ApplicationModule} 允许依赖里没有 {@code
 * conversation} —— {@code ModuleBoundaryTest} 会当场报红，所以按本项目惯例
 * 「SPI 放 {@code platform}、实现放提供方模块」抽这个端口。
 *
 * <p>★ {@code sourceRef} 与 {@code tokens} 刻意<strong>不搬</strong>：它们属于对话域，评测侧不消费。
 * 一个端口暴露得比调用方需要的多，下一个人就会以为"漏出来的都该用"；而少一个字段的代价只是
 * 将来真需要时再扩，多一个字段的代价是一条没人维护的形状。
 */
public interface DebugChatPort {

  /**
   * 对比调试：同一个问题跑两次 —— 一次用已保存的角色设定，一次用请求里带的那一版。
   *
   * @param appId 应用 id（看不见 ⇒ 404，与正式轮同一条口径）
   * @param question 调试问题（非空）
   * @param draftRolePrompt 表单里还没保存的角色设定（可为 null / 空 ⇒ 视为没改动）
   */
  DebugComparison debugCompare(UUID appId, String question, String draftRolePrompt);

  /**
   * 一份调试答案。
   *
   * @param elapsedMs 生成耗时（毫秒）
   */
  record DebugAnswer(String answer, long elapsedMs) {}

  /**
   * 对比结果。
   *
   * @param saved 用已保存配置跑出来的那一份（始终有值）
   * @param draft 用草稿跑出来的那一份；{@code draftRan = false} 时为 {@code null}
   * @param draftRan 是否真的跑了第二次。★ {@code false} 的原因是"草稿与已保存逐字相同"， 不是失败 ——
   *     评测侧要据此知道两栏是同一份，而不是"第二栏跑丢了"
   */
  record DebugComparison(DebugAnswer saved, DebugAnswer draft, boolean draftRan) {}
}
