package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 请求级列表重排开关（{@code rerankListwise}）到底有没有进到"这一轮真正生效的检索参数"。
 *
 * <p>## 为什么必须有这一条
 *
 * <p>这个开关整条链有四段：请求体（{@code ChatStreamBody}）→ {@code ChatRequest} → {@code PreparedTurn} → {@link
 * ChatService#effectiveRetrieval} 叠进 {@link RetrievalParams}。
 *
 * <p>前三次改造的教训是：**断到"参数对象能存这个值"不算数**。{@code RetrievalParams.withRerankListwise} 有自己的单测，{@code
 * PreparedTurn} 有了这一格也能构造出来 —— 但把 {@code effectiveRetrieval} 里那行 {@code
 * base.withRerankListwise(...)} 删掉，全仓仍然是绿的，而功能已经没了。
 *
 * <p>所以这一条直接断**出口**：给一个带 {@code rerankListwise} 的 {@code PreparedTurn}，看出来的 {@link RetrievalParams}
 * 上是什么。
 *
 * <p>## 三档语义（缺一不可）
 *
 * <p>{@code true} / {@code false} / {@code null} 是三个**不同**的决定，其中 {@code null} = 跟随应用 /
 * 全局。少断任何一档，都会让"用户显式关掉"被读成"没配过"（或反过来）。
 */
class EffectiveRetrievalListwiseTest {

  /**
   * 造一个只关心 {@code retrieval} 与 {@code rerankListwise} 两格的 {@code PreparedTurn}。
   *
   * <p>8 个构造参数全传 {@code null}：{@code effectiveRetrieval} 是纯函数式的一段，只读 {@code turn} 与 {@code
   * policyResolver}（后者为 null 时回落到平台默认值）。
   */
  private static ChatService service() {
    return new ChatService(null, null, null, null, null, null, null, null);
  }

  private static ChatTurnWriter.PreparedTurn turn(
      RetrievalParams retrieval, Boolean rerankListwise) {
    return new ChatTurnWriter.PreparedTurn(
        UUID.randomUUID(), // conversationId
        UUID.randomUUID(), // assistantMessageId
        UUID.randomUUID(), // userMessageId
        "trace-listwise", // traceId
        "test-model", // modelLabel
        UUID.randomUUID(), // modelId
        List.of(UUID.randomUUID()), // kbIds
        false, // multiTurn
        false, // thinkingEnabled
        null, // appId（null = 通用问答）
        null, // rolePrompt
        List.of(), // promptTexts
        retrieval, // 应用级检索参数
        rerankListwise); // ★ 被测的那一格
  }

  private static RetrievalParams appParams() {
    return new RetrievalParams(RetrievalMode.FULLTEXT, 0.42, 7, NoRefStrategy.REFUSE);
  }

  @Test
  @DisplayName("请求写 true ⇒ 这一轮真的开着（并且应用那几格不被冲掉）")
  void trueReachesTheParams() {
    RetrievalParams got = service().effectiveRetrieval(turn(appParams(), true));

    assertThat(got.rerankListwise()).isEqualTo(Boolean.TRUE);
    // ★ 顺带钉住"叠加不是整体替换"：写 true 时 mode / threshold / topN / 无引用策略都得原样在
    assertThat(got.mode()).isEqualTo(RetrievalMode.FULLTEXT);
    assertThat(got.threshold()).isEqualTo(0.42);
    assertThat(got.topN()).isEqualTo(7);
    assertThat(got.noRefStrategy()).isEqualTo(NoRefStrategy.REFUSE);
  }

  @Test
  @DisplayName("请求写 false ⇒ 这一轮真的关着（不能被读成\"没配过\"）")
  void falseReachesTheParams() {
    assertThat(service().effectiveRetrieval(turn(appParams(), false)).rerankListwise())
        .isEqualTo(Boolean.FALSE);
  }

  @Test
  @DisplayName("请求不写（null）⇒ 跟随，不能凭空变成 false")
  void nullStaysFollowing() {
    assertThat(service().effectiveRetrieval(turn(appParams(), null)).rerankListwise()).isNull();
  }

  @Test
  @DisplayName("通用问答（没有应用级参数）也要能叠加请求级开关")
  void worksWithoutAppParams() {
    RetrievalParams got = service().effectiveRetrieval(turn(null, true));

    // 应用为 null ⇒ 回落平台默认值；请求级覆盖仍然生效
    assertThat(got.mode()).isEqualTo(RetrievalMode.FULLTEXT);
    assertThat(got.rerankListwise()).isEqualTo(Boolean.TRUE);
  }
}
