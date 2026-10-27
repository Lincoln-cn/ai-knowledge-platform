package cn.lincoln.aiplatform.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort.AppBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * `#215`：应用绑定的提示词**正文**必须真的被取出来、且顺序是确定的。
 *
 * <p>★★ 这一格此前完全不存在（`AppBinding` 里根本没有提示词字段），所以它有三个可能为假的地方，逐个钉：
 *
 * <ol>
 *   <li>取不到正文 —— 绑了三条、拼出来零条（界面显示已选、行为一字不变，且不报错）；
 *   <li>取到了但顺序不稳 —— id 是随机 UUID、`findAllById` 的返回顺序随库而变，同一应用两次问答可能拼出不同 prompt，而"答案不一样"这件事没有报错可追；
 *   <li>库里引用了一条已被删掉的提示词 —— 应当表现为"少一段"，而不是抛异常把整轮问答变成 500。
 * </ol>
 *
 * <p>★ 为什么这层要单测而不是只靠 HTTP 闸门：HTTP 闸门里应用只绑一条提示词，只要有一条进去就算绿 —— 排序与去重那两条它<b>看不见</b>。
 */
class AppBindingAdapterPromptTextsTest {

  private static final UUID APP_ID = UUID.fromString("0b000000-0000-4000-8000-0000000000a1");
  private static final UUID MODEL_ID = UUID.fromString("0b000000-0000-4000-8000-0000000000a2");
  private static final UUID OWNER_ID = UUID.fromString("0b000000-0000-4000-8000-0000000000a3");
  private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

  @Test
  void boundPromptTextsComeBackInAStableOrderWhateverOrderTheDatabaseReturns() {
    UUID typeA = UUID.fromString("0b000000-0000-4000-8000-0000000000c1");
    UUID typeB = UUID.fromString("0b000000-0000-4000-8000-0000000000c2");
    PromptConfig secondInTypeA = prompt("SECOND_IN_TYPE_A", typeA, "beta");
    PromptConfig firstInTypeA = prompt("FIRST_IN_TYPE_A", typeA, "alpha");
    PromptConfig onlyInTypeB = prompt("ONLY_IN_TYPE_B", typeB, "zeta");

    // ★ 故意按"与期望相反"的顺序返回：如果实现依赖仓库返回顺序，这条就红
    AppBinding binding =
        bindingWith(
            List.of(onlyInTypeB, secondInTypeA, firstInTypeA),
            List.of(onlyInTypeB.id(), secondInTypeA.id(), firstInTypeA.id()));

    assertThat(binding.promptTexts())
        .as("按 typeId → roleName 稳定排序（typeA 是更小的 UUID）")
        .containsExactly("FIRST_IN_TYPE_A", "SECOND_IN_TYPE_A", "ONLY_IN_TYPE_B");
  }

  @Test
  void blankCallWordsAreDroppedAndIdenticalTextsCollapseIntoOne() {
    // ★ 空正文在领域层就写不进去（PromptConfig.update 抛"提示词不能为空"），所以这里只能用 mock 造出
    //   "库里残留一条空正文"的形状 —— 那是历史数据 / 直接改库才可能的，而拼 prompt 的一方必须扛住它。
    PromptConfig blank = mock(PromptConfig.class);
    UUID blankId = UUID.randomUUID();
    when(blank.id()).thenReturn(blankId);
    when(blank.callWord()).thenReturn("   ");
    when(blank.typeId()).thenReturn(UUID.randomUUID());
    when(blank.roleName()).thenReturn("a");
    PromptConfig repeated = prompt("SAME_TEXT", UUID.randomUUID(), "b");
    PromptConfig repeatedAgain = prompt("  SAME_TEXT  ", UUID.randomUUID(), "c");

    AppBinding binding =
        bindingWith(
            List.of(blank, repeated, repeatedAgain),
            List.of(blankId, repeated.id(), repeatedAgain.id()));

    assertThat(binding.promptTexts())
        .as("空正文没有意义（等于没绑）；同一段文本只留一份，且去掉首尾空白")
        .containsExactly("SAME_TEXT");
  }

  @Test
  void aBoundPromptThatNoLongerExistsJustDisappearsInsteadOfFailingTheTurn() {
    PromptConfig alive = prompt("ALIVE", UUID.randomUUID(), "a");
    UUID deletedId = UUID.randomUUID();

    // 库里只剩一条：另一条被删了（写路径只在保存时清理绑定，库里可能残留）
    AppBinding binding = bindingWith(List.of(alive), List.of(alive.id(), deletedId));

    assertThat(binding.promptTexts()).as("少一段提示词远好于拼 prompt 失败导致的 500").containsExactly("ALIVE");
  }

  @Test
  void anAppWithNoBoundPromptsGetsAnEmptyListNotANull() {
    AppBinding binding = bindingWith(List.of(), List.of());

    assertThat(binding.promptTexts()).as("空 = 没绑，不是绑了一条空提示词").isEmpty();
  }

  private static PromptConfig prompt(String callWord, UUID typeId, String roleName) {
    return new PromptConfig(UUID.randomUUID(), roleName, callWord, typeId, "说明", "📄", NOW);
  }

  private static AppBinding bindingWith(List<PromptConfig> stored, List<UUID> boundIds) {
    Assistant app =
        new Assistant(
            APP_ID,
            "提示词夹具",
            "📄",
            "说明",
            AssistantType.SIMPLE,
            MODEL_ID,
            "APP_ROLE_PROMPT",
            OWNER_ID,
            OWNER_ID);
    app.bind(List.of(), boundIds, null);

    AssistantRepository assistants = mock(AssistantRepository.class);
    when(assistants.findById(any())).thenReturn(Optional.of(app));
    AccessScopePort accessScope = mock(AccessScopePort.class);
    when(accessScope.canSee(any())).thenReturn(true);
    JpaPromptConfigRepository prompts = mock(JpaPromptConfigRepository.class);
    // ★ 先算好返回值再 stub：在 thenReturn(...) 的参数里调用另一个 mock 的方法会让 Mockito 以为
    //   stub 还没结束（UnfinishedStubbingException）—— 一个与被测代码无关的陷阱。
    List<PromptConfig> visible = stored.stream().filter(p -> boundIds.contains(p.id())).toList();
    when(prompts.findAllById(anyCollection())).thenReturn(visible);

    return new AppBindingAdapter(assistants, accessScope, prompts)
        .findVisibleById(APP_ID)
        .orElseThrow();
  }
}
