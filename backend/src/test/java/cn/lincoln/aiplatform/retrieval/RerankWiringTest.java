package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.EntityRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.ListwiseRerankPort;
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
    RetrievalService service =
        service(search, rerank, new RerankProperties(true, 3, "x", "y", false, 5));

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
    // ★ texts 是"文档名 + 换行 + 正文"（`#312` E2 实测：只喂正文 B3 68.2%，加文档名 71.3%），
    //   且是按 id 对上的
    assertThat(rerank.lastTexts)
        .as("texts 按 id 带「文档名\\n正文」，而不是空串/占位")
        .containsEntry(S1, FakeSearch.textsOf(S1))
        .containsEntry(S2, FakeSearch.textsOf(S2))
        .containsEntry(S3, FakeSearch.textsOf(S3));
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
    RetrievalService service =
        service(search, rerank, new RerankProperties(false, 3, "x", "y", false, 5));

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
    return service(search, rerank, rerankProperties, ListwiseRerankPort.noop());
  }

  private static RetrievalService service(
      FakeSearch search,
      RerankPort rerank,
      RerankProperties rerankProperties,
      ListwiseRerankPort listwise) {
    return new RetrievalService(
        search,
        new FakeEmbedding(),
        new FakeUnderstanding(),
        new FakeMultiHop(),
        new RetrievalBudgetProperties(5000, 5000, 0.6, true, true),
        rerank,
        rerankProperties,
        listwise,
        // ★ 这个测试关心的是重排接线，不是实体扩召回 ⇒ 显式给"一路都没有"，
        //   不让它悄悄接上一个会改候选集的替身（那会让顺序断言失去意义）。
        (kbId, question) -> java.util.List.of());
  }

  /** 带实体扩召回的构造（0.6.2 的判据用）。 */
  private static RetrievalService serviceWithRecall(
      FakeSearch search, RerankPort rerank, EntityRecallPort recall) {
    return new RetrievalService(
        search,
        new FakeEmbedding(),
        new FakeUnderstanding(),
        new FakeMultiHop(),
        new RetrievalBudgetProperties(5000, 5000, 0.6, true, true),
        rerank,
        RerankProperties.defaults(),
        ListwiseRerankPort.noop(),
        recall);
  }

  /**
   * ★★ LLM listwise 的接线（用户 2026-11-08：「不默认开启，做成配置」）。
   *
   * <p>判据设计与上面两条同源：假 listwise 返回测试自己选的 {@code [S2, S3, S1]}， 与检索原序 {@code [S1, S2, S3]} 完全不同 ⇒
   * 接线没生效时结果不会是它。
   */
  @Test
  void listwiseRunsWhenRequestTurnsItOn() {
    FakeSearch search = new FakeSearch();
    search.answer(
        QUESTION,
        List.of(
            new SegmentSearchPort.Scored(S1, 0.9),
            new SegmentSearchPort.Scored(S2, 0.8),
            new SegmentSearchPort.Scored(S3, 0.7)));
    RecordingListwise listwise = new RecordingListwise(List.of(S2, S3, S1));
    // 全局关 + 请求开 ⇒ 请求赢
    RetrievalService service =
        service(
            search,
            RerankPort.noop(),
            new RerankProperties(false, 3, "x", "y", false, 5),
            listwise);

    HitTestView view = service.hitTest(queryWithListwise(Boolean.TRUE));

    assertThat(view.items())
        .as("请求 rerankListwise=true ⇒ 顺序必须是假 listwise 给的 [S2, S3, S1]")
        .extracting(HitTestView.Item::segmentId)
        .containsExactly(S2, S3, S1);
    assertThat(listwise.calls).as("listwise 端口恰好被调一次").isEqualTo(1);
    assertThat(listwise.lastQuery).as("传的是用户原始问题").isEqualTo(QUESTION);
  }

  /**
   * ★ 反证：请求显式关掉时，即使全局开着，listwise 也<strong>一次都不许被调</strong>。
   *
   * <p>这条能红：假如判档写反（先看全局再看请求、或忽略请求），{@code calls} 会是 1、顺序会变成 {@code [S3, S1, S2]}。
   */
  @Test
  void requestCanTurnListwiseOffWhileGlobalIsOn() {
    FakeSearch search = new FakeSearch();
    search.answer(
        QUESTION,
        List.of(
            new SegmentSearchPort.Scored(S1, 0.9),
            new SegmentSearchPort.Scored(S2, 0.8),
            new SegmentSearchPort.Scored(S3, 0.7)));
    RecordingListwise listwise = new RecordingListwise(List.of(S3, S1, S2));
    RetrievalService service =
        service(
            search, RerankPort.noop(), new RerankProperties(false, 3, "x", "y", true, 5), listwise);

    HitTestView view = service.hitTest(queryWithListwise(Boolean.FALSE));

    assertThat(listwise.calls).as("请求显式关 ⇒ listwise 端口一次都不许被调").isZero();
    assertThat(view.items())
        .as("请求显式关 ⇒ 保持检索原序")
        .extracting(HitTestView.Item::segmentId)
        .containsExactly(S1, S2, S3);
  }

  private static HitTestQuery query() {
    return new HitTestQuery(
        RetrievalMode.FULLTEXT.name(), 0.6, 5, null, List.of(KB), QUESTION, 1, false, 3, false, 1);
  }

  /**
   * 带 listwise 档位的查询。
   *
   * @param rerankListwise {@code null} = 这次请求没指定（跟随全局）
   * @return 查询
   */
  private static HitTestQuery queryWithListwise(Boolean rerankListwise) {
    return new HitTestQuery(
        RetrievalMode.FULLTEXT.name(),
        0.6,
        5,
        null,
        List.of(KB),
        QUESTION,
        1,
        false,
        3,
        false,
        1,
        rerankListwise);
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

  /** 假 listwise：返回测试指定的顺序，并把收到的参数记下来。 */
  private static final class RecordingListwise implements ListwiseRerankPort {

    private final List<UUID> answer;
    int calls;
    String lastQuery;
    int lastTopK;

    RecordingListwise(List<UUID> answer) {
      this.answer = answer;
    }

    @Override
    public List<UUID> rerank(
        String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
      calls++;
      lastQuery = query;
      lastTopK = topK;
      return List.copyOf(answer);
    }
  }

  /** 假检索：FULLTEXT 单路，按查询返回预置排名。 */
  private static final class FakeSearch implements SegmentSearchPort {

    private final Map<String, List<Scored>> byQuery = new LinkedHashMap<>();
    final List<String> queries = new ArrayList<>();

    static String contentOf(UUID id) {
      return "正文-" + id;
    }

    /** 喂给重排的文本 = 文档名 + 换行 + 正文（与 {@code RetrievalService} 的接线一致）。 */
    static String textsOf(UUID id) {
      return DOC_NAME + "\n" + contentOf(id);
    }

    private static final String DOC_NAME = "文档";

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
                DOC_NAME,
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

  /**
   * 0.6.2 判据：实体扩召回带进来的段，必须是**别的路找不到、但最后出现在结果里**的那一条。
   *
   * <p>★ 这条测试存在的理由：接线之前，23 条既有测试全绿也没证明扩召回改变了任何东西 —— 它们只证明"没破"。要让这条判据真的能红，必须让 E1 只能从实体那一路进来。
   */
  @Test
  void entityRecallBringsInASegmentThatNoOtherPathFound() {
    UUID fromFullText = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001");
    UUID fromEntity = UUID.fromString("eeeeeeee-0000-4000-8000-000000000002");

    FakeSearch search = new FakeSearch();
    search.answer(QUESTION, List.of(new SegmentSearchPort.Scored(fromFullText, 0.9)));

    // 反证用：同一个 FakeSearch，但不给实体那一路。
    List<UUID> withoutRecall =
        serviceWithRecall(search, RerankPort.noop(), (kbId, question) -> List.of())
            .hitTest(query())
            .items()
            .stream()
            .map(h -> h.segmentId())
            .toList();
    assertThat(withoutRecall).doesNotContain(fromEntity);

    List<UUID> withRecall =
        serviceWithRecall(search, RerankPort.noop(), (kbId, question) -> List.of(fromEntity))
            .hitTest(query())
            .items()
            .stream()
            .map(h -> h.segmentId())
            .toList();
    assertThat(withRecall).as("实体扩召回带进来的段必须出现在结果里").contains(fromEntity);
  }
}
