package cn.lincoln.aiplatform.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * 把"异常在响应已提交后再处理"时被擦掉的原始异常先记下来（`#216` 观测那一半）。
 *
 * <p>★ 为什么需要它：`DispatcherServlet.processHandlerException` 在响应已提交时仍会去 `setHeader`，Tomcat 的
 * `MimeHeaders` 此时可能被置空 ⇒ 二次 NPE。那个 NPE **替换掉原始异常**： 响应体是空的、日志里没有原始异常的类型与堆栈 —— 一次有原因的失败，变成"没有原因"。
 *
 * <p>★ 这个类只做一件事：**在链路更前面把原始异常落日志，然后返回 {@code null} 让后续 resolver 照常工作**。它不改变 HTTP
 * 行为（不改状态码、不写响应体），所以不会与既有 `GlobalExceptionHandler` 抢职责；它存在的价值是"哪怕后面又炸一次，日志里已经有人说话了"。
 *
 * <p>★ 为什么返回 {@code null} 而不是造一个响应：造响应意味着它要决定"用户看到什么"，那是 `GlobalExceptionHandler`
 * 的活；两处都决定，就会出现两种口径。
 */
@Component
public class OriginalExceptionLoggerResolver implements HandlerExceptionResolver, Ordered {

  private static final Logger log = LoggerFactory.getLogger(OriginalExceptionLoggerResolver.class);

  @Override
  public ModelAndView resolveException(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    log.error(
        "请求处理抛异常（原始）：{} {} → {}；响应是否已提交：{}",
        request.getMethod(),
        request.getRequestURI(),
        ex.getClass().getName(),
        response.isCommitted(),
        ex);
    return null;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
