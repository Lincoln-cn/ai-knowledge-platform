package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
  private final JpaPromptConfigRepository prompts;

  public AppBindingAdapter(
      AssistantRepository assistants,
      AccessScopePort accessScope,
      JpaPromptConfigRepository prompts) {
    this.assistants = assistants;
    this.accessScope = accessScope;
    this.prompts = prompts;
  }

  @Override
  public Optional<AppBinding> findVisibleById(UUID appId) {
    if (appId == null) {
      // ★ null 不是"也许有这个应用"：通用问答本来就没有应用，直接空，不去查库
      return Optional.empty();
    }
    return assistants.findById(appId).filter(accessScope::canSee).map(this::binding);
  }

  private AppBinding binding(Assistant app) {
    return new AppBinding(
        app.id(),
        app.modelId(),
        app.kbIds(),
        app.multiTurn(),
        // ★ `#185`：思考开关随绑定进链路；是否真的发 reasoning_effort 由模型三态决定
        app.thinkingEnabled(),
        app.rolePrompt(),
        promptTexts(app.promptIds()),
        // ★ `#185`：应用级检索参数随绑定一起进问答链路。此前它们只存在于实体 / 视图 / 导出里，
        //   `ChatService` 读的是平台设置 ⇒ 配置页那几个控件全是装饰。
        new RetrievalParams(
            app.retrievalMode(),
            app.retrievalThreshold().doubleValue(),
            app.retrievalTopN(),
            app.retrievalNoRefStrategy()));
  }

  /**
   * 应用绑定的提示词正文（{@code #215}）。
   *
   * <p>★★ 为什么排序在这里做、而不是让调用方按 {@code promptIds} 的集合顺序拼： id 是随机 UUID，集合顺序随入库/取库而变 ⇒ 同一条应用每次拼出来的
   * prompt 文本可能不同，而那种不稳定**不会报错**，只会让"同一问题两次答案不一样"变得无法归因。按 {@code typeId → roleName}
   * 排序是确定性的，且与提示词管理页的分组一致。
   *
   * <p>★ 已删除的提示词被绑定关系指向时（`keepSatisfiedPrompts` 只在写路径清，库里仍可能残留）表现为"少了那一段"， 而不是抛异常 —— 拼 prompt 失败导致的
   * 500 比少一段提示词严重得多。
   */
  private List<String> promptTexts(Collection<UUID> promptIds) {
    if (promptIds == null || promptIds.isEmpty()) {
      return List.of();
    }
    return prompts.findAllById(promptIds).stream()
        .filter(p -> p.callWord() != null && !p.callWord().isBlank())
        .sorted(
            Comparator.comparing(
                    (PromptConfig p) -> p.typeId() == null ? "" : p.typeId().toString())
                .thenComparing(p -> p.roleName() == null ? "" : p.roleName()))
        .map(p -> p.callWord().strip())
        .distinct()
        .toList();
  }
}
