package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.model.ToolProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * "没有工具"的实现（0.8.3 · {@code ROADMAP §11.45.4}）。
 *
 * <p>★ 为什么要有这个类、而不是干脆不装配任何 {@link ToolProvider}：
 *
 * <ul>
 *   <li><strong>不装配的情况</strong>，将来任何一处 {@code ObjectProvider<ToolProvider>} 拿到的是空，
 *       于是"没有工具"与"有人忘了装配"在代码里长得一模一样 —— 这正是本项目反复记录的头号病 <code>silent-failure</code> 的形状；
 *   <li><strong>装配一个明确返回空表的实现</strong>之后，"今天没有工具调用"变成一件 <strong>可以被读出来、也可以被断言</strong>的事：上游请求体里不会出现
 *       {@code tools} 键。
 * </ul>
 *
 * <p>★ 它<strong>不是</strong>"将来会被替换掉的占位实现"意义上的占位 —— 只要没有真正的工具执行链路，它就一直是正确实现。0.9.0
 * 加沙箱时应当<strong>新增</strong>一个实现 （例如 {@code BuiltinToolProvider}）并由配置决定用哪个，而不是把这个类改成半成品。
 */
@Component
public class NoopToolProvider implements ToolProvider {

  /**
   * 恒返回空表。
   *
   * <p>★ 空表而不是 {@code null}：调用方拿到的永远是一个可迭代的集合， "没有工具"不需要额外判断，也就不会出现"忘了判空 ⇒ 上游收到一个 {@code
   * tools:null}"这种事故。
   */
  @Override
  public List<ToolSpec> toolsFor(UUID modelId) {
    return List.of();
  }
}
