package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

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

  @Test
  @DisplayName("后台任务跑在请求的身份下，跑完不留残留 —— 线程池复用，残留身份会让下一次看见别人的范围")
  void backgroundTaskRunsUnderTheRequestIdentityAndLeavesNothingBehind() throws Exception {
    SecurityContext request = SecurityContextHolder.createEmptyContext();
    request.setAuthentication(new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicReference<String> leftover = new AtomicReference<>("未执行");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              () -> {
                PromptEvalService.runAs(
                    request,
                    () ->
                        seen.set(SecurityContextHolder.getContext().getAuthentication().getName()));
                leftover.set(
                    String.valueOf(SecurityContextHolder.getContext().getAuthentication()));
              })
          .get();
    } finally {
      pool.shutdownNow();
    }
    assertThat(seen.get()).as("后台线程必须看得见请求线程那个身份 —— 否则可见性判定会判「应用不存在」（#363）").isEqualTo("alice");
    assertThat(leftover.get()).as("跑完必须换回原值：线程池复用，残留身份会把别人的库当成自己的").isEqualTo("null");
  }
}
