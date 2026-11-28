package cn.lincoln.aiplatform.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RetrievalRequest} 的形状判据。
 *
 * <p>★ 为什么值得写：{@link RetrievalRequest#of} 是"命中测试的请求翻成检索请求"的唯一入口，它有 12 个字段 —— 漏搬一个（最典型的是 {@code
 * rerankListwise}，它决定这一批要不要多花 3 秒调 LLM）不会有任何编译错误， 只会让"我在界面上下拉选了 listwise、结果没生效"变成一件永远查不出来的事。
 *
 * <p>★ 所以这里的每条断言都在问同一个问题：<strong>这个字段是不是原样过去了</strong>。
 */
class RetrievalRequestTest {

  private static HitTestQuery fullQuery() {
    return new HitTestQuery(
        "FULLTEXT",
        0.85,
        9,
        "FREE",
        List.of(UUID.randomUUID()),
        "财务资产部负责哪些预算事项",
        2,
        false,
        4,
        true,
        3,
        true);
  }

  @Test
  @DisplayName("命中测试的十二个字段全部原样翻过去（漏一个都是静默失效）")
  void everyFieldOfHitTestQueryIsCarriedOver() {
    HitTestQuery query = fullQuery();
    RetrievalRequest request = RetrievalRequest.of(query);

    assertThat(request.mode()).isEqualTo("FULLTEXT");
    assertThat(request.threshold()).isEqualTo(0.85);
    assertThat(request.topN()).isEqualTo(9);
    assertThat(request.noRefStrategy()).isEqualTo("FREE");
    assertThat(request.kbIds()).isEqualTo(query.kbIds());
    assertThat(request.question()).isEqualTo("财务资产部负责哪些预算事项");
    assertThat(request.queryCount()).isEqualTo(2);
    assertThat(request.decomposeEnabled()).isFalse();
    assertThat(request.maxSubQuestions()).isEqualTo(4);
    assertThat(request.multiHopEnabled()).isTrue();
    assertThat(request.maxHops()).isEqualTo(3);
    // ★ 这一条是本次为什么要写这个测试的理由：它错了不会报错，只会让"选了 listwise 却没生效"没人查得出。
    assertThat(request.rerankListwise()).isTrue();
  }

  @Test
  @DisplayName("命中测试没有会话、不并个人库、阈值只做入口参数")
  void hitTestRequestHasNoConversationAndDoesNotFilterAfterwards() {
    RetrievalRequest request = RetrievalRequest.of(fullQuery());

    assertThat(request.conversationId()).isNull();
    assertThat(request.includePersonal()).isFalse();
    assertThat(request.thresholdPolicy()).isEqualTo(RetrievalRequest.ThresholdPolicy.HIT_TEST);
    // ★ 命中测试不带域进来：域由 retrieve 自己按 kbIds 解析（对话侧才需要传"已解析的那一支"）
    assertThat(request.scope()).isNull();
  }

  @Test
  @DisplayName("缺省值与 HitTestQuery 逐字一致（两边默认值不同 = 演示时好用、部署后不一样）")
  void defaultsMatchTheContract() {
    RetrievalRequest request =
        RetrievalRequest.of(
            new HitTestQuery(null, null, null, null, null, "问", null, null, null, null, null));

    assertThat(request.modeOrDefault()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(request.thresholdOrDefault()).isEqualTo(0.6);
    assertThat(request.topNOrDefault()).isEqualTo(5);
    assertThat(request.queryCountOrDefault()).isEqualTo(HitTestQuery.DEFAULT_QUERY_COUNT);
    assertThat(request.decomposeEnabledOrDefault()).isTrue();
    assertThat(request.maxSubQuestionsOrDefault())
        .isEqualTo(HitTestQuery.DEFAULT_MAX_SUB_QUESTIONS);
    // ★ 多跳缺省关：增加延迟与 token，MVP 的 B4 是首字延迟 ≤ 10s。
    assertThat(request.multiHopEnabledOrDefault()).isFalse();
    assertThat(request.maxHopsOrDefault()).isEqualTo(HitTestQuery.DEFAULT_MAX_HOPS);
    // ★ 没指定 listwise ⇒ null，交给全局配置（不是 false ——"这次没指定"与"这次明确关掉"是两件事）。
    assertThat(request.rerankListwise()).isNull();
  }
}
