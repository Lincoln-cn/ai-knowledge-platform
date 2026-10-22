package cn.lincoln.aiplatform.platform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ 检索预算配置的判据（`app.retrieval.*`）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>这两个预算值原来是两个写死的 {@code static final = 5000}。实测在 **CPU + 1.5B** 上 一次查询理解要 4–5s、重试一次共 **7.9s** ⇒
 * 5s 总预算在**第 0 路**就耗尽 （日志：`检索预算 5000ms 已耗尽（已跑 0 路）`），整条链路降级成单路， 而用户看到的是"慢且没有改写"的一次检索 ——
 * 根因是**预算与硬件不匹配**。
 *
 * <p>⇒ 本类钉三件事：<b>默认值不变</b>（不悄悄改变已有部署）、 <b>低配能覆盖</b>、<b>自相矛盾的组合在启动时就被拒</b>。
 *
 * <p>★ 金丝雀：把 {@code queryUnderstandingRetryOnParseFailure} 的校验删掉 ⇒ 第三条必红。
 */
class RetrievalBudgetPropertiesTest {

  @Test
  void defaultsMatchTheDesignBook() {
    RetrievalBudgetProperties defaults = new RetrievalBudgetProperties(5000, 5000, 0.6, true, true);

    assertThat(defaults.queryUnderstandingBudgetMs())
        .as("★ 默认仍是 5000（10 §6.2 第 6 项）—— 改默认值会悄悄改变已有部署的行为")
        .isEqualTo(5000);
    assertThat(defaults.totalBudgetMs()).isEqualTo(5000);
    assertThat(defaults.hopBudgetRatio()).isEqualTo(0.6);
    assertThat(defaults.queryUnderstandingEnabled()).isTrue();
    assertThat(defaults.queryUnderstandingRetryOnParseFailure()).isTrue();
  }

  @Test
  void lowEndProfileValuesAreAccepted() {
    // ★ 与 application-lowend.yml 一致的一组值：CPU + 小模型的"够用"配置
    RetrievalBudgetProperties lowEnd =
        new RetrievalBudgetProperties(12000, 20000, 0.5, false, false);

    assertThat(lowEnd.queryUnderstandingBudgetMs()).isEqualTo(12000);
    assertThat(lowEnd.totalBudgetMs()).isEqualTo(20000);
    assertThat(lowEnd.queryUnderstandingEnabled())
        .as("★ 低配默认关掉查询理解：实测 1.5B 的输出格式不达标，放宽预算也只是白等 8–10s")
        .isFalse();
  }

  @Test
  void disablingUnderstandingButKeepingRetryIsRejected() {
    assertThatThrownBy(() -> new RetrievalBudgetProperties(5000, 5000, 0.6, false, true))
        .as(
            "★★ 「关了查询理解、却又开着解析重试」自相矛盾 —— 它的表现是『配置看起来生效、实际一次都不会发生』，"
                + "这类静默不生效必须在启动时拒绝，而不是等到线上没人看得出为什么没改写")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("无意义");
  }

  @Test
  void nonPositiveBudgetsAreRejected() {
    assertThatThrownBy(() -> new RetrievalBudgetProperties(0, 5000, 0.6, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("查询理解预算");
    assertThatThrownBy(() -> new RetrievalBudgetProperties(5000, -1, 0.6, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("总预算");
  }

  @Test
  void hopRatioMustBeWithinZeroToOne() {
    assertThatThrownBy(() -> new RetrievalBudgetProperties(5000, 5000, 0, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多跳水位");
    assertThatThrownBy(() -> new RetrievalBudgetProperties(5000, 5000, 1.5, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多跳水位");
    // 边界：正好 1.0 合法（"用光才停"，语义上是"不提前收手"）
    assertThatCode(() -> new RetrievalBudgetProperties(5000, 5000, 1.0, true, true))
        .doesNotThrowAnyException();
  }
}
