package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.application.HitTestQuery;
import cn.lincoln.aiplatform.retrieval.application.HitTestView;
import cn.lincoln.aiplatform.retrieval.application.MultiHopService;
import cn.lincoln.aiplatform.retrieval.application.QueryUnderstandingService;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import cn.lincoln.aiplatform.retrieval.domain.QueryUnderstanding;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 重排<strong>接线</strong>测试：证明 {@link RetrievalService#hitTest} 真的把 {@link RerankPort} 用上了，
 * 而不是"端口被创建了却没人调用"（{@code #283}：这个项目的头号病是"配了没用上"）。
 *
 * <h3>★ 判据设计：期望值是测试自己指定的，不是照实现抄的</h3>
 *
 * <p>假重排返回一个<strong>测试自己选定</strong>的顺序（{@code [S3, S1, S2]}，与检索原序 {@code [S1, S2, S3]} 明显不同）， 断言
 * {@code hitTest} 的结果顺序就是它 —— <strong>如果重排没接上</strong>（接线被注掉 / 开关关掉 / 端口没人调）， 结果保持检索原序 {@code [S1,
 * S2, S3]}，那条 {@code containsExactly} 断言立刻红。 期望值来自"假重排给什么就该出什么"的接线语义，与实现内部怎么排无关。
 *
 * <p>★ 手写假实现（不引 Mockito），形状照 {@code MultiHopTest} 的夹具。
 */
class RerankWiringTest {

  private static final UUID KB = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final UUID S1 = seg(1);
  private static final UUID S2 = seg(2);
  private static final UUID S3 = seg(3);
  private static final String QUESTION = "违约金上限是多少？";

  @Test
  void rerankIsWiredIntoHitTest() {
    FakeSearch search = new FakeSearch();
    search.answer(
        QUESTION,
        List.of(
            new SegmentSearchPort.Scored(S1, 0.9),
            new SegmentSearchPort.Scored(S2, 0.8),
            new SegmentSearchPort.Scored(S3, 0.7)));
    RecordingRerank rerank = new RecordingRerank(List.of(S3, S1, S2));
    RetrievalService service = service(search, rerank, new RerankProperties(true, 3, "x", "y"));

    HitTestView view = service.hitTest(query());

    // ★ 核心断言（重排没接上必红）：假重排给的顺序必须真的出现在结果里
    assertThat(view.items())
        .as("hitTest 的顺序必须是重排后的 [S3, S1, S2] —— 是 [S1, S2, S3] 就说明重排没被调用")
        .extracting(HitTestView.Item::segmentId)
        .containsExactly(S3, S1, S2);
    // 端口真的被调用了，且参数是接线点该给的
    assertThat(rerank.calls).as("重排端口恰好被调一次").isEqualTo(1);
    assertThat(rerank.lastQuery).as("传的是用户原始问题").isEqualTo(QUESTION);
    assertThat(rerank.lastTopK).as("窗口取配置的 topK").isEqualTo(3);
    // ★ texts 必须是**正文**（重排要按正文算相关性），且是按 id 对上的
    assertThat(rerank.lastTexts)
        .as("texts 按 id 带正文，而不是空串/占位")
        .containsEntry(S1, FakeSearch.contentOf(S1))
        .containsEntry(S2, FakeSearch.contentOf(S2))
        .containsEntry(S3, FakeSearch.contentOf(S3));
  }

  @Test
  void disabledMeansThePortIsNeverTouched() {
    FakeSearch search = new FakeSearch();
    search.answer(
        QUESTION,
        List.of(
            new SegmentSearchPort.Scored(S1, 0.9),
            new SegmentSearchPort.Scored(S2, 0.8),
            new SegmentSearchPort.Scored(S3, 0.7)));
    RecordingRerank rerank = new RecordingRerank(List.of(S3, S1, S2));
    RetrievalService service = service(search, rerank, new RerankProperties(false, 3, "x", "y"));

    HitTestView view = service.hitTest(query());

    // ★ 开关的语义是"一次都不调"（不是"调了让它自己降级"）—— 两次断言都能红：
    //   假如开关不生效，rerank.calls 会是 1、顺序会变成 [S3, S1, S2]。
    assertThat(rerank.calls).as("enabled=false ⇒ 重排端口一次都不许被调").isZero();
    assertThat(view.items())
        .as("enabled=false ⇒ 保持检索原序")
        .extracting(HitTestView.Item::segmentId)
        .containsExactly(S1, S2, S3);
  }

  private static RetrievalService service(
      FakeSearch search, RerankPort rerank, RerankProperties rerankProperties) {
    return new RetrievalService(
        search,
        new FakeEmbedding(),
        new FakeUnderstanding(),
        new FakeMultiHop(),
        new RetrievalBudgetProperties(5000, 5000, 0.6, true, true),
        rerank,
        rerankProperties);
  }

  private static HitTestQuery query() {
    return new HitTestQuery(
        RetrievalMode.FULLTEXT.name(), 0.6, 5, null, List.of(KB), QUESTION, 1, false, 3, false, 1);
  }

  private static UUID seg(int value) {
    return UUID.fromString(String.format("30000000-0000-4000-8000-%012d", value));
  }

  /** 假重排：返回测试指定的顺序，并把收到的参数记下来（证明端口被调用且拿到的是正文）。 */
  private static final class RecordingRerank implements RerankPort {

    private final List<UUID> answer;
    int calls;
    String lastQuery;
    int lastTopK;
    Map<UUID, String> lastTexts;

    RecordingRerank(List<UUID> answer) {
      this.answer = answer;
    }

    @Override
    public List<UUID> rerank(
        String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
      calls++;
      lastQuery = query;
      lastTopK = topK;
      lastTexts = texts;
      return answer;
    }
  }

  /** 假检索：FULLTEXT 单路，按查询返回预置排名。 */
  private static final class FakeSearch implements SegmentSearchPort {

    private final Map<String, List<Scored>> byQuery = new LinkedHashMap<>();
    final List<String> queries = new ArrayList<>();

    static String contentOf(UUID id) {
      return "正文-" + id;
    }

    void answer(String query, List<Scored> scored) {
      byQuery.put(query, scored);
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB);
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      return RetrievalScope.shared(List.of(KB));
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      return Map.of();
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      queries.add(query.question());
      return byQuery.getOrDefault(query.question(), List.of());
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      return List.of();
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
      return new LinkedHashSet<>(candidateIds);
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      List<Card> cards = new ArrayList<>();
      for (UUID id : segmentIds) {
        cards.add(
            new Card(
                id,
                "标题-" + id,
                contentOf(id),
                "文档",
                "库",
                UUID.randomUUID(),
                UUID.randomUUID(),
                ClassificationLevel.INTERNAL));
      }
      return cards;
    }
  }

  /** 假查询理解：原始问题单路，让首轮完全可预测。 */
  private static final class FakeUnderstanding extends QueryUnderstandingService {

    FakeUnderstanding() {
      super(new FakeChat(), new RetrievalBudgetProperties(5000, 5000, 0.6, true, true));
    }

    @Override
    public Outcome understand(
        String question,
        int queryCount,
        boolean decomposeEnabled,
        int maxSubQuestions,
        long budgetMs) {
      return new Outcome(QueryUnderstanding.single(question), 0, null, false, false, List.of(), 0);
    }
  }

  /** 假多跳：本测试里不该被触发（命中数与分数都够），真被调到就给"判定失败"让链路继续。 */
  private static final class FakeMultiHop extends MultiHopService {

    FakeMultiHop() {
      super(new FakeChat());
    }

    @Override
    public Outcome decide(String question, List<SegmentSearchPort.Card> hits, long timeoutMs) {
      return new Outcome(
          cn.lincoln.aiplatform.retrieval.domain.HopDecision.failure("本测试不该触发多跳"), 1);
    }
  }

  /** 假模型端口：本测试里永远不会用到它。 */
  private static final class FakeChat implements ChatPort {

    @Override
    public Optional<UUID> pickChatModel() {
      return Optional.empty();
    }

    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      throw new UnsupportedOperationException("本测试不该真的调模型");
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      throw new UnsupportedOperationException("本测试不该真的调模型");
    }
  }

  /** 全文模式下用不到向量化。 */
  private static final class FakeEmbedding implements EmbeddingPort {

    @Override
    public List<float[]> embed(UUID modelId, List<String> texts) {
      throw new UnsupportedOperationException("全文模式不该向量化");
    }
  }
}
