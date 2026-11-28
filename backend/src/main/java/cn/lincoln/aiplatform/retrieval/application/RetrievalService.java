package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.EntityRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.ListwiseRerankPort;
import cn.lincoln.aiplatform.platform.retrieval.QaRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.RelationRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettings;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettingsStore;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.Rrf;
import cn.lincoln.aiplatform.platform.retrieval.RrfQuotaJoiner;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.domain.HopDecision;
import cn.lincoln.aiplatform.retrieval.domain.HopStop;
import cn.lincoln.aiplatform.retrieval.domain.Jaccard;
import cn.lincoln.aiplatform.retrieval.domain.QueryUnderstanding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 命中测试（三模式 + RRF + 查询理解）—— 第 10 周的判据 ④ 与第 12 周的判据 ①②③ 都落在这个类里。
 *
 * <h3>链路（每一步的顺序都是有理由的，别调）</h3>
 *
 * <pre>
 *  校验 → 可见范围 → **查询理解（M 子问题 × N 查询）** → 每条查询检索一次（模式内已融合）
 *       → 子问题内 RRF → 子问题按顺序拼接 → ★两路同谓词 → 阈值/截断 → 组装 + 轨迹
 * </pre>
 *
 * <ul>
 *   <li><strong>阈值在 TopN 之前</strong>（{@code 03 §2.3}）：反了会"取了 10 条但都不达标"。
 *   <li><strong>候选要过采样</strong>（{@link #OVERFETCH}）：过滤会砍掉一些，按 1×TopN 取的话 "过滤掉 3 条"就等于"少 3 条结果"。
 *   <li><strong>可见性判定放在融合之后、截断之前</strong>：先判会把 RRF 的名次算错 （被过滤掉的候选占的名次会消失，剩下的名次整体前移、分虚高）。★ 而"两路同谓词"
 *       这条纪律（{@code 13}）要求向量路拿回来的候选也过同一套判定 —— 所以它就落在这里。
 * </ul>
 *
 * <h3>★ 第 12 周加的第五步：M 个子问题 × N 条查询</h3>
 *
 * <p>{@code 10 §3.2.1} 那条"路数怎么算"很容易想错：<strong>不是</strong> {@code 查询数 × 模式数} —— 混合模式内部本身已经是"向量 +
 * 全文两路 RRF"，再乘模式数会得到 {@code 3 × 3 = 9} 路且语义重复。 正确的形态是：<strong>每条查询先各自融合成一路</strong>，多条查询之间再做一次 RRF
 * （RRF 天然可递推，不新增算法）。
 *
 * <p>★ <strong>子问题之间不做全局 RRF</strong>（{@code 10 §3.3.3} 明确禁止）： 子问题本来就不该互相竞争排名，把 M×N 路丢进一个大 RRF
 * 会让"问了两件事、只答了一件"。 本周按<strong>子问题顺序拼接</strong>（顺序 = 子问题顺序 → 子问题内排名）， 而"每个子问题保底占几条"的配额合并属于第 13
 * 周（0.4.1 的 {@code RrfQuotaJoiner}）。
 *
 * <h3>★ 阈值的语义（一条刻意的边界）</h3>
 *
 * <p>阈值是<strong>相似度</strong>下限（{@code 10 §5.4}：它要随向量模型校准）⇒ 只作用于向量路。 全文路的 {@code ts_rank} 是无界的，拿 0–1
 * 的阈值去卡它，全文模式会永远为空 —— 而它恰恰是专有名词/编号类问题的唯一出路（{@code 03 §2.1}）。每条路有自己的准入判据， 融合只看名次（RRF 的全部意义）。
 *
 * <h3>★ 顺序执行而不是并行（与 {@code 10 §3.2.2} 的一处偏离，如实记）</h3>
 *
 * <p>设计册写的是"N 条查询并行（虚拟线程 + CompletableFuture）"。本周<strong>顺序</strong>跑，理由： 整个 {@code hitTest}
 * 在一个<strong>只读事务</strong>里，共用一条 JDBC 连接 —— 而连接不是线程安全的。 要并行就得给每条查询各自的事务与连接，那是另一件事（也是它在真栈上唯一能验证的方式）。
 * 代价由两个上限兜住：总路数 ≤ {@value #MAX_PATHS}、以及检索阶段的<strong>时间预算</strong> （{@code 10 §6.2} 第 6
 * 项：预算耗尽就按已完成的查询继续，绝不无限等）。并行化登记为后续。
 *
 * <h3>★★ 一条被真栈闸门逼出来的兜底：改写不许把结果改没</h3>
 *
 * <p>落地的当天，既有那条"全站页面走查"闸门**红了**：命中测试页在 FULLTEXT / HYBRID 下**一条都不出**。 查下来不是闸门的问题 ——
 * 是这一层的真实失败模式：<strong>查询理解产出的查询一条都没命中，而用户 原本那个问题明明能召回</strong>（那次是桩模型按 schema
 * 造了占位查询；换成真模型时，"改写把编号改掉" 或"扩展成了语义旁支"是同样形状的问题）。设计册 §1.3 的判据 3 是"任何一层失败，降级到下一档继续答"，
 * 而"这层把结果从有变成没有"正是该降级的情形。
 *
 * <p>⇒ 现在：多查询一条都没命中时，<strong>退回原始问题再跑一次</strong>。 ★ 两个刻意的限制：只在 {@code N > 1}
 * 时兜（单查询就是原始问题，没有"退回"可言）； 以及**只有它真的救回了结果才记成 D4** —— 语料里本来就没有时退回同样为空， 那不算"这层降级了"（否则每问一个语料里没有的问题都会往
 * {@code degradeRate} 上记一笔）。
 */
@Service
public class RetrievalService {

  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

  /** 候选过采样倍数（见类注释）。上限 100：命中测试是人点的，不是批处理。 */
  private static final int OVERFETCH = 3;

  private static final int CANDIDATE_LIMIT_CAP = 100;

  /**
   * 总检索路数上限（{@code 10 §6.2} 第 4 项：默认 6）。
   *
   * <p>截断规则也是那一条定的：<strong>按子问题顺序截断，每个子问题至少保住 1 条查询</strong> ——
   * 不然"路数超了"会把后面的子问题整段砍掉，而那正是"问了两件事只答一件"的成因。
   */
  private static final int MAX_PATHS = 6;

  /**
   * 检索阶段的时间预算（{@code 10 §6.2} 第 6 项：默认 5000ms）。
   *
   * <p>★ 设计册说这是"唯一能兜住所有层的上限"：调用次数可以预估，单次调用多久预估不了。
   * 所以每一层在开始前检查余量，耗尽就<strong>用已经拿到的结果继续</strong>（不是失败）。
   */
  // ★ 原 `RETRIEVAL_BUDGET_MS = 5000` 常量已改为配置 `app.retrieval.total-budget-ms`
  //   （低配 CPU 上一次查询理解就要 4–5s，5s 总预算在第 0 路就耗尽）。见 RetrievalBudgetProperties。

  /**
   * 多跳的触发下限（{@code 10 §3.4.2}：命中数 &lt; 3 就认为"首轮不够"）。
   *
   * <p>★ 触发是<strong>规则判定</strong>，不额外调模型 —— 多跳已经要多花一次判定调用， 再让模型来判断"要不要多跳"就是拿两次调用换一次机会。
   */
  private static final int HOP_MIN_HITS = 3;

  /** 回包里最多带几条问答对明细。 */
  private static final int QA_DETAIL_LIMIT = 5;

  /**
   * 时间预算的"该降级了"水位（{@code 10 §6.3}：D1 = 时间预算用掉 <strong>60%</strong> / 多跳判定失败）。
   *
   * <p>★ 为什么是"用掉 60% 就不再开新一轮"而不是"等花光"：多跳一轮要**再一次模型调用 + 再一次检索**， 等花光再停等于必然超预算 —— 那时首字延迟已经被吃掉，而 B4
   * 是硬指标。
   */
  // ★ 原 `HOP_BUDGET_RATIO = 0.6` 已改为配置 `app.retrieval.hop-budget-ratio`。

  private final SegmentSearchPort segmentSearch;

  private final EmbeddingPort embeddingPort;
  private final QueryUnderstandingService queryUnderstanding;

  /**
   * 预算与低配开关（`app.retrieval.*`）。
   *
   * <p>★ 这三个数原来是写死的常量（总预算 5000ms / 多跳水位 0.6）：在 CPU + 小模型上 一次查询理解就要 4–5s ⇒ 总预算在**第 0
   * 路**就耗尽（实测日志：`检索预算 5000ms 已耗尽（已跑 0 路）`）。
   */
  private final RetrievalBudgetProperties budget;

  /**
   * 重排端口（cross-encoder）：只依赖 {@code platform} 里的接口，不依赖实现类（{@code #293} 模块边界）。
   *
   * <p>★ 位置是刻意的：重排在"配额合并 + 可见性过滤 + 截断"**之后**、组装视图之前 —— 它改的是"谁排最前面"（送进答案的那几条）， 不参与召回与配额（那些各有自己的判据）。
   */
  private final RerankPort rerankPort;

  /** 重排配置（{@code app.retrieval.rerank.*}）：{@code enabled} 在调用点判 —— 关掉就一次都不调。 */
  private final RerankProperties rerankProperties;

  /**
   * 可改的运行参数（配置页写的那几格，{@code platform/V100}）。
   *
   * <p>★ 为 {@code null} 表示"这次调用来自那些直接 {@code new RetrievalService(...)} 的旧测试" —— 它们的断言建立在 yml
   * 默认值上，不该被一个数据库连接拖进来。生产路径（{@code @Autowired} 的构造器）一定非 null。
   *
   * <p>★ 只在真正要用的那一刻读（{@link #effective()}），不在构造期缓存：配置页改完要立刻生效， 缓存一份就意味着"改完还要重启"—— 那正是这批改动要消灭的东西。
   */
  private final RetrievalEngineSettingsStore engineSettings;

  /** LLM listwise 重排端口（{@code #151}）：接在 cross-encoder 之后，默认关（见 {@link RerankProperties}）。 */
  private final ListwiseRerankPort listwiseRerankPort;

  /**
   * 实体扩召回的读端口（0.6.2）。问题里提到的机构/文档出现过的段，作为**一路候选**参与 RRF。
   *
   * <p>★ 它是"多一路"而不是"新算法"：检索本来就是多条查询各自检索再融合， 实体命中与"另一条查询"在结构上同类。
   */
  private final EntityRecallPort entityRecallPort;

  private final RelationRecallPort relationRecallPort;

  /**
   * 第五路：问答对。{@code null} = 这一路没接（日志里也不报错）。
   *
   * <p>★ 为什么它走 setter 而不是构造器参数：这个类的两个构造器已经被四处测试直接 {@code new} 出来， 加一个参数就得改四处，而且改错一次会让「7
   * 参便捷版」与「生产版」的长度同时对不上。 一个可选依赖不该拥有改所有调用点的权力。
   */
  private QaRecallPort qaRecallPort;

  /** 问答对召回端口的可选注入：容器里没有这个 bean 时这一路自动跳过。 */
  @Autowired(required = false)
  void setQaRecallPort(QaRecallPort qaRecallPort) {
    this.qaRecallPort = qaRecallPort;
  }

  private final MultiHopService multiHop;

  /**
   * 不带 listwise 的旧签名（{@code #312} 时期，7 参）。
   *
   * <p>★ 为什么要留：那一批测试直接 {@code new RetrievalService(...)} 造服务，它们不关心 listwise ⇒ 缺省接 {@link
   * ListwiseRerankPort#noop()}（永远原序）——与"listwise 关"完全等价， 所以旧测试的断言一字不用改。
   *
   * @param segmentSearch 分段检索端口
   * @param embeddingPort 向量端口
   * @param queryUnderstanding 查询理解
   * @param multiHop 多跳
   * @param budget 预算配置
   * @param rerankPort cross-encoder 重排端口
   * @param rerankProperties 重排配置
   */
  public RetrievalService(
      SegmentSearchPort segmentSearch,
      EmbeddingPort embeddingPort,
      QueryUnderstandingService queryUnderstanding,
      MultiHopService multiHop,
      RetrievalBudgetProperties budget,
      RerankPort rerankPort,
      RerankProperties rerankProperties) {
    this(
        segmentSearch,
        embeddingPort,
        queryUnderstanding,
        multiHop,
        budget,
        rerankPort,
        rerankProperties,
        ListwiseRerankPort.noop(),
        // ★ 7 参这条路只服务既有测试（它们不关心实体扩召回）。
        (kbId, question) -> java.util.List.of(),
        // ★ 同理，关系扩召回也走空实现。
        (kbId, question) -> java.util.List.of(),
        // ★ 也不接配置表：旧测试的断言建立在 yml 默认值上，不该被数据库连接拖进来。
        null);
  }

  /**
   * 生产用的构造器（8 参）。
   *
   * <p>★★ {@code @Autowired} 是必需的，不是装饰：这个类有<b>两个</b>构造器（这个 8 参与下面那个 7 参便捷版）， Spring
   * 在候选不唯一时无法决定用哪个，报出来的却是 {@code Failed to instantiate … : No default constructor found} ——
   * 文案与真因（构造器不唯一）毫无关系。2026-11-08 加 listwise 端口时就是这么撞了一次。
   *
   * @param segmentSearch 检索端口
   * @param embeddingPort 向量化端口
   * @param queryUnderstanding 查询理解
   * @param multiHop 多跳
   * @param budget 预算
   * @param rerankPort cross-encoder 重排
   * @param rerankProperties 重排配置
   * @param listwiseRerankPort LLM listwise 重排
   */
  @Autowired
  public RetrievalService(
      SegmentSearchPort segmentSearch,
      EmbeddingPort embeddingPort,
      QueryUnderstandingService queryUnderstanding,
      MultiHopService multiHop,
      RetrievalBudgetProperties budget,
      RerankPort rerankPort,
      RerankProperties rerankProperties,
      ListwiseRerankPort listwiseRerankPort,
      EntityRecallPort entityRecallPort,
      RelationRecallPort relationRecallPort,
      RetrievalEngineSettingsStore engineSettings) {
    this.segmentSearch = segmentSearch;
    this.embeddingPort = embeddingPort;
    this.queryUnderstanding = queryUnderstanding;
    this.multiHop = multiHop;
    this.budget = budget;
    this.rerankPort = rerankPort;
    this.rerankProperties = rerankProperties;
    this.listwiseRerankPort = listwiseRerankPort;
    this.entityRecallPort = entityRecallPort;
    this.relationRecallPort = relationRecallPort;
    this.engineSettings = engineSettings;
  }

  /**
   * 当前生效的运行参数。
   *
   * <p>★ 只有在"这一次检索要用"时才读，读到的是内存快照（零查询）。配置页保存后快照立刻替换 ⇒ 下一次请求就按新值跑。
   *
   * <p>★ {@code engineSettings == null} 时回落到 {@link RerankProperties}：那条路只有旧测试在用， 它们断言的是 yml 默认值。
   */
  private RetrievalEngineSettings effective() {
    if (engineSettings != null) {
      return engineSettings.current();
    }
    return new RetrievalEngineSettings(
        true,
        true,
        rerankProperties.enabled(),
        rerankProperties.topK(),
        rerankProperties.forwardBatch(),
        rerankProperties.listwiseEnabled(),
        rerankProperties.listwiseTopK(),
        null);
  }

  @Transactional(readOnly = true)
  public Outcome retrieve(RetrievalRequest request) {
    long startedAt = System.nanoTime();
    RetrievalMode mode = request.modeOrDefault();

    // ① 可见范围：看不见的库既不进检索、也不给"存在性"信号（07 §3.3 硬规矩 1）
    //   ★ 第 17 周起这一步解析的是**域**（13 §11.3 的三支绑定值），不再只是"一组库 id"：
    //     命中测试没有会话（conversationId = null），且个人库按 13 §11.4 的默认**不参与**
    //     （「加上我的资料」开关是契约上的可选字段，随问答链路那一批接）。
    // ★ 调用方可以带着**已解析的域**进来（对话侧要传含会话附件的那一支）；不传才在这里解析。
    //   为什么不能一律在这里解析：会话域的三个绑定值来自三个地方，其中 ownerId 只能从当前范围取 ——
    //   而「用谁的身份检索会话附件」是 ChatService 已经回答过的问题，重答一遍就可能答得不一样。
    RetrievalScope scope =
        request.scope() != null
            ? request.scope()
            : segmentSearch.retrievalScope(
                request.kbIds(), request.conversationId(), request.includePersonal());
    if (scope.isEmpty()) {
      // ★ 这里是"没有可检索的范围"，不是"没命中" —— 但对外都返空列表：
      //   区别只该出现在日志/埋点里，不该变成"这个库存在但你看不见"这种信息泄露。
      return new Outcome(
          List.of(),
          mode,
          elapsedMs(startedAt),
          new HitTestView.Trace(
              1, 1, 0, 0, "没有可检索的知识库", false, 0, false, 1, HopStop.NOT_TRIGGERED.describe(), 0),
          new HitTestView.Detected(List.of(), List.of(), "没有可检索的知识库，两路扩召回都没跑"),
          null);
    }

    // ② 查询理解
    //
    // ★★ 关闭时**不发调用、直接单路**，且 degradeLevel 保持 0：
    //    「按配置关掉」与「模型不可用而失败」是两件事 —— 若都把 degradeLevel 写成 4，
    //    看 trace 的人无法区分「我关了它」和「它坏了」。
    QueryUnderstandingService.Outcome understood;
    if (!budget.queryUnderstandingEnabled()) {
      log.info("查询理解已按配置关闭（app.retrieval.query-understanding-enabled=false）⇒ 原始问题单路");
      understood =
          new QueryUnderstandingService.Outcome(
              QueryUnderstanding.single(request.question()),
              0,
              null,
              false,
              false,
              java.util.List.of("查询理解已按配置关闭"),
              0L);
    } else {
      // ★ 它自带兜底（不可用 ⇒ 原始问题单路），所以这里不需要 try/catch
      understood =
          queryUnderstanding.understand(
              request.question(),
              request.queryCountOrDefault(),
              request.decomposeEnabledOrDefault(),
              request.maxSubQuestionsOrDefault(),
              budget.queryUnderstandingBudgetMs());
    }

    // ★★ #230：检索预算的时钟从这里开始 —— 它量的是"检索阶段净耗时"，不含查询理解。
    //   此前这里沿用方法开头那个 startedAt（含查询理解）⇒ 查询理解只要不是瞬间返回，
    //   "总预算"就在第 0 路被判耗尽（日志：检索预算 5000ms 已耗尽（已跑 0 路）），
    //   整条链路静默降级成单路，B1 读数在两轮之间摆动 30 个百分点。
    long retrievalStartedAt = System.nanoTime();

    int candidateLimit = Math.min(request.topNOrDefault() * OVERFETCH, CANDIDATE_LIMIT_CAP);
    Map<UUID, Double> vectorScores = new HashMap<>();
    Map<UUID, Double> textScores = new HashMap<>();

    // ③ 每个子问题：它的 N 条查询各检索一次 → 子问题内 RRF
    List<List<UUID>> perSubQuestion = new ArrayList<>();
    int paths = 0;
    int pathsAttempted = 0;
    boolean budgetExhausted = false;
    boolean pathFailed = false;
    int degradeLevel = understood.degradeLevel();
    String degradeReason = understood.degradeReason();

    // ★ 两路扩召回的明细与"跑了但空"的证据（命中测试的诊断块）。
    //   与 perQuery 并行维护：perQuery 只装送去融合的段 id —— 明细进不了融合，也不该进。
    //   ★ 失败原因单独记：现在的实现只写日志，界面上「这一路失败」与「这一路没有命中」
    //     长得一模一样，而那正是本项目反复记账的静默失效形状。
    List<EntityRecallPort.Entity> detectedEntities = new ArrayList<>();
    List<RelationRecallPort.Relation> detectedRelations = new ArrayList<>();
    Set<UUID> entityHits = new java.util.LinkedHashSet<>();
    Set<UUID> relationHits = new java.util.LinkedHashSet<>();
    List<QaRecallPort.Match> qaMatches = new ArrayList<>();
    Set<UUID> qaHits = new java.util.LinkedHashSet<>();
    String qaFailure = null;
    String entityFailure = null;
    String relationFailure = null;

    List<List<String>> plan = planPaths(understood.understanding());
    for (List<String> queries : plan) {
      List<List<UUID>> perQuery = new ArrayList<>();
      for (String query : queries) {
        // ★ 每一路开始前检查余量（10 §6.2 第 6 项）—— 耗尽就用已拿到的结果继续，不是失败
        if (elapsedMs(retrievalStartedAt) > budget.totalBudgetMs()) {
          budgetExhausted = true;
          log.warn(
              "检索预算 {}ms 已耗尽（已跑 {} 路），按已完成的查询继续。"
                  + "★ 若这是低配环境（CPU / 小模型），把 app.retrieval.total-budget-ms 调大，"
                  + "或关掉查询理解（app.retrieval.query-understanding-enabled=false）",
              budget.totalBudgetMs(),
              paths);
          break;
        }
        pathsAttempted++;
        try {
          perQuery.add(
              retrieveOneQuery(
                  query,
                  mode,
                  scope,
                  candidateLimit,
                  request.thresholdOrDefault(),
                  vectorScores,
                  textScores));
          paths++;
        } catch (BusinessException failure) {
          // ★ 部分成功是常态（{@code 10 §3.2.3}）：一条路坏掉不该让整次检索失败 ——
          //   否则多查询会变成一个"降低可用性"的特性，与初衷相反
          pathFailed = true;
          log.warn("查询「{}」检索失败，丢弃这一路：{}", query, failure.getMessage());
        }
      }
      // ★ 实体扩召回（0.6.2）：问题里提到的机构出现过的段，作为**一路候选**参与子问题内 RRF。
      //   它是「多一路」不是新算法 —— 与另一条查询在结构上同类，所以进 perQuery。
      if (!scope.isEmpty() && !scope.hasConversationScope()) {
        try {
          java.util.LinkedHashSet<UUID> byEntity = new java.util.LinkedHashSet<>();
          for (UUID kbId : scope.sharedKbIds()) {
            // ★ 用 recall 而不是 segmentsMentionedIn：明细与召回必须是同一次产出，
            //   否则界面显示"认出了 3 个"、实际只用了 2 个。
            EntityRecallPort.Recall one = entityRecallPort.recall(kbId, request.question());
            byEntity.addAll(one.segments());
            detectedEntities.addAll(one.entities());
          }
          entityHits.addAll(byEntity);
          if (!byEntity.isEmpty()) {
            perQuery.add(new java.util.ArrayList<>(byEntity));
            paths++;
          }
        } catch (RuntimeException failure) {
          // ★ 扩召回失败不拖累整次检索：它是「多一路」，不是必经之路。
          //   但必须留痕 —— 静默失效正是本项目反复记账的形状。
          //   自 0.9.9 起这条痕同时进回包（命中测试要能把它念出来）。
          entityFailure = failure.toString();
          log.warn("实体扩召回失败，本次少一路候选：{}", failure.toString());
        }
      }
      // ★ 关系扩召回（0.6.7）：问题里的实体**参与过关系陈述**的那几段，作为又一路候选。
      //   与实体扩召回不是同一件事：那个要的是"提到过这个实体"，这个要的是"这里说了它和谁有关系"。
      //   后者精确得多（一段里有关系陈述，几乎必然与"X 与谁有什么关系"这类问题相关），
      //   但覆盖面窄（只有抽到过关系的段才在里面）—— 两路都留着，让 RRF 去排。
      if (!scope.isEmpty() && !scope.hasConversationScope()) {
        try {
          java.util.LinkedHashSet<UUID> byRelation = new java.util.LinkedHashSet<>();
          for (UUID kbId : scope.sharedKbIds()) {
            RelationRecallPort.Recall one = relationRecallPort.recall(kbId, request.question());
            byRelation.addAll(one.segments());
            detectedRelations.addAll(one.relations());
          }
          relationHits.addAll(byRelation);
          if (!byRelation.isEmpty()) {
            perQuery.add(new java.util.ArrayList<>(byRelation));
            paths++;
          }
        } catch (RuntimeException failure) {
          // ★ 与实体扩召回同口径：多一路不是必经之路，失败不拖累整次检索，但必须留痕。
          relationFailure = failure.toString();
          log.warn("关系扩召回失败，本次少一路候选：{}", failure.toString());
        }
      }
      // ★ 问答对扩召回（0.9.9）
      if (!scope.isEmpty() && !scope.hasConversationScope() && qaRecallPort != null) {
        try {
          List<QaRecallPort.Match> matches =
              qaRecallPort.recall(
                  new QaRecallPort.Query(
                      request.question(),
                      scope.sharedKbIds(),
                      request.thresholdOrDefault(),
                      QA_DETAIL_LIMIT));
          java.util.LinkedHashSet<UUID> byQa = new java.util.LinkedHashSet<>();
          for (QaRecallPort.Match match : matches) {
            qaMatches.add(match);
            if (match.segmentId() != null) {
              byQa.add(match.segmentId());
            }
          }
          qaHits.addAll(byQa);
          if (!byQa.isEmpty()) {
            perQuery.add(new java.util.ArrayList<>(byQa));
            paths++;
          }
        } catch (RuntimeException failure) {
          qaFailure = failure.toString();
          log.warn("问答对扩召回失败，本次少一路候选：{}", failure.toString());
        }
      }
      if (perQuery.isEmpty()) {
        continue;
      }
      perSubQuestion.add(
          perQuery.size() == 1
              ? perQuery.getFirst()
              : Rrf.fuse(perQuery).stream().map(Rrf.Fused::segmentId).toList());
    }

    if (perSubQuestion.isEmpty()) {
      // ④ 全部路都失败 ⇒ 降级：用原始问题再跑一次单路（{@code 10 §3.2.3} 的最后一行）
      log.warn("查询理解的每条路都失败了，降级为原始问题单路重试");
      List<UUID> retry =
          retrieveOneQuery(
              request.question(),
              mode,
              scope,
              candidateLimit,
              request.thresholdOrDefault(),
              vectorScores,
              textScores);
      perSubQuestion.add(retry);
      paths = 1;
      degradeLevel = 4;
      degradeReason = "所有查询都失败，已退回原始问题单路";
    } else if (budgetExhausted && degradeLevel < 1) {
      degradeLevel = 1;
      degradeReason = join("检索预算耗尽，按已完成的查询继续", degradeReason);
    } else if (pathFailed && degradeLevel == 0) {
      degradeReason = join("有查询失败已丢弃该路", degradeReason);
    }

    // ④b ★★ 多跳（第 14 周 · 0.4.2，10 §3.4）：默认关；触发是规则判定，迭代**全部发生在生成前**
    //   —— 这是 §8.3 那条"SSE 契约一个字都不用改"的前提，不许打破。
    HopRun hop =
        request.multiHopEnabledOrDefault()
            ? runMultiHop(
                request,
                mode,
                scope,
                candidateLimit,
                retrievalStartedAt,
                perSubQuestion,
                vectorScores,
                textScores)
            : new HopRun(false, 1, HopStop.NOT_ENABLED, List.of(), 0, null);
    if (hop.degradeReason() != null && degradeLevel < 1) {
      // §6.3：D1 = 去掉多跳（时间预算用掉 60% / 多跳判定失败）
      degradeLevel = 1;
      degradeReason = join(hop.degradeReason(), degradeReason);
    }
    log.info(
        "多跳：触发={} 轮次={} 停止原因={} 第 2 轮新增分段={}",
        hop.triggered(),
        hop.rounds(),
        hop.stopReason().describe(),
        hop.newSegments());

    // ⑤ ★★ 子问题之间按**保底配额**合并（第 13 周 · 0.4.1，10 §3.3.3）——
    //   不是顺序拼接，也不是全局 RRF：前者会让"前一个子问题占满 TopN"，
    //   后者（本节开头明确否掉）会让 A 把 B 的名额全挤掉。判据是"问两个问题 ⇒ 两个都被回答"。
    List<RrfQuotaJoiner.Unit> units = new ArrayList<>(perSubQuestion.size());
    for (int index = 0; index < perSubQuestion.size(); index++) {
      units.add(new RrfQuotaJoiner.Unit(index, perSubQuestion.get(index)));
    }
    if (!hop.ranking().isEmpty()) {
      // ★ 第 2 轮的排名作为**一个额外单元**进配额合并（10 §3.4.5 的"各轮累积、一并参与合并"）。
      //   放在配额单元里而不是"拼到某一路后面"，是因为保底配额正好保护了它：
      //   多跳找到的那条**不会被第 1 轮的 TopN 挤掉** —— 否则这一次迭代就白跑了。
      units.add(new RrfQuotaJoiner.Unit(units.size(), hop.ranking()));
    }
    RrfQuotaJoiner.Result merged = RrfQuotaJoiner.join(units, request.topNOrDefault());
    if (!merged.quotas().isEmpty()) {
      // §7.1 的合并层埋点：每个子问题分到几条、丢了几条 —— 没有它，"这个子问题为什么答得少"没人答得上
      log.info("子问题配额合并：{}（补足 {} 条）", RrfQuotaJoiner.describe(merged), merged.fillInCount());
    }
    List<UUID> ordered = merged.selected();
    Set<UUID> visible = segmentSearch.visibleAmong(ordered, scope);
    List<UUID> kept = ordered.stream().filter(visible::contains).toList();

    // ⑤b ★★ "改写把结果改没了"要兜住 —— 这一条是**真栈闸门逼出来的**（见类注释末尾）。
    //   ★ 只在"多查询一条都没命中"时兜（N > 1），而且**只把降级记为 D4 当它真的救回了结果**：
    //     语料里本来就没有时，退回原始问题同样为空 —— 那不算"这层降级了"，
    //     否则每问一个语料里没有的问题都会把 degradeRate 记一笔。
    if (kept.isEmpty() && understood.understanding().totalQueries() > 1) {
      List<UUID> fromOriginal =
          retrieveOneQuery(
              request.question(),
              mode,
              scope,
              candidateLimit,
              request.thresholdOrDefault(),
              vectorScores,
              textScores);
      Set<UUID> visibleOriginal = segmentSearch.visibleAmong(fromOriginal, scope);
      List<UUID> fallback = fromOriginal.stream().filter(visibleOriginal::contains).toList();
      if (!fallback.isEmpty()) {
        log.warn("改写/扩展后的 {} 条查询一条都没命中，退回原始问题并召回了 {} 条", paths, fallback.size());
        kept = fallback;
        ordered = fromOriginal;
        degradeLevel = 4;
        degradeReason = join("改写后的查询全部没命中，已退回原始问题单路", degradeReason);
      }
    }
    List<UUID> finalIds = kept.stream().limit(request.topNOrDefault()).toList();

    // ⑥ 组装：分数取"证据最好的那一路"，展示信息一次批量取（避免 N+1）
    Map<UUID, Double> scores = new HashMap<>(textScores);
    vectorScores.forEach((id, score) -> scores.merge(id, score, Math::max));
    Map<UUID, SegmentSearchPort.Card> cards = new LinkedHashMap<>();
    for (SegmentSearchPort.Card card : segmentSearch.load(finalIds)) {
      cards.put(card.segmentId(), card);
    }

    // ⑥b ★★ cross-encoder 重排（只重排前 topK 个，窗口与读数见 RerankPort）：
    //     离线实测 B3 引用准确率 57.9% → 68.2%（+10.3 点），单条 2.4s（p95 3.1s）；
    //     ★ 失败一律降级原序（NoopRerankerAdapter），检索不因重排坏掉而不可用 ——
    //     enabled 在这里判：关掉 = 一次都不调（省 2.4s/条）。
    // ★★ ⑥b 与 ⑥c 是**两个独立开关**（用户 2026-11-08：「listwise 做成配置」）：
    //    重排文本先无条件备好，两个开关各判各的 —— 早先把 ⑥c 嵌在 ⑥b 的 if 里，
    //    结果是"关掉 cross-encoder 就顺带关掉 listwise"，而这两件事的代价与收益完全不同
    //    （前者缺它 B3 掉 13 点，后者只是"锦上添花但慢"）。ListwiseWiringTest 抓到了这个 bug。
    Map<UUID, String> rerankTexts = new HashMap<>();
    for (Map.Entry<UUID, SegmentSearchPort.Card> entry : cards.entrySet()) {
      // ★ 文档名必须进来（`#312` 阶段 0 实验 E2 实测）：B3 判的是"第 1 条命中且**同文档**"，
      //   只喂正文时交叉编码器不知道这段属于哪个文档，只会判"像不像在回答这个问题"。
      //   195 条离线对照：只喂正文 58.0%→67.2%（+9.2），正文前加文档名 58.0%→71.3%（+13.3）
      //   ⇒ 净增 +4.1 点，超过"有效"判据（>+3）。
      String docName = entry.getValue().docName();
      String body = entry.getValue().content();
      rerankTexts.put(
          entry.getKey(), docName == null || docName.isBlank() ? body : docName + "\n" + body);
    }
    // ★ 一次请求只读一次快照：三处判断必须看到同一份值，否则配置页正好在这中间保存，
    //   就会出现"按新窗口重排、按旧 listwise 收尾"这种缝里的状态。
    RetrievalEngineSettings engine = effective();
    if (engine.rerankEnabled() && finalIds.size() > 1) {
      finalIds = rerankPort.rerank(request.question(), finalIds, rerankTexts, engine.rerankTopK());
    }

    // ⑥c ★★ LLM listwise（用户 2026-11-08：不默认开、做成配置）。接在 cross-encoder 之后：
    //     前者负责"从 10 个里挑出最有希望的"，后者负责"在这几个里再做一次语义判断"。
    //     20 条试跑 B3 65.00% → 70.00%（+5.00 点），但单条 3.12s ⇒ 默认关，好机器上开。
    //     ★ 优先级：请求参数（HitTestQuery.rerankListwise）> 全局（listwiseEnabled）。
    //       null 表示"这次请求没指定"，交给全局。
    Boolean listwiseWanted = request.rerankListwise();
    boolean listwiseOn = listwiseWanted != null ? listwiseWanted : engine.listwiseEnabled();
    if (listwiseOn && finalIds.size() > 1) {
      finalIds =
          listwiseRerankPort.rerank(
              request.question(), finalIds, rerankTexts, engine.listwiseTopK());
    }

    // ★★ 直答（#78）：在命中集合里挑出「标成直答、且匹配分最高」的那一条。
    //   为什么要在这里挑、而不是让调用方从 detected().qaMatches() 里挑：那个列表是"明细"（只留前 5 条），
    //   一条最相关的直答完全可能排不进去 —— 那样开关就会时而生效、时而不生效，而且看不出来。
    QaRecallPort.Match directAnswer =
        qaMatches.stream()
            .filter(QaRecallPort.Match::directAnswer)
            .max(java.util.Comparator.comparingDouble(QaRecallPort.Match::score))
            .orElse(null);

    List<Ranked> ranked = new ArrayList<>(finalIds.size());
    for (UUID id : finalIds) {
      SegmentSearchPort.Card card = cards.get(id);
      if (card == null) {
        // 判过可见却取不到展示信息：只可能是并发删除（罕见）。跳过而不是塞一个空壳 ——
        // 空壳条目会让引用编号与实际内容错位。
        continue;
      }
      // ★ 渠道：向量 / 关键词来自两路分数表（HYBRID 模式下一条候选常常两路都有），
      //   实体 / 关系 / 问答对来自扩召回的段集合。顺序固定 —— 那排徽标不该这次的次序与上次不同。
      List<String> channels = new ArrayList<>(5);
      if (vectorScores.containsKey(id)) {
        channels.add("vector");
      }
      if (textScores.containsKey(id)) {
        channels.add("text");
      }
      if (entityHits.contains(id)) {
        channels.add("entity");
      }
      if (relationHits.contains(id)) {
        channels.add("relation");
      }
      if (qaHits.contains(id)) {
        channels.add("qa");
      }
      ranked.add(new Ranked(id, card, scores.getOrDefault(id, 0.0), List.copyOf(channels)));
    }
    return new Outcome(
        ranked,
        mode,
        elapsedMs(startedAt),
        new HitTestView.Trace(
            understood.understanding().subQuestionCount(),
            attemptedPathsOr(pathsAttempted, paths),
            paths,
            degradeLevel,
            degradeReason,
            understood.truncated(),
            understood.elapsedMs(),
            hop.triggered(),
            hop.rounds(),
            hop.stopReason().describe(),
            hop.newSegments()),
        new HitTestView.Detected(
            List.copyOf(detectedEntities),
            List.copyOf(detectedRelations),
            List.copyOf(qaMatches),
            detectNote(
                detectedEntities,
                detectedRelations,
                qaMatches,
                entityFailure,
                relationFailure,
                qaFailure)),
        directAnswer);
  }

  /**
   * 命中测试：校验 + 检索 + 组视图。
   *
   * <p>★ 它现在只剩"把 {@link Outcome} 翻成 {@link HitTestView}"这一件事 —— 检索策略一行都不在这里（{@link #retrieve}）。这是
   * `#378` 的收口点：同一个问句在命中测试与对话里走的是<strong>同一段</strong>代码。
   */
  @Transactional(readOnly = true)
  public HitTestView hitTest(HitTestQuery request) {
    validate(request);
    Outcome outcome = retrieve(RetrievalRequest.of(request));
    List<HitTestView.Item> items = new ArrayList<>(outcome.hits().size());
    int rank = 1;
    for (Ranked hit : outcome.hits()) {
      items.add(
          new HitTestView.Item(
              rank++,
              hit.segmentId(),
              hit.card().title(),
              hit.card().docName(),
              hit.card().kbName(),
              hit.score(),
              hit.card().content(),
              hit.channels()));
    }
    return new HitTestView(
        outcome.mode(), items, outcome.elapsedMs(), outcome.trace(), outcome.detected());
  }

  /**
   * 一次检索的产出（不区分调用方）。
   *
   * <p>★ 只返回"排名 + 卡片"，不返回 {@link HitTestView.Item}：{@code Item} 是命中测试的展示形状（带序号），对话侧要的是 {@code
   * SourceRef}（带文档 id / 库 id / 密级）。两种形状都从 {@link Ranked} 翻，谁也别想替对方定形状。
   *
   * @param hits 已排序、已截到 topN
   */
  public record Outcome(
      List<Ranked> hits,
      RetrievalMode mode,
      long elapsedMs,
      HitTestView.Trace trace,
      HitTestView.Detected detected,
      QaRecallPort.Match directAnswer) {}

  /** 一条命中的候选人：分段 + 展示卡片 + 分数 + 它是被哪几路捞上来的。 */
  public record Ranked(
      UUID segmentId, SegmentSearchPort.Card card, double score, List<String> channels) {}

  /**
   * 两路扩召回"为什么没有东西可念"的说明。
   *
   * <p>★ 四档刻意分开，因为它们在界面上的处置完全不同：
   *
   * <ul>
   *   <li>失败 ⇒ 这是<strong>缺陷</strong>，要报出来（否则"少一路候选"永远没人知道）；
   *   <li>问句里没有实体 ⇒ 正常，说明这一路本来就没有起点；
   *   <li>认出实体但没有关系陈述 ⇒ 正常，但让人知道"该补语料"而不是"功能坏了"；
   *   <li>有东西可念 ⇒ 返回 null，界面去念实体与关系本身。
   * </ul>
   */
  static String detectNote(
      List<EntityRecallPort.Entity> entities,
      List<RelationRecallPort.Relation> relations,
      List<QaRecallPort.Match> qaMatches,
      String entityFailure,
      String relationFailure,
      String qaFailure) {
    List<String> parts = new ArrayList<>(2);
    if (entityFailure != null) {
      parts.add("实体扩召回失败：" + entityFailure);
    }
    if (relationFailure != null) {
      parts.add("关系扩召回失败：" + relationFailure);
    }
    if (qaFailure != null) {
      parts.add("问答对扩召回失败：" + qaFailure);
    }
    if (!parts.isEmpty()) {
      return String.join("；", parts) + " —— 本次少一路候选";
    }
    String qa = qaNote(qaMatches);
    if (entities.isEmpty() && relations.isEmpty()) {
      String base = "问句里没有识别到实体，两路扩召回都没有起点";
      return qa == null ? base : qa + "；" + base;
    }
    if (relations.isEmpty()) {
      String base = "认出 " + entities.size() + " 个实体，但它们在这些文档里没有关系陈述（抽取后才有边，见知识库「实体与关系」）";
      return qa == null ? base : qa + "；" + base;
    }
    return qa;
  }

  /** 命中问答对时的那句话；没命中返回 null（不占位、不说空话）。 */
  private static String qaNote(List<QaRecallPort.Match> qaMatches) {
    if (qaMatches.isEmpty()) {
      return null;
    }
    long withSegment = qaMatches.stream().filter(match -> match.segmentId() != null).count();
    String base = "命中 " + qaMatches.size() + " 条问答对（" + withSegment + " 条挂了出处分段）";
    return withSegment < qaMatches.size() ? base + "，没出处的那几条只能作为答案候选" : base;
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 校验口径与 mock 逐字一致（`mock/handlers/retrieval.ts` 的两处断言）。 */
  private void validate(HitTestQuery request) {
    if (request.question() == null || request.question().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入测试问题");
    }
    double threshold = request.thresholdOrDefault();
    if (threshold < 0 || threshold > 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "相似度阈值应在 0～1 之间");
    }
    int topN = request.topNOrDefault();
    if (topN < 1) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "TopN 至少为 1");
    }
    if (topN > HitTestQuery.MAX_TOP_N) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "TopN 应在 1～20 之间");
    }
    // ★ 越界一律 400 而不是静默夹到边界：界面上滑块到不了的值，只可能来自直接调接口 ——
    //   "收下但按别的值跑"会让调参的人以为参数生效了
    int queryCount = request.queryCountOrDefault();
    if (queryCount < 0 || queryCount > HitTestQuery.MAX_QUERY_COUNT) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "查询理解条数应在 0～5 之间");
    }
    int maxSubQuestions = request.maxSubQuestionsOrDefault();
    if (maxSubQuestions < 1 || maxSubQuestions > HitTestQuery.MAX_SUB_QUESTIONS_CAP) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "子问题数应在 1～5 之间");
    }
    int maxHops = request.maxHopsOrDefault();
    if (maxHops < 1 || maxHops > HitTestQuery.MAX_HOPS_CAP) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "多跳轮数应在 1～3 之间");
    }
  }

  /**
   * 多跳的那一轮（{@code 10 §3.4}）。
   *
   * <h3>★ 它必须"要么答得更好，要么什么都没发生"</h3>
   *
   * <p>{@code §3.4.6} 列了三种失败（下一轮检索失败 / 判定调用失败 / 下一轮 0 命中），处理全都是 <strong>用已有结果继续</strong> ——
   * 一次都没让检索失败。这里逐条对上，并且每条都留下 {@link HopStop}（{@code §7.1} 要的是"多跳的边际收益"，没这个数就没人能判断它值不值）。
   *
   * <p>★ 触发条件（{@code §3.4.2}）：首轮<strong>最高分 &lt; 阈值</strong> **或** 命中数 &lt; {@value #HOP_MIN_HITS}。
   * 规则判定，不额外调模型。
   *
   * @return 触发与否、轮次、停止原因、第 2 轮的排名（送进配额合并）、新增分段数、以及"该记 D1 吗"
   */
  private HopRun runMultiHop(
      RetrievalRequest request,
      RetrievalMode mode,
      RetrievalScope scope,
      int candidateLimit,
      long startedAt,
      List<List<UUID>> perSubQuestion,
      Map<UUID, Double> vectorScores,
      Map<UUID, Double> textScores) {
    LinkedHashSet<UUID> existing = new LinkedHashSet<>();
    perSubQuestion.forEach(existing::addAll);
    double bestScore =
        existing.stream()
            .mapToDouble(
                id ->
                    Math.max(vectorScores.getOrDefault(id, 0.0), textScores.getOrDefault(id, 0.0)))
            .max()
            .orElse(0.0);
    boolean triggered = bestScore < request.thresholdOrDefault() || existing.size() < HOP_MIN_HITS;
    if (!triggered) {
      return new HopRun(false, 1, HopStop.NOT_TRIGGERED, List.of(), 0, null);
    }

    LinkedHashSet<UUID> accumulated = new LinkedHashSet<>(existing);
    List<UUID> lastRanking = List.of();
    int rounds = 1;
    int newSegments = 0;
    while (rounds < request.maxHopsOrDefault()) {
      long used = elapsedMs(startedAt);
      if (used * 1.0 / budget.totalBudgetMs() >= budget.hopBudgetRatio()) {
        // §6.3 的 D1 触发点之一：预算用掉 60% ⇒ 去掉多跳（不是"等花光再停"）
        return new HopRun(
            true,
            rounds,
            HopStop.BUDGET,
            lastRanking,
            newSegments,
            "检索阶段已用掉 %.0f%% 的时间预算（≥%.0f%%）⇒ 去掉多跳（D1）"
                .formatted(used * 100.0 / budget.totalBudgetMs(), budget.hopBudgetRatio() * 100));
      }

      List<SegmentSearchPort.Card> cards = segmentSearch.load(List.copyOf(accumulated));
      MultiHopService.Outcome outcome =
          multiHop.decide(request.question(), cards, budget.totalBudgetMs() - used);
      if (!outcome.usable()) {
        // §3.4.6：判定失败 ⇒ 停止迭代、用已有结果生成（同时是 §6.3 的 D1 触发点之二）
        return new HopRun(
            true, rounds, HopStop.JUDGE_FAILED, lastRanking, newSegments, "多跳判定失败，已停止迭代（D1）");
      }
      HopDecision decision = outcome.decision();
      if (decision.sufficient()) {
        return new HopRun(true, rounds, HopStop.SUFFICIENT, lastRanking, newSegments, null);
      }
      if (!decision.hasNextQuery()) {
        return new HopRun(true, rounds, HopStop.NO_QUERY, lastRanking, newSegments, null);
      }

      List<UUID> next;
      try {
        // ★ 第 2 轮按"单条查询"发起（§3.4.5 的落地修订：粒度是查询，不是子问题）
        next =
            retrieveOneQuery(
                decision.nextQuery(),
                mode,
                scope,
                candidateLimit,
                request.thresholdOrDefault(),
                vectorScores,
                textScores);
      } catch (BusinessException failure) {
        // §3.4.6 第一行：下一轮检索失败 ⇒ 用已有结果继续
        log.warn("多跳第 {} 轮检索失败，用已有结果继续：{}", rounds + 1, failure.getMessage());
        return new HopRun(true, rounds, HopStop.NO_NEW_HITS, lastRanking, newSegments, null);
      }
      if (next.isEmpty()) {
        // §3.4.6 第三行：0 命中 ⇒ 停止（这条正是 analytics 里"多跳未改善"的那笔数据）
        return new HopRun(true, rounds, HopStop.NO_NEW_HITS, lastRanking, newSegments, null);
      }

      rounds++;
      lastRanking = next;
      int before = accumulated.size();
      accumulated.addAll(next);
      newSegments = accumulated.size() - before;
      log.info("多跳第 {} 轮：新增 {} 条分段（查询「{}」）", rounds, newSegments, decision.nextQuery());

      if (Jaccard.repeats(existing, new LinkedHashSet<>(next))) {
        // §3.4.4 条件 4：高度重合 ⇒ 停。★ 这一轮的结果**仍然累积**（"停止迭代"不等于"丢弃这一轮"）
        return new HopRun(true, rounds, HopStop.REPEATED, lastRanking, newSegments, null);
      }
      if (rounds >= request.maxHopsOrDefault()) {
        return new HopRun(true, rounds, HopStop.MAX_HOPS, lastRanking, newSegments, null);
      }
      existing = accumulated; // 下一轮的重合度与"到目前为止的全部结果"比
    }
    return new HopRun(true, rounds, HopStop.MAX_HOPS, lastRanking, newSegments, null);
  }

  /**
   * 多跳那一轮的结果。
   *
   * @param triggered 是否触发（触发条件不成立时它是 false，而"没触发"本身也要进埋点）
   * @param rounds 实际轮次（含首轮；没触发时是 1）
   * @param stopReason 为什么停下（{@link HopStop}）
   * @param ranking 最后一轮的排名（送进配额合并的那个单元）
   * @param newSegments 最后一轮带来的新分段数（§7.1："第 2 轮是否带来新分段"）
   * @param degradeReason 非空表示该记一次 D1（预算 60% / 判定失败）
   */
  private record HopRun(
      boolean triggered,
      int rounds,
      HopStop stopReason,
      List<UUID> ranking,
      int newSegments,
      String degradeReason) {}

  /**
   * 把"子问题 × 查询"摊平成"每次检索要发的一组查询"，并按总路数上限截断。
   *
   * <p>★ 截断规则（{@code 10 §6.2} 第 4 项）：<strong>按子问题顺序截断，且每个子问题至少保住 1 条查询</strong> —— 只按"总共 6
   * 条"砍的话，末尾的子问题会被整段丢掉。
   */
  private static List<List<String>> planPaths(QueryUnderstanding understanding) {
    List<List<String>> plan = new ArrayList<>();
    int used = 0;
    for (QueryUnderstanding.SubQuestion sub : understanding.subQuestions()) {
      if (used >= MAX_PATHS) {
        break;
      }
      int room = Math.min(sub.queries().size(), MAX_PATHS - used);
      List<String> queries = sub.queries().subList(0, Math.max(1, room));
      plan.add(queries);
      used += queries.size();
    }
    if (plan.isEmpty()) {
      // 理论上到不了（解析阶段保证至少一个子问题、一条查询），但空计划会让下面返空结果而"看起来像没命中"
      plan.add(List.of(understanding.subQuestions().getFirst().question()));
    }
    return plan;
  }

  /**
   * 一条查询的一次检索（模式内已经融合好）—— 返回该查询的候选次序。
   *
   * <p>★ 每一条查询都<strong>各自</strong>过一遍模式语义：混合模式是"这条查询的向量路 + 全文路先 RRF"，
   * 而不是"所有查询的向量路放一起、所有查询的全文路放一起"（后者会把不同查询的名次混起来算）。
   */
  private List<UUID> retrieveOneQuery(
      String query,
      RetrievalMode mode,
      RetrievalScope scope,
      int candidateLimit,
      double threshold,
      Map<UUID, Double> vectorScores,
      Map<UUID, Double> textScores) {
    List<UUID> vectorIds = List.of();
    if (mode != RetrievalMode.FULLTEXT) {
      List<SegmentSearchPort.Scored> hits = vectorSearch(query, scope, candidateLimit);
      for (SegmentSearchPort.Scored hit : hits) {
        vectorScores.merge(hit.segmentId(), hit.score(), Math::max);
      }
      // ★ 阈值只作用于向量路，且要在融合之前（见类注释：阈值是相似度，不是名次）
      vectorIds =
          hits.stream()
              .filter(hit -> hit.score() >= threshold)
              .map(SegmentSearchPort.Scored::segmentId)
              .toList();
    }

    List<UUID> textIds = List.of();
    if (mode != RetrievalMode.VECTOR) {
      List<SegmentSearchPort.Scored> hits =
          segmentSearch.fullText(new SegmentSearchPort.TextQuery(query, scope, candidateLimit));
      for (SegmentSearchPort.Scored hit : hits) {
        textScores.merge(hit.segmentId(), hit.score(), Math::max);
      }
      textIds = hits.stream().map(SegmentSearchPort.Scored::segmentId).toList();
    }

    return switch (mode) {
      case VECTOR -> vectorIds;
      case FULLTEXT -> textIds;
      case HYBRID ->
          Rrf.fuse(List.of(vectorIds, textIds)).stream().map(Rrf.Fused::segmentId).toList();
    };
  }

  /**
   * 向量那一路：问题 → 向量 → 按维度路由到表 → 相似度检索。
   *
   * <p>★ <strong>维度从"实际返回的向量长度"取</strong>，而不是查模型声明的维度： 写入侧用的也是这个长度（{@code
   * JdbcSegmentEmbeddingAdapter} 的 {@code embedding.dimension()} 来自返回向量）⇒ 两边取法一致，才不会出现"写进 A 表、检索 B
   * 表"这种静默错位。
   *
   * <h3>★★ 会话域在这条路上的边界：<strong>不让过</strong>（本批显式声明，见第一条判定）</h3>
   *
   * <p>会话临时的分段没有 {@code kb_knowledge_base} 行 ⇒ 它的向量模型继承<strong>平台默认</strong>（{@code 13
   * §11.2}），而"平台默认向量模型"那一格今天<strong>无处解析</strong>：库里没有那样一行，{@code embeddingModelByKb(空)} 也问不出模型 ⇒
   * 这条路既嵌不了问题向量、也定不了查哪张维度表。
   *
   * <p>本批的裁定是<strong>会话附件暂不参与向量路</strong>（只走全文一路），并且把它写成方法开头的<strong>显式判定</strong>：
   *
   * <ul>
   *   <li>它读的是 {@link RetrievalScope#hasConversationScope()} 与 {@link
   *       RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH} 两个具名值 —— 于是"会话域进了向量路"是一次
   *       <strong>可读到的</strong>判定，而不是"模型查不到 ⇒ 顺手抛了个错"；
   *   <li>★ 判定的<strong>唯一入口</strong>就是这里：本方法此前是 {@code private}，而"边界在不在"这件事必须能从测试里
   *       直接读到（否则只能靠一条公开路径撞上去，而那条路径今天传不进会话域）⇒ 改成包内可见，理由写在改动说明里；
   *   <li>★ 放开它的<strong>触发条件</strong>：当且仅当"平台默认向量模型"定清并落地（见 {@link
   *       RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH}）—— 那时把那个常量改成 {@code true}
   *       并<strong>删掉这一段</strong>， 而不是在这里加一个分支。
   * </ul>
   *
   * <p>★ 为什么是"明确拒绝"而不是"把会话域从域里摘掉再查"：摘掉之后会话附件在向量/混合模式下<strong>少看见</strong>，
   * 而那件事在读数上与"这次查询确实没命中"完全一样（本项目头号病）。拒绝会让"会话内走向量/混合模式"这件事 以一条带出处的消息暴露出来（{@code
   * RetrievalService.hitTest} 会把这一路丢掉并把原因记进轨迹 / 日志）。
   */
  List<SegmentSearchPort.Scored> vectorSearch(String question, RetrievalScope scope, int limit) {
    // ★★ 会话域的向量路边界（本批唯一的一处声明，见方法注释）：
    //   · 常量说"当前**不**收会话域"（放开它的触发条件写在 RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH）；
    //   · 判定说"这一批域里确实有会话临时，所以拒绝"。
    //   两件事分开写：谁把常量改成 true，这条判定就自动不再拦人（那时按方法注释删掉本段）。
    if (!RetrievalScope.CONVERSATION_SCOPE_IN_VECTOR_PATH && scope.hasConversationScope()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "会话附件暂不参与向量检索（它的向量模型 = 平台默认，那一格今天无处解析）",
          "会话内的检索请用全文模式；向量/混合模式覆盖会话附件随「平台默认向量模型」那一批开放");
    }
    Map<UUID, UUID> modelByKb = segmentSearch.embeddingModelByKb(scope.kbIdsWithModelRows());
    if (modelByKb.isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "这些知识库没有可用的向量模型，无法做向量检索", "请确认已绑定向量模型并已加载");
    }

    // ★★ 按模型分组：不同模型的向量在**不同空间、也在不同维度表**里 ⇒ 只能各查各的。
    //   这不是"兼容写法"，而是 **RRF 的又一次复用**（`10 §4.1`：混合模式内部 / 多查询 / 跨轮，
    //   同一个算法复用三次）。★ 这一点是**真栈闸门教我们的**：命中测试页在"不限定知识库"时
    //   会一次跨到 5 个模型（开发库实测），早先的写法在那里直接返回 400 —— 页面点不动，
    //   而那是"演示路径"。
    Map<UUID, List<UUID>> kbIdsByModel = new LinkedHashMap<>();
    modelByKb.forEach(
        (kb, model) -> kbIdsByModel.computeIfAbsent(model, key -> new ArrayList<>()).add(kb));

    List<List<UUID>> rankedPerModel = new ArrayList<>();
    Map<UUID, Double> similarity = new HashMap<>();
    BusinessException lastFailure = null;
    for (Map.Entry<UUID, List<UUID>> group : kbIdsByModel.entrySet()) {
      try {
        List<SegmentSearchPort.Scored> hits =
            searchWithinOneModel(group.getKey(), group.getValue(), scope, question, limit);
        rankedPerModel.add(ids(hits));
        for (SegmentSearchPort.Scored hit : hits) {
          similarity.merge(hit.segmentId(), hit.score(), Math::max);
        }
      } catch (BusinessException failure) {
        // ★ 一条坏引用不该打死整次检索。开发库实测：有库绑的向量模型已被删除 ⇒ 早先的写法
        //   把 NOT_FOUND 直接抛出去，命中测试页整页点不动 —— 而那是**演示路径**。
        //   ⇒ 跳过这一组、把原因写进日志（不是静默丢弃），其余照常检。
        lastFailure = failure;
        log.warn(
            "检索跳过 {} 个知识库：它们的向量模型 {} 不可用（{}）",
            group.getValue().size(),
            group.getKey(),
            failure.getMessage());
      }
    }
    if (rankedPerModel.isEmpty()) {
      // ★ 全坏才抛：这时返回空列表等于"让用户猜为什么没有结果"。
      throw lastFailure;
    }

    List<UUID> ordered =
        rankedPerModel.size() == 1
            ? rankedPerModel.getFirst()
            : Rrf.fuse(rankedPerModel).stream().map(Rrf.Fused::segmentId).toList();
    // ★ 展示分取的是"该分段在它自己那个模型下的相似度"（跨模型时量纲并不严格可比）——
    //   排序**不依赖**它（排序由 RRF 定），所以这点是可以接受的；写在注释里免得后人误解。
    List<SegmentSearchPort.Scored> result = new ArrayList<>(ordered.size());
    for (UUID id : ordered) {
      result.add(new SegmentSearchPort.Scored(id, similarity.getOrDefault(id, 0.0)));
    }
    return result;
  }

  /** 同一个向量模型下的检索：问题 → 向量 → 按维度路由到表 → 相似度。 */
  private List<SegmentSearchPort.Scored> searchWithinOneModel(
      UUID modelId, List<UUID> kbIds, RetrievalScope scope, String question, int limit) {
    List<float[]> embedded = embeddingPort.embed(modelId, List.of(question));
    if (embedded == null || embedded.isEmpty() || embedded.getFirst() == null) {
      throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "向量模型没有返回向量", "请稍后重试或检查模型服务状态");
    }
    float[] vector = embedded.getFirst();
    if (vector.length == 0) {
      throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "向量模型返回了空向量", "请检查模型服务");
    }

    // ★ 维度从**实际返回的向量长度**取，而不是查模型声明的维度：写入侧用的也是这个长度
    //   ⇒ 两边取法一致，才不会出现"写进 A 表、检索 B 表"这种静默错位。
    // ★ 检索本身交给 knowledge（`SegmentSearchPort.vectorSearch`）：密级与三域过滤必须与
    //   `<=>` 在同一条 SQL 里（pre-filter，13 §三），而只有那一侧认识这些谓词。
    // ★ 域随模型分组收窄（`withinShared`）：这一组的库 + 属于这一组的个人库，
    //   会话域照搬（它没有库行，本批在这条路上明确拒绝，见上面的守卫）。
    return segmentSearch.vectorSearch(
        new SegmentSearchPort.VectorQuery(
            vector.length, vector, scope.withinShared(kbIds), limit, modelId));
  }

  /** 单路直接用它的次序 —— 保留给"没有查询理解"的路径（模式内的融合在 {@link #retrieveOneQuery} 里做）。 */
  private static List<UUID> ids(List<SegmentSearchPort.Scored> scored) {
    return scored.stream().map(SegmentSearchPort.Scored::segmentId).toList();
  }

  private static String join(String head, String tail) {
    return tail == null || tail.isBlank() ? head : head + "；" + tail;
  }

  /** 轨迹里的 N 是"实际发出去几条查询"：全部失败时退化成 1（原始问题那一条）。 */
  private static int attemptedPathsOr(int attempted, int succeeded) {
    return succeeded > 0 ? attempted : 1;
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000;
  }
}
