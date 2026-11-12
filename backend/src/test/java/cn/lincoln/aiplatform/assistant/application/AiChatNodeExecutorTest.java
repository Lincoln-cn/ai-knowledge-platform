package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AiChatNodeExecutor} 的判据。
 *
 * <p>★ 这一组测试的存在理由：{@code AiChatNodeExecutor} 是 0.7.1 补的洞 —— {@code SUPPORTED_TYPES} 从 0.7.0 起就写着
 * {@code AI_CHAT_NODE}，而当时没有它的执行器， 于是带 AI 节点的图**能通过发布校验、点试用必炸**。所以这里要断的不只是「能跑」， 还有「它真的实现了那个类型」。
 */
class AiChatNodeExecutorTest {

  private static final UUID MODEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID EXECUTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static WorkflowNodeContext context(
      String input, Map<String, Object> config, FlowState state) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-ai", "AI_CHAT_NODE", input, state, config, null);
  }

  @Test
  @DisplayName("实时：节点声明的类型就是 SUPPORTED_TYPES 里那一个")
  void declaresTheTypeThatPublishValidationChecks() {
    // 发布校验读的就是 LinearWorkflowExecutor.SUPPORTED_TYPES；类型字符串对不上，
    // 校验会放行而运行时 registry 找不到执行器 —— 那正是 0.7.0 的洞。
    assertThat(new AiChatNodeExecutor(mock(ChatPort.class)).type())
        .isEqualTo("AI_CHAT_NODE")
        .isIn(LinearWorkflowExecutor.SUPPORTED_TYPES);
  }

  @Test
  @DisplayName("只有问题、没有检索结果时也要能回答（不编资料）")
  void answersWithoutRetrievalResult() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("这是回答");
    FlowState state = new FlowState();

    new AiChatNodeExecutor(chatPort)
        .execute(
            context(
                "住宿标准是多少？",
                Map.of(AiChatNodeExecutor.CONFIG_MODEL_ID, MODEL_ID.toString()),
                state));

    assertThat(state.read(AiChatNodeExecutor.ANSWER_KEY)).isEqualTo("这是回答");
    // 没有资料时不能往提示词里塞「参考资料：」空段 —— 那会让模型对着空标题编内容。
    var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(chatPort).complete(any(), prompt.capture(), any(Duration.class));
    assertThat(prompt.getValue()).contains("住宿标准是多少？").doesNotContain("参考资料：");
  }

  @Test
  @DisplayName("有检索结果时提示词带资料，并要求不许编造")
  void usesRetrievalResultWhenPresent() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("依据资料的回答");
    FlowState state = new FlowState();
    state.write(SearchDatasetNodeExecutor.RESULT_KEY, "《差旅费管理办法》第三十一条：住宿标准……", KeyUpdate.REPLACE);

    new AiChatNodeExecutor(chatPort)
        .execute(
            context(
                "住宿标准是多少？",
                Map.of(AiChatNodeExecutor.CONFIG_MODEL_ID, MODEL_ID.toString()),
                state));

    var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(chatPort).complete(any(), prompt.capture(), any(Duration.class));
    assertThat(prompt.getValue()).contains("参考资料：").contains("第三十一条").contains("不要编造");
  }

  @Test
  @DisplayName("节点没配 modelId 时用平台默认模型（老图不该因升级跑不动）")
  void fallsBackToPlatformModelWhenNotConfigured() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("默认模型回答");
    FlowState state = new FlowState();

    new AiChatNodeExecutor(chatPort).execute(context("问题", Map.of(), state));

    assertThat(state.read(AiChatNodeExecutor.ANSWER_KEY)).isEqualTo("默认模型回答");
    verify(chatPort)
        .complete(org.mockito.ArgumentMatchers.eq(MODEL_ID), anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("没有默认模型也没有配置时明确失败，而不是静默产出空回答")
  void failsWhenNoModelAtAll() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                new AiChatNodeExecutor(chatPort).execute(context("问题", Map.of(), new FlowState())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有可用模型");
    verify(chatPort, never()).complete(any(), anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("空回答算失败：200 但没正文，与调用失败后果一样")
  void blankAnswerIsAFailure() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("   ");
    FlowState state = new FlowState();

    assertThatThrownBy(
            () ->
                new AiChatNodeExecutor(chatPort)
                    .execute(
                        context(
                            "问题",
                            Map.of(AiChatNodeExecutor.CONFIG_MODEL_ID, MODEL_ID.toString()),
                            state)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("空回答");
    assertThat(state.read(AiChatNodeExecutor.ANSWER_KEY)).isNull();
  }

  @Test
  @DisplayName("提示词拼装：资料为空时不出现空标题")
  void promptForEmptyRetrievalHasNoEmptySection() {
    assertThat(AiChatNodeExecutor.buildPrompt("问题", "")).contains("问题：问题").doesNotContain("参考资料：");
    assertThat(AiChatNodeExecutor.buildPrompt("问题", "   ")).doesNotContain("参考资料：");
    assertThat(AiChatNodeExecutor.buildPrompt("问题", "资料正文")).contains("资料正文");
  }

  @Test
  @DisplayName("节点配置里的 modelId 不是 UUID 时退回默认模型，不打断整张图")
  void nonUuidModelIdFallsBack() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("回答");
    FlowState state = new FlowState();

    new AiChatNodeExecutor(chatPort)
        .execute(context("问题", Map.of(AiChatNodeExecutor.CONFIG_MODEL_ID, "qwen2.5-1.5b"), state));

    assertThat(state.read(AiChatNodeExecutor.ANSWER_KEY)).isEqualTo("回答");
    verify(chatPort)
        .complete(org.mockito.ArgumentMatchers.eq(MODEL_ID), anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("没拿到问题时明确失败（START_NODE 没跑或这次执行没有输入）")
  void failsWithoutQuestion() {
    ChatPort chatPort = mock(ChatPort.class);
    assertThatThrownBy(
            () -> new AiChatNodeExecutor(chatPort).execute(context(" ", Map.of(), new FlowState())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有拿到问题");
  }

  @Test
  @DisplayName("SUPPORTED_TYPES 里的每个类型都真有执行器 —— 这才是「发布放行、运行时不炸」的判据")
  void everySupportedTypeHasAnExecutor() {
    // 0.7.0 的洞：SUPPORTED_TYPES 写着 AI_CHAT_NODE，而实现只有三个 ——
    // 发布校验读的就是这个集合，于是校验放行、点试用时 registry.get 拿不到执行器。
    // 这里把四个实现都摆出来断「集合相等」：以后往集合里加类型而忘了加实现，这条会红。
    Map<String, NodeExecutor> byType = new java.util.LinkedHashMap<>();
    for (NodeExecutor executor :
        List.of(
            new StartNodeExecutor(),
            new BaseNodeExecutor(),
            new SearchDatasetNodeExecutor(providerOf(mock(WorkflowSearchPort.class))),
            new ConditionNodeExecutor(mock(ChatPort.class)),
            new NumberConditionNodeExecutor(),
            new AiChatNodeExecutor(mock(ChatPort.class)),
            new ReplyNodeExecutor())) {
      byType.put(executor.type(), executor);
    }

    assertThat(byType.keySet()).isEqualTo(LinearWorkflowExecutor.SUPPORTED_TYPES);
  }

  /** 造一个只吐一个实例的 {@code ObjectProvider}：{@code SearchDatasetNodeExecutor} 要的是它而不是裸端口。 */
  private static ObjectProvider<WorkflowSearchPort> providerOf(WorkflowSearchPort port) {
    return new ObjectProvider<>() {
      @Override
      public WorkflowSearchPort getObject(Object... args) {
        return port;
      }

      @Override
      public WorkflowSearchPort getIfAvailable() {
        return port;
      }

      @Override
      public WorkflowSearchPort getIfUnique() {
        return port;
      }

      @Override
      public WorkflowSearchPort getObject() {
        return port;
      }

      @Override
      public java.util.Iterator<WorkflowSearchPort> iterator() {
        return List.of(port).iterator();
      }
    };
  }
}
