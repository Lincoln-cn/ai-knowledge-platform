package cn.lincoln.aiplatform.platform.guard;

import java.time.Instant;

/**
 * 安全围栏的运行开关（表 {@code pf_guard_settings}，单行 · 迁移 {@code V102}）。
 *
 * <h3>★ 它管"要不要拦"，词表管"拦什么"</h3>
 *
 * <p>两者必须分开：<strong>关掉整道围栏</strong>是一个可审计的显式动作（{@link #inputEnabled}），而"某条词不再拦"
 * 是内容维护。把两者混成一张表会让"我今天临时关掉围栏"变成"删掉一批词再改回来"—— 后者没人能证明改回来时一条不少。
 *
 * @param inputEnabled 输入侧总开关。关掉 = 整个围栏不参与匹配（部署方可能要求"先只观察"）
 * @param defaultRefusalText 统一拒答话术。★ 默认值与 {@code SafeGuardChatAdvisor.REFUSAL} **一字不差**：两边不一致的症状是
 *     "界面上看到的和用户收到的不是同一句"
 * @param advisorsEnabled advisor 链总开关。与 {@code app.chat.advisors.enabled} 对齐 ——
 *     关着上线等于安全闸门不生效（{@code 12 §12}）
 * @param categoryRefusalEnabled 按分类给不同话术。★ 默认 {@code false}：开启后攻击者可以用"回的是哪句话" 反推自己撞到了哪一类
 * @param updatedAt 上次修改时刻（服务端写；从未改过则为 {@code null}）
 */
public record GuardSettings(
    boolean inputEnabled,
    String defaultRefusalText,
    boolean advisorsEnabled,
    boolean categoryRefusalEnabled,
    Instant updatedAt) {

  /** 拒答话术的长度上限（与迁移里的 {@code varchar(200)} 同源，应用层先挡一次）。 */
  public static final int MAX_REFUSAL_LENGTH = 200;

  /**
   * 校验。与 {@code RetrievalEngineSettings#checked()} 同一个手法：不在 record 的 compact 构造器里做，
   * 因为那样"读库读到一个越界值"会直接抛在读路径上。
   *
   * @return 校验通过的值
   * @throws IllegalArgumentException 话术为空或超长
   */
  public GuardSettings checked() {
    if (defaultRefusalText == null || defaultRefusalText.isBlank()) {
      throw new IllegalArgumentException("拒答话术不能为空 —— 拦下来之后总得回一句话");
    }
    if (defaultRefusalText.length() > MAX_REFUSAL_LENGTH) {
      throw new IllegalArgumentException(
          "拒答话术最多 " + MAX_REFUSAL_LENGTH + " 个字，现在写的是 " + defaultRefusalText.length());
    }
    return this;
  }
}
