package cn.lincoln.aiplatform.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * 响应<strong>已经提交</strong>之后才抛出的异常：先把它记下来，别再让框架的二次错误把它擦掉。
 *
 * <h3>★★ 这个类要解决的问题（`#216`，2026-10-30）</h3>
 *
 * <p>真栈上偶发过一次「第 1 轮提问 ⇒ 500，且<b>响应体一个字节都没有</b>」。翻日志才看清：会话确实开始了一轮问答， 紧接着连续五条 {@code ERROR}，全是同一处
 * Tomcat 层的二次 NPE ——
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "MimeHeaderField.getName()" because "this.headers[i]" is null
 *   at org.apache.tomcat.util.http.MimeHeaders.removeHeader(MimeHeaders.java:417)
 *   at org.apache.coyote.Response.setHeader(Response.java:452)
 *   at org.springframework.web.servlet.DispatcherServlet.processHandlerException(DispatcherServlet.java:1216)
 * </pre>
 *
 * <p>也就是说：<b>响应已经提交</b>（SSE 已经把开头写出去、缓冲也刷了），这时 {@code DispatcherServlet} 还要走标准的异常处理路径去 {@code
 * setHeader}（清掉 {@code Cache-Control} / 重新协商 {@code Content-Type}），而 Tomcat 的 {@code MimeHeaders}
 * 在提交之后已经没有可用的槽位 ⇒ 二次 NPE。 <b>这个 NPE 把原始异常顶掉了</b>：响应体是空的、日志里没有原始异常的类型、也没有堆栈 —— 任何"偶发 500"都变成不可诊断。
 *
 * <h3>处置：只在"已提交"这一格里接管</h3>
 *
 * <ul>
 *   <li>响应<b>未提交</b> ⇒ 返回 {@code null}，<b>不改变任何既有行为</b>（{@code @RestControllerAdvice}、 控制器自己的
 *       {@code @ExceptionHandler}、Spring Security 的既有分工全部照旧）；
 *   <li>响应<b>已提交</b> ⇒ ① 用 {@code ERROR} 记下<b>原始异常的类型、消息与堆栈</b>（带 uri/method/status， 事后可为那一次请求定位）；②
 *       返回一个<b>什么都不写</b>的 {@link ModelAndView}，把"已处理"这件事告诉 {@code
 *       DispatcherServlet}，从而<b>不再进入</b>那条会二次 NPE 的路径（不再写头、不再写体）。
 * </ul>
 *
 * <p>★ 为什么用"no-op view"而不是返回 {@code null}：返回 {@code null} 等于"我没处理"，框架会继续找下一个 resolver，最后仍然回到那条
 * {@code setHeader} 的路径上 —— 灯还是会被关掉。
 *
 * <p>★ 为什么不是"在 SSE 里 try/catch 打印一下"：能走到异常处理的路径不止 SSE（任何"边写边算"的流式端点、 任何已经 {@code flushBuffer()}
 * 之后失败的请求）；这个类是<b>出口处</b>的兜底，不要求每个控制器都记得写一遍。
 *
 * <p>★ 注册方式是 {@link CommittedResponseExceptionResolverConfig} 里的 {@code
 * extendHandlerExceptionResolvers(...).add(0, ...)}：必须排在 {@code ExceptionHandlerExceptionResolver}
 * <b>之前</b>，否则后者的 {@code @ExceptionHandler} 会先去写响应头、在"已提交"时先炸。
 */
public class CommittedResponseExceptionResolver implements HandlerExceptionResolver {

  private static final Logger log =
      LoggerFactory.getLogger(CommittedResponseExceptionResolver.class);

  /** 渲染时什么也不写：响应已经提交，任何写入都只会再抛一次。 */
  private static final View NO_OP_VIEW =
      (model, request, response) -> {
        // 故意留空：响应已提交，这里既不能写头也不能写体。
      };

  @Override
  @Nullable
  public ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception ex) {
    if (!response.isCommitted()) {
      return null;
    }
    log.error(
        "★★ 响应已提交之后仍抛出异常（原始异常在此记录 —— 否则容器的二次错误会把它擦掉）"
            + " uri={} method={} status={} exception={} message={}",
        request.getRequestURI(),
        request.getMethod(),
        response.getStatus(),
        ex.getClass().getName(),
        ex.getMessage(),
        ex);
    ModelAndView mav = new ModelAndView();
    mav.setView(NO_OP_VIEW);
    return mav;
  }
}
