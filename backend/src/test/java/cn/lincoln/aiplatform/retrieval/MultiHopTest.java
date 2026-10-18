package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.application.HitTestQuery;
import cn.lincoln.aiplatform.retrieval.application.HitTestView;
import cn.lincoln.aiplatform.retrieval.application.MultiHopService;
import cn.lincoln.aiplatform.retrieval.application.QueryUnderstandingService;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import cn.lincoln.aiplatform.retrieval.domain.HopDecision;
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
 * 多跳的停止条件与触发规则（第 14 周 · 0.4.2，{@code 10 §3.4}）。
 *
 * <h3>为什么这些必须有读数</h3>
 *
 * <p>{@code ROADMAP} 第 14 周的判据是"四个停止条件（含 Jaccard）"与"埋点看到轮次与停止原因"，
 * 而这一层的失败模式恰好是**不报错**的那一类：多跳要么多花一次模型调用却没带来新东西，要么 该停时不停（把首字延迟烧掉）。所以每条停止路径都要有一条断言，且断言的是 {@code trace}
 * 里的 <strong>回合数与停止原因</strong>（可数、可进 analytics），不是日志文本。
 *
 * <p>★ 全部用<strong>手写假实现</strong>（本仓的测试风格：不引 Mockito）：假检索按"查询串"返回预置排名， 于是"第 1 轮"与"第 2
 * 轮"可以被完全控制。真栈那一路（真 PG + 真桩）另由闸门覆盖。
 */
class MultiHopTest {

  private static final UUID KB = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String QUESTION = "X 公司的违约金上限是多少";
  private static final String NEXT = "X 公司 合同编号 违约金 条款";

  private static final UUID S1 = seg(1);
  private static final UUID S2 = seg(2);
  private static final UUID S3 = seg(3);
  private static final UUID S4 = seg(4);

  private final FakeSearch search = new FakeSearch();
  private final FakeMultiHop hop = new FakeMultiHop();

  /** 1. 首轮已经够 ⇒ 不触发（多跳连一次模型调用都不该发生）。 */
  @Test
  void doesNotTriggerWhenFirstRoundIsEnough() {
    search.answer(QUESTION, hits(S1, S2, S3), 0.9);
    hop.decision = new HopDecision(true, null, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopTriggered()).isFalse();
    assertThat(view.trace().hopRounds()).isEqualTo(1);
    assertThat(view.trace().hopStopReason()).startsWith("NOT_TRIGGERED");
    assertThat(hop.calls).as("没触发就不该调模型判定了").isZero();
  }

  /** 2. 触发是**规则**判定：命中数 < 3 就触发（即使分数很高）。 */
  @Test
  void triggersWhenTooFewHitsEvenIfScoresAreHigh() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    hop.decision = new HopDecision(true, null, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopTriggered()).isTrue();
    assertThat(hop.calls).as("触发了就一定会问一次判定").isEqualTo(1);
  }

  /** 2b. 触发条件的另一半：命中够多但**最高分低于阈值**也触发。 */
  @Test
  void triggersWhenBestScoreIsBelowThreshold() {
    search.answer(QUESTION, hits(S1, S2, S3, S4), 0.30);
    hop.decision = new HopDecision(true, null, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopTriggered()).isTrue();
  }

  /** 3. 停止条件 1：{@code sufficient=true} ⇒ 停（最省的路径，第 2 轮不该发起）。 */
  @Test
  void stopsWhenModelSaysTheHitsAreSufficient() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    hop.decision = new HopDecision(true, null, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("SUFFICIENT");
    assertThat(view.trace().hopRounds()).isEqualTo(1);
    assertThat(search.queries).as("没有发起第 2 轮检索").containsExactly(QUESTION);
  }

  /** 4. 模型认为不够、但给不出下一轮查询 ⇒ 停（不硬编，{@code §3.4.3}）。 */
  @Test
  void stopsWhenModelGivesNoNextQuery() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    hop.decision = new HopDecision(false, "   ", null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("NO_QUERY");
    assertThat(search.queries).containsExactly(QUESTION);
  }

  /** 5. 停止条件 3（{@code §3.4.6}）：下一轮 0 命中 ⇒ 停止并用第 1 轮结果。 */
  @Test
  void stopsWhenNextRoundHitsNothing() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    search.answer(NEXT, List.of(), 0.0);
    hop.decision = new HopDecision(false, NEXT, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("NO_NEW_HITS");
    assertThat(view.items()).as("★ 第 1 轮的结果照常返回（绝不因为多跳没改善而失败）").hasSize(2);
  }

  /**
   * ★★ 停止条件 4（金丝雀）：下一轮与已有结果高度重合 ⇒ 停（防原地打转）。
   *
   * <p>读数用 {@code |交|=2、|并|=3 ⇒ 0.667 > 0.6}。把 {@code Jaccard.repeats} 短路掉，这条立刻变红 ——
   * 而症状（"多跳在反复问同一件事、每轮都烧一次模型调用"）在别的断言里**看不出来**。
   */
  @Test
  void stopsWhenNextRoundRepeatsTheSameSegments() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    search.answer(NEXT, hits(S1, S2, S3), 0.9);
    hop.decision = new HopDecision(false, NEXT, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("REPEATED");
    assertThat(view.trace().hopRounds()).isEqualTo(2);
  }

  /**
   * 停止条件 2：本轮有**新东西**、且还有轮数余额 ⇒ 继续；用光 {@code maxHops} 才停。
   *
   * <p>★ 同时钉住 {@code §7.1} 的埋点"第 2 轮是否带来新分段"：{@code newSegments=2}（S3/S4 是新的）。
   */
  @Test
  void stopsAtMaxHopsWhenRoundsBringFreshSegments() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    search.answer(NEXT, hits(S3, S4), 0.9);
    hop.decision = new HopDecision(false, NEXT, null);

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("MAX_HOPS");
    assertThat(view.trace().hopRounds()).isEqualTo(2);
    assertThat(view.trace().hopNewSegments()).as("第 2 轮带来 2 条新分段").isEqualTo(2);
    assertThat(search.queries).containsExactly(QUESTION, NEXT);
  }

  /** {@code maxHops=1} 等价于"只跑首轮"：触发也不会真的再检（配置成 1 是个合法的"关掉迭代"）。 */
  @Test
  void maxHopsOneMeansFirstRoundOnly() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    hop.decision = new HopDecision(false, NEXT, null);

    HitTestView view = run(true, 1);

    assertThat(view.trace().hopRounds()).isEqualTo(1);
    assertThat(search.queries).containsExactly(QUESTION);
  }

  /**
   * ★★ 判定不可用 ⇒ 停止迭代、用已有结果继续，**并记 D1**（{@code §3.4.6} + {@code §6.3}）。
   *
   * <p>这一条是本层的底线承诺："多跳这一层坏掉，绝不能让一次问答失败"。
   */
  @Test
  void degradesInsteadOfFailingWhenJudgeIsUnavailable() {
    search.answer(QUESTION, hits(S1, S2), 0.9);
    hop.unavailable = true;

    HitTestView view = run(true, 2);

    assertThat(view.trace().hopStopReason()).startsWith("JUDGE_FAILED");
    assertThat(view.trace().degradeLevel()).as("§6.3：判定失败 ⇒ D1（去掉多跳）").isEqualTo(1);
    assertThat(view.trace().degradeReason()).contains("多跳判定失败");
    assertThat(view.items()).as("★ 一次问答都不许失败").isNotEmpty();
  }

  /** 关掉多跳（默认）⇒ 埋点写清"没开"，而不是留空（留空会与"开了但没触发"混起来）。 */
  @Test
  void disabledMultiHopIsRecordedAsConfiguration() {
    search.answer(QUESTION, hits(S1, S2), 0.9);

    HitTestView view = run(false, 2);

    assertThat(view.trace().hopTriggered()).isFalse();
    assertThat(view.trace().hopStopReason()).startsWith("NOT_ENABLED");
    assertThat(hop.calls).isZero();
    assertThat(view.trace().degradeLevel()).as("" + "★ 关掉它是配置，不是降级 —— 不许记进 degradeRate").isZero();
  }

  /** 多跳轮数越界 ⇒ 400（越界不许"收下但按别的值跑"，与其它参数同口径）。 */
  @Test
  void rejectsMaxHopsOutOfRange() {
    search.answer(QUESTION, hits(S1, S2), 0.9);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(true, 9))
        .isInstanceOf(cn.lincoln.aiplatform.platform.domain.BusinessException.class)
        .hasMessageContaining("多跳轮数应在 1～3 之间");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private HitTestView run(boolean multiHop, int maxHops) {
    RetrievalService service =
        new RetrievalService(
            search,
            new FakeEmbedding(),
            new FakeUnderstanding(),
            hop,
            new RetrievalBudgetProperties(5000, 5000, 0.6, true, true));
    HitTestQuery request =
        new HitTestQuery(
            RetrievalMode.FULLTEXT.name(),
            0.6,
            5,
            null,
            List.of(KB),
            QUESTION,
            1,
            false,
            3,
            multiHop,
            maxHops);
    return service.hitTest(request);
  }

  private static List<SegmentSearchPort.Scored> hits(UUID... ids) {
    List<SegmentSearchPort.Scored> scored = new ArrayList<>();
    for (UUID id : ids) {
      scored.add(new SegmentSearchPort.Scored(id, 0.9));
    }
    return scored;
  }

  private static UUID seg(int value) {
    return UUID.fromString(String.format("10000000-0000-4000-8000-%012d", value));
  }

  /** 假检索：按**查询串**返回预置排名 —— 于是"首轮"与"下一轮"可以被完全控制。 */
  private static final class FakeSearch implements SegmentSearchPort {

    private final Map<String, List<Scored>> byQuery = new LinkedHashMap<>();
    private final Map<String, Double> bestScore = new LinkedHashMap<>();
    final List<String> queries = new ArrayList<>();

    void answer(String query, List<Scored> scored, double best) {
      byQuery.put(query, scored);
      bestScore.put(query, best);
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB);
    }

    /**
     * ★ 第 17 周（{@code #31}）新增的域解析 —— 本类是纯假的检索侧，只带共用库域即可。
     *
     * <p>★ 为什么必须**显式实现**而不是靠默认：端口的默认实现是"明确拒绝" （{@code UnsupportedOperationException}）——
     * 一个没实现它的端口用在生产路径上会立刻红， 而不是静默退化成"只按共用库检索"（那会让会话域/个人域的内容**少看见**且不报错）。
     */
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
      List<Scored> scored = byQuery.getOrDefault(query.question(), List.of());
      double best = bestScore.getOrDefault(query.question(), 0.0);
      List<Scored> adjusted = new ArrayList<>();
      for (Scored each : scored) {
        adjusted.add(new Scored(each.segmentId(), best));
      }
      return adjusted;
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
                "正文内容，用于判定的前 100 字。",
                "文档",
                "库",
                UUID.randomUUID(),
                UUID.randomUUID(),
                ClassificationLevel.INTERNAL));
      }
      return cards;
    }
  }

  /** 假查询理解：直接给"原始问题单路"，让首轮完全可预测。 */
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

  /** 假多跳判定：由测试直接指定"模型说了什么"。 */
  private static final class FakeMultiHop extends MultiHopService {

    HopDecision decision = new HopDecision(true, null, null);
    boolean unavailable;
    int calls;

    FakeMultiHop() {
      super(new FakeChat());
    }

    @Override
    public Outcome decide(String question, List<SegmentSearchPort.Card> hits, long timeoutMs) {
      calls++;
      if (unavailable) {
        return new Outcome(HopDecision.failure("模型输出解析失败"), 1);
      }
      return new Outcome(decision, 1);
    }
  }

  /** 两个假服务都需要一个 ChatPort 参数；本测试里永远不会用到它。 */
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
