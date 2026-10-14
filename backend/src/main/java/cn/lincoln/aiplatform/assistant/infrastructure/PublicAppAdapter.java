package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.PublicAppPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link PublicAppPort} 的实现 —— 端口在 {@code platform}、实现在提供方模块（{@code 08 §4.4.2}）。
 *
 * <h3>★ 为什么两行 {@code findById + canSee} 也值得一个类</h3>
 *
 * <p>因为它把<strong>"可见性判定只有一处"</strong>（{@code 07 §3.3}）这条纪律钉在一个可评审的位置上。 若让公开面的控制器自己 {@code findById}
 * 再自己比 {@code ownerId / visibility}，那处比较就是第二份判定， 而它与 {@link AccessScopePort}
 * 的漂移是<strong>静默的</strong>。
 *
 * <p>★ 与 {@link AppBindingAdapter} 的分工：那个回答"这一轮问答用哪个模型、检索哪些库"，
 * 这个回答"访客第一屏看到什么"。两者共用同一条可见性判定，因此不会出现"看得见应用却看不见它的绑定"。
 *
 * <h3>★ 两步的顺序与同一个出口</h3>
 *
 * <ol>
 *   <li>先按 id 取（{@code AssistantRepository.findById} 已滤掉软删 —— 删掉的应用与不存在的应用同一个出口）；
 *   <li>再问 {@code canSee}（★ 那一处内部认的是<strong>当前生效范围</strong>，公开面上就是 {@code
 *       AccessScopePort#asSubject} 装上的"归属人范围 ∩ 冻结密级 ∩（有登录调用者时）调用者自己"）。
 * </ol>
 *
 * <p>★ 两步任一失败都只对外给出一个 {@link Optional#empty()}：调用方据此返 404。 "不存在"与"看不见"必须不可分 —— 能分辨就等于确认了这个 id
 * 的存在性（{@code 08 §4.4.3} 纪律 2）。
 */
@Component
public class PublicAppAdapter implements PublicAppPort {

  private final AssistantRepository assistants;
  private final AccessScopePort accessScope;

  public PublicAppAdapter(AssistantRepository assistants, AccessScopePort accessScope) {
    this.assistants = assistants;
    this.accessScope = accessScope;
  }

  @Override
  public Optional<PublicApp> findVisibleById(UUID appId) {
    if (appId == null) {
      // ★ null 不是"也许有这个应用"：凭证一定指向一个应用 id，空值只可能来自调用方的破洞
      return Optional.empty();
    }
    return assistants.findById(appId).filter(accessScope::canSee).map(PublicAppAdapter::publicApp);
  }

  /**
   * 实体 ⇒ 展示面。
   *
   * <p>★ {@code description} 归一成空串：{@code V29} 那一列可为 {@code null}，而契约的 {@code description} 是必填字符串。
   * 让 {@code null} 直接漏出去，会让前端把"没有描述"当成"字段缺失"（两者在公开页上是同一屏，但在类型上是两件事）。
   */
  private static PublicApp publicApp(Assistant app) {
    return new PublicApp(
        app.id(),
        app.name(),
        app.icon(),
        app.description() == null ? "" : app.description(),
        app.prologue(),
        app.quickQuestions());
  }
}
