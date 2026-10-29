package cn.lincoln.aiplatform.platform.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 {@link CommittedResponseExceptionResolver} 插到异常处理链的<b>最前面</b>。
 *
 * <h3>★ 为什么用 {@code extendHandlerExceptionResolvers} 而不是 {@code @Component}</h3>
 *
 * <p>一个 {@code HandlerExceptionResolver} <b>bean</b> 会被 {@code DispatcherServlet} 收集，但
 * <em>顺序</em>取决于各 bean 的 {@code Ordered}；而 Spring MVC 默认那三个 resolver 被打包成一个 {@code
 * HandlerExceptionResolverComposite}（同样是一个 bean）。靠 {@code @Order} 去抢在它前面， 等于依赖"这个组合器恰好没设顺序"这一实现细节
 * —— 哪天 Spring 给它一个负数就静默失效。
 *
 * <p>{@code extendHandlerExceptionResolvers} 是官方给的<b>插入点</b>：拿到的是最终会被使用的那张列表， {@code add(0, ...)}
 * 就是"第一个跑"。顺序在这里是<b>写死的</b>，不靠推断。
 *
 * <p>★ 只在"响应已提交"这一格里生效（见 {@link CommittedResponseExceptionResolver} 的类注释）： 未提交时它返回 {@code
 * null}，后面那三个 resolver 与 {@code @RestControllerAdvice} 的行为一个字都没变。
 */
@Configuration
public class CommittedResponseExceptionResolverConfig implements WebMvcConfigurer {

  @Override
  public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
    resolvers.add(0, new CommittedResponseExceptionResolver());
  }
}
