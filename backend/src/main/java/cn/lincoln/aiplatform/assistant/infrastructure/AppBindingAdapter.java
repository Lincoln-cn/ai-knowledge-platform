package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link AppBindingPort} 的实现 —— 端口在 {@code platform}、实现在提供方模块（{@code assistant}）。
 *
 * <h3>★ 为什么是这个模块实现它</h3>
 *
 * <p>"哪些应用存在、绑了哪些知识库、用哪个对话模型"的唯一真相源是 {@code as_assistant} 与它的两张子表， 而读写它们的 {@link
 * AssistantRepository} 就在这里。把实现放到别处，等于让"应用绑了什么"长出第二个真相 —— 而它不会报错， 只会在某一侧改了绑定关系之后，另一侧还按旧绑定检索。
 *
 * <h3>★★ 两步的顺序不是随意的</h3>
 *
 * <ol>
 *   <li>先按 id 取（{@link AssistantRepository#findById} 已经滤掉软删 —— 删掉的应用与不存在的应用同一个出口）；
 *   <li>再问 {@link AccessScopePort#canSee}（★ <strong>可见性判定只在这一处实现</strong>，{@code 07 §3.3}）——
 *       这里<strong>不</strong>自己比 {@code ownerId}，也不看 {@code visibility}：那是"归属 + 可见性 + 共享"三条的合成，
 *       抄一遍就多一处会跟着改的地方。
 * </ol>
 *
 * <p>★ 两步都失败（或任一步失败）时对外<strong>只有一个</strong> {@code Optional.empty()}：调用方据此返 404。 "不存在"与"看不见"必须不可分
 * —— 能分辨就等于确认了这个 id 的存在性。
 */
@Component
public class AppBindingAdapter implements AppBindingPort {

  private final AssistantRepository assistants;
  private final AccessScopePort accessScope;

  public AppBindingAdapter(AssistantRepository assistants, AccessScopePort accessScope) {
    this.assistants = assistants;
    this.accessScope = accessScope;
  }

  @Override
  public Optional<AppBinding> findVisibleById(UUID appId) {
    if (appId == null) {
      // ★ null 不是"也许有这个应用"：通用问答本来就没有应用，直接空，不去查库
      return Optional.empty();
    }
    return assistants.findById(appId).filter(accessScope::canSee).map(AppBindingAdapter::binding);
  }

  private static AppBinding binding(Assistant app) {
    return new AppBinding(app.id(), app.modelId(), app.kbIds(), app.multiTurn());
  }
}
