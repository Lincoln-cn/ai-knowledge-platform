package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 胜率算法（ROADMAP §11.63.5 第 2 条）。
 *
 * <p>★ 这三条断言钉的是"读数会不会骗人"，不是"算式对不对"： 没评过必须给 {@code null}（不是 0）、差不多要算半分、分母只数已评的题。
 */
@DisplayName("提示词评测的胜率")
class PromptEvalServiceTest {

  @Test
  @DisplayName("一题都没评时给 null，不给 0 —— 0 会被读成「改坏了」")
  void noRatingsYetIsNullNotZero() {
    assertThat(PromptEvalService.winRate(0, 0, 0)).isNull();
  }

  @Test
  @DisplayName("全评「更好」是 1.0")
  void allBetterIsOne() {
    assertThat(PromptEvalService.winRate(5, 0, 0)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("「差不多」算半分：3 更好 + 1 差不多 + 1 更差 = 0.7")
  void sameCountsAsHalf() {
    assertThat(PromptEvalService.winRate(3, 1, 1)).isEqualTo(0.7);
  }

  @Test
  @DisplayName("全评「更差」是 0.0，且与「没评」区分得开")
  void allWorseIsZeroButNotNull() {
    assertThat(PromptEvalService.winRate(0, 0, 4)).isEqualTo(0.0);
  }
}
