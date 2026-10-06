package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import cn.lincoln.aiplatform.retrieval.domain.Rrf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 命中测试（三模式 + RRF）—— 本周的判据 ④ 就落在这个类里。
 *
 * <h3>链路（每一步的顺序都是有理由的，别调）</h3>
 *
 * <pre>
 *  校验 → 可见范围 → 每路取候选 → 阈值过滤 → 融合(RRF) → ★两路同谓词 → TopN → 组装
 * </pre>
 *
 * <ul>
 *   <li><strong>阈值在 TopN 之前</strong>（{@code 03 §2.3}）：反了会"取了 10 条但都不达标"。
 *   <li><strong>候选要过采样</strong>（{@link #OVERFETCH}）：过滤会砍掉一些，按 1×TopN 取的话 "过滤掉 3 条"就等于"少 3 条结果"。
 *   <li><strong>可见性判定放在融合之后、截断之前</strong>：先判会把 RRF 的名次算错 （被过滤掉的候选占的名次会消失，剩下的名次整体前移、分虚高）。★ 而"两路同谓词"
 *       这条纪律（{@code 13}）要求向量路拿回来的候选也过同一套判定 —— 所以它就落在这里。
 * </ul>
 *
 * <h3>★ 阈值的语义（一条刻意的边界）</h3>
 *
 * <p>阈值是<strong>相似度</strong>下限（{@code 10 §5.4}：它要随向量模型校准）⇒ 只作用于向量路。 全文路的 {@code ts_rank} 是无界的，拿 0–1
 * 的阈值去卡它，全文模式会永远为空 —— 而它恰恰是专有名词/编号类问题的唯一出路（{@code 03 §2.1}）。每条路有自己的准入判据， 融合只看名次（RRF 的全部意义）。
 */
@Service
public class RetrievalService {

  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

  /** 候选过采样倍数（见类注释）。上限 100：命中测试是人点的，不是批处理。 */
  private static final int OVERFETCH = 3;

  private static final int CANDIDATE_LIMIT_CAP = 100;

  private final SegmentSearchPort segmentSearch;
  private final VectorIndexPort vectorIndex;
  private final EmbeddingPort embeddingPort;

  public RetrievalService(
      SegmentSearchPort segmentSearch, VectorIndexPort vectorIndex, EmbeddingPort embeddingPort) {
    this.segmentSearch = segmentSearch;
    this.vectorIndex = vectorIndex;
    this.embeddingPort = embeddingPort;
  }

  @Transactional(readOnly = true)
  public HitTestView hitTest(HitTestQuery request) {
    long startedAt = System.nanoTime();
    RetrievalMode mode = request.modeOrDefault();
    validate(request);

    // ① 可见范围：看不见的库既不进检索、也不给"存在性"信号（07 §3.3 硬规矩 1）
    List<UUID> kbIds = segmentSearch.visibleKbIds(request.kbIds());
    if (kbIds.isEmpty()) {
      // ★ 这里是"没有可检索的库"，不是"没命中" —— 但对外都返空列表：
      //   区别只该出现在日志/埋点里，不该变成"这个库存在但你看不见"这种信息泄露。
      return new HitTestView(mode, List.of(), elapsedMs(startedAt));
    }

    int candidateLimit = Math.min(request.topNOrDefault() * OVERFETCH, CANDIDATE_LIMIT_CAP);

    // ② 每路取候选
    List<SegmentSearchPort.Scored> vectorHits =
        mode == RetrievalMode.FULLTEXT ? List.of() : vectorSearch(request, kbIds, candidateLimit);
    List<SegmentSearchPort.Scored> fullTextHits =
        mode == RetrievalMode.VECTOR
            ? List.of()
            : segmentSearch.fullText(
                new SegmentSearchPort.TextQuery(request.question(), kbIds, candidateLimit));

    // ③ 阈值过滤（只作用于向量相似度，见类注释）
    double threshold = request.thresholdOrDefault();
    vectorHits = vectorHits.stream().filter(hit -> hit.score() >= threshold).toList();

    // ④ 融合
    List<UUID> ordered = order(mode, vectorHits, fullTextHits);

    // ⑤ ★ 两路同谓词 + 截断
    Set<UUID> visible = segmentSearch.visibleAmong(ordered, kbIds);
    List<UUID> finalIds =
        ordered.stream().filter(visible::contains).limit(request.topNOrDefault()).toList();

    // ⑥ 组装：分数取"证据最好的那一路"，展示信息一次批量取（避免 N+1）
    Map<UUID, Double> scores = bestScores(vectorHits, fullTextHits);
    Map<UUID, SegmentSearchPort.Card> cards = new LinkedHashMap<>();
    for (SegmentSearchPort.Card card : segmentSearch.load(finalIds)) {
      cards.put(card.segmentId(), card);
    }

    List<HitTestView.Item> items = new ArrayList<>(finalIds.size());
    int rank = 1;
    for (UUID id : finalIds) {
      SegmentSearchPort.Card card = cards.get(id);
      if (card == null) {
        // 判过可见却取不到展示信息：只可能是并发删除（罕见）。跳过而不是塞一个空壳 ——
        // 空壳条目会让引用编号与实际内容错位。
        continue;
      }
      items.add(
          new HitTestView.Item(
              rank++,
              id,
              card.title(),
              card.docName(),
              card.kbName(),
              scores.getOrDefault(id, 0.0),
              card.content()));
    }
    return new HitTestView(mode, items, elapsedMs(startedAt));
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
  }

  /**
   * 向量那一路：问题 → 向量 → 按维度路由到表 → 相似度检索。
   *
   * <p>★ <strong>维度从"实际返回的向量长度"取</strong>，而不是查模型声明的维度： 写入侧用的也是这个长度（{@code
   * JdbcSegmentEmbeddingAdapter} 的 {@code embedding.dimension()} 来自返回向量）⇒ 两边取法一致，才不会出现"写进 A 表、检索 B
   * 表"这种静默错位。
   */
  private List<SegmentSearchPort.Scored> vectorSearch(
      HitTestQuery request, List<UUID> kbIds, int limit) {
    Map<UUID, UUID> modelByKb = segmentSearch.embeddingModelByKb(kbIds);
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
            searchWithinOneModel(group.getKey(), group.getValue(), request.question(), limit);
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
      UUID modelId, List<UUID> kbIds, String question, int limit) {
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
    List<VectorIndexPort.Hit> hits =
        vectorIndex.search(new VectorIndexPort.Query(vector.length, vector, kbIds, limit));
    return hits.stream()
        // 余弦距离 → 相似度：同一个向量是 0（→1），正交是 1（→0）。界面上那个阈值就是它。
        .map(hit -> new SegmentSearchPort.Scored(hit.segmentId(), 1.0 - hit.distance()))
        .toList();
  }

  /** 单路直接用它的次序；混合模式用 RRF 融合两路的名次（{@code 03 §2.2}）。 */
  private List<UUID> order(
      RetrievalMode mode,
      List<SegmentSearchPort.Scored> vectorHits,
      List<SegmentSearchPort.Scored> fullTextHits) {
    return switch (mode) {
      case VECTOR -> ids(vectorHits);
      case FULLTEXT -> ids(fullTextHits);
      case HYBRID ->
          Rrf.fuse(List.of(ids(vectorHits), ids(fullTextHits))).stream()
              .map(Rrf.Fused::segmentId)
              .toList();
    };
  }

  private static List<UUID> ids(List<SegmentSearchPort.Scored> scored) {
    return scored.stream().map(SegmentSearchPort.Scored::segmentId).toList();
  }

  /** 每条命中的展示分 = 两路里最高的那个分（混合模式也一样：RRF 分只用于定次序）。 */
  private static Map<UUID, Double> bestScores(
      List<SegmentSearchPort.Scored> vectorHits, List<SegmentSearchPort.Scored> fullTextHits) {
    Map<UUID, Double> best = new HashMap<>();
    for (SegmentSearchPort.Scored hit : vectorHits) {
      best.merge(hit.segmentId(), hit.score(), Math::max);
    }
    for (SegmentSearchPort.Scored hit : fullTextHits) {
      best.merge(hit.segmentId(), hit.score(), Math::max);
    }
    return best;
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000;
  }
}
