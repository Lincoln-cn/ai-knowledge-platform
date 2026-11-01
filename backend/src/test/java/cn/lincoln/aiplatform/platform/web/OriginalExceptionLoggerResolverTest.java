package cn.lincoln.aiplatform.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * `#216` 判据 1 的载体：**原始异常必须落日志**，且这个 resolver 不改 HTTP 行为。
 *
 * <p>★ 为什么断言"日志里出现异常的类型名"而不是"返回了 ModelAndView"：真正被 NPE 擦掉的是 **痕迹**，不是响应。断言返回值的测试，在"链路后面又炸一次"时照样绿 ——
 * 那正是这次事故的形状。
 */
@ExtendWith(OutputCaptureExtension.class)
class OriginalExceptionLoggerResolverTest {

  private final OriginalExceptionLoggerResolver resolver = new OriginalExceptionLoggerResolver();

  @Test
  void originalExceptionIsLoggedWithItsType(CapturedOutput output) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/conversations/chat");
    MockHttpServletResponse response = new MockHttpServletResponse();
    IllegalStateException boom = new IllegalStateException("SSE 写到一半炸了");

    Object result = resolver.resolveException(request, response, new Object(), boom);

    assertThat(result).as("★ 不返回 ModelAndView：它不决定用户看到什么").isNull();
    assertThat(output.getOut())
        .as("★ 原始异常的类型与消息必须出现在日志里 —— 否则这次失败没有原因可查")
        .contains("java.lang.IllegalStateException")
        .contains("SSE 写到一半炸了")
        .contains("/api/v1/conversations/chat");
  }

  @Test
  void itRunsBeforeEveryOtherResolver() {
    assertThat(resolver.getOrder())
        .as("★ 必须是最高优先级：排在后面就等着被 NPE 抢先")
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  @Test
  void itDoesNotTouchTheResponse(CapturedOutput output) {
    MockHttpServletResponse response = new MockHttpServletResponse();

    resolver.resolveException(
        new MockHttpServletRequest("GET", "/x"), response, new Object(), new RuntimeException("x"));

    assertThat(response.getStatus()).as("状态码不由它决定").isEqualTo(200);
    assertThat(response.getContentAsByteArray()).as("响应体不由它决定").isEmpty();
  }
}
