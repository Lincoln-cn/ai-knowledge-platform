package canaryfixture.access;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 金丝雀样本：**第二处**可见性判定实现。
 *
 * <p>它存在的唯一目的，是让 {@code AccessScopeUniquenessTest} 能断言"分叉会被抓住"。 它<strong>不该被"修好"</strong> —— 给它加上
 * {@code @Profile} 或搬进 identity 的那一刻， 那条金丝雀就永久失效了，而且失效是**静默**的（测试仍然绿）。
 *
 * <p>放在 {@code canaryfixture} 顶层包而不是应用包下：它带 {@code @Component}， 放进应用包会被真的注册成一个 bean 并参与判定 ——
 * 那正是我们要防的事。
 */
@Component
public class SecondScopeImplementation implements AccessScopePort {

  @Override
  public ScopeFilter currentScope() {
    return ScopeFilter.nobody();
  }

  @Override
  public boolean canSee(OwnedResource resource) {
    return false;
  }

  /**
   * ⚠️ 金丝雀样本的委派实现：**故意什么都不做**。
   *
   * <p>★ 不要在这里补成"真的委派"——它是一份**坏样本**，它的价值就在于"这是一处不该存在的判定实现"。 补上 {@code asScope} 只是为了让这个类在 {@code
   * AccessScopePort} 长出第 4 个方法之后仍然可编译 （否则那条金丝雀会因为编译失败而**看起来红了**，而它红的理由与被测的东西无关）。
   */
  @Override
  public <T> T asScope(ScopeFilter delegated, UUID requesterId, Supplier<T> body) {
    return body.get();
  }

  /** 只是为了让这个类"看起来像真的会在用"，避免被当成死代码删掉。 */
  public Set<String> rolesItThinks() {
    return Set.of("CANARY");
  }
}
