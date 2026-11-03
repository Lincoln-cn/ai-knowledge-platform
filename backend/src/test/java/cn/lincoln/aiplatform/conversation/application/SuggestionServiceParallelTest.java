package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 「猜你想问」的并行版（`#285`）—— 四件事各自能红。
 *
 * <p>★ 为什么不测 {@code ChatService} 那一侧：那一侧的全部逻辑就是"开关关就不调本方法"， 用一条 {@code if}
 * 表达；真正会静默坏的都在**本类**（拿不到上下文还硬猜、模型失败把主回答带崩、 猜出来不落库）。所以这里逐条钉住它们，`ChatService` 那侧由 {@code
 * maybeGuessSuggestions} 的 四道 early-return 守住（它没有任何可独立断言的副作用）。
 */
class SuggestionServiceParallelTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ChatPort chat = mock(ChatPort.class);
  private final ChatPolicyResolver policy = mock(ChatPolicyResolver.class);
  private final SuggestionService service = new SuggestionService(jdbc, chat, policy);

  private final UUID messageId = UUID.randomUUID();
  private final UUID modelId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(policy.suggestionEnabled()).thenReturn(true);
    when(policy.suggestionCount()).thenReturn(3);
  }

  /** 判据 ③ 的前半：没有上下文可依据时不要硬猜 —— 也不该白花一次模型调用。 */
  @Test
  void whenTheFeatureIsOffTheModelIsNotCalledAtAll() {
    when(policy.suggestionEnabled()).thenReturn(false);

    service.guessAndPersist(messageId, modelId, "住宿标准是多少？", "资料 A");

    verifyNoInteractions(chat);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  /**
   * 判据 ③ 的后半：猜的过程失败不许冒泡。
   *
   * <p>★ 这条是"锦上添花不许变成错误"的唯一证据 —— 它调到的是 {@code ChatService} 的异步线程，
   * 一旦把异常抛出去，用户看到的是**一条已经成功的回答**在日志里带一个未捕获异常。
   */
  @Test
  void whenTheUpstreamFailsTheFailureStaysInside() {
    when(chat.complete(any(), anyString(), any())).thenThrow(new IllegalStateException("上游炸了"));

    assertThatCode(() -> service.guessAndPersist(messageId, modelId, "住宿标准是多少？", "资料 A"))
        .doesNotThrowAnyException();
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  /** 判据 ②：真的落库，且落的是同一条助手消息的 `artifacts`。 */
  @Test
  void theGeneratedQuestionsArePersistedOnTheSameMessage() {
    when(chat.complete(any(), anyString(), any()))
        .thenReturn("1. 住宿标准是多少？\n2. 出差补贴多少？\n3. 报销要哪些材料？");

    service.guessAndPersist(messageId, modelId, "住宿标准是多少？", "资料 A");

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(contains("update cv_message set artifacts"), json.capture(), eq(messageId));
    assertThat(json.getValue()).contains("住宿标准是多少？").contains("出差补贴多少？");
  }

  /**
   * 判据 ② 的实质：喂给模型的是**资料**，不是答案。
   *
   * <p>★ 这条是并行版与串行版唯一的语义差别，也是最容易被"顺手改成等答案"的地方 —— 一旦有人把 {@code guessPrompt} 换成 {@code prompt}，这条会红。
   */
  @Test
  void thePromptCarriesContextNotAnAnswer() {
    when(chat.complete(any(), anyString(), any())).thenReturn("1. 甲\n2. 乙\n3. 丙");

    service.guessAndPersist(messageId, modelId, "住宿标准是多少？", "《差旅管理办法》· 住宿费不超过 500");

    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(chat).complete(eq(modelId), prompt.capture(), any(Duration.class));
    assertThat(prompt.getValue()).contains("资料：").contains("住宿费不超过 500").doesNotContain("\n答：");
  }
}
