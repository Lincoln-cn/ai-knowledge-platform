package cn.lincoln.aiplatform.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * `#185` 的第一段：应用上的思考开关必须真的随绑定进链路。
 *
 * <p>★ 这条判据防的是"字段加了、页面能勾、绑定没带"——ChatService 只能看到 {@link AppBindingPort.AppBinding}，这里丢了 {@code
 * thinkingEnabled}，下游再正确也永远收到 false。
 */
class AppBindingAdapterTest {

  @Test
  void carriesThinkingFlagIntoTheBinding() {
    UUID appId = UUID.randomUUID();
    Assistant app = mock(Assistant.class);
    when(app.id()).thenReturn(appId);
    when(app.modelId()).thenReturn(UUID.randomUUID());
    when(app.kbIds()).thenReturn(List.of());
    when(app.multiTurn()).thenReturn(true);
    when(app.thinkingEnabled()).thenReturn(true);
    when(app.rolePrompt()).thenReturn("角色设定");
    when(app.retrievalMode()).thenReturn(RetrievalMode.FULLTEXT);
    when(app.retrievalThreshold()).thenReturn(new BigDecimal("0.60"));
    when(app.retrievalTopN()).thenReturn(5);
    when(app.retrievalNoRefStrategy()).thenReturn(NoRefStrategy.REFUSE);

    AssistantRepository assistants = mock(AssistantRepository.class);
    when(assistants.findById(appId)).thenReturn(Optional.of(app));
    AccessScopePort accessScope = mock(AccessScopePort.class);
    when(accessScope.canSee(app)).thenReturn(true);

    AppBindingPort.AppBinding binding =
        new AppBindingAdapter(assistants, accessScope).findVisibleById(appId).orElseThrow();

    assertThat(binding.thinkingEnabled()).as("★ 应用勾了思考，绑定必须带 true").isTrue();
    assertThat(binding.retrieval())
        .as("检索参数仍然随绑定走，不能被本次改动挤掉")
        .isEqualTo(new RetrievalParams(RetrievalMode.FULLTEXT, 0.60, 5, NoRefStrategy.REFUSE));
  }
}
