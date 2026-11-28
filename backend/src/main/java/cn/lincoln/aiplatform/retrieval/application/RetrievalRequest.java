package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import java.util.List;
import java.util.UUID;

/**
 * 检索的一次请求 —— <strong>唯一的那一份</strong>。
 *
 * <p>★★ 这个类型存在的理由只有一条（`#378`）：检索策略在 0.9.8 之前有<strong>两套</strong> —— 命中测试走 {@code
 * RetrievalService}（四路 + 配额合并 + 重排），对话走 {@code
 * ChatService}（两路、无配额合并）。两套的差别不是"谁更简"，而是<strong>同一句问话在两个入口召回的东西不一样</strong>， 于是 B1/B3
 * 的读数测的不是用户拿到的东西。收敛到一处之后，"加第五路"只改一个地方。
 *
 * <p>字段与 {@link HitTestQuery} <strong>同名同义</strong>（{@code mode}/{@code threshold}/{@code topN}/…），
 * 连 {@code ...OrDefault()} 这些访问器都照抄 —— 这不是复制粘贴的偷懒，而是让搬运检索主体时
 * <strong>一行逻辑都不用改</strong>：搬迁本身已经够危险了，不该再叠加"把 {@code request.question()} 改成 {@code
 * request.query()} 时漏了一处"这种风险。
 *
 * <p>★ 与命中测试的差别只在最后三个字段：命中测试没有会话（{@code conversationId = null}）、个人库不参与 （{@code includePersonal =
 * false}）、阈值只当"向量召回的入口参数"（{@link ThresholdPolicy#HIT_TEST}）；对话相反，且阈值要<strong>两段式</strong>判 —— 见
 * {@link ThresholdPolicy}。
 *
 * @param conversationId 会话 id（对话链路用来把会话附件并进可见范围）；命中测试为 {@code null}
 * @param includePersonal 是否把"我的资料"（个人库）并入范围；命中测试恒 false
 * @param thresholdPolicy 阈值怎么用 —— 见 {@link ThresholdPolicy}
 */
public record RetrievalRequest(
    String mode,
    Double threshold,
    Integer topN,
    String noRefStrategy,
    List<UUID> kbIds,
    String question,
    Integer queryCount,
    Boolean decomposeEnabled,
    Integer maxSubQuestions,
    Boolean multiHopEnabled,
    Integer maxHops,
    Boolean rerankListwise,
    UUID conversationId,
    boolean includePersonal,
    ThresholdPolicy thresholdPolicy) {

  /**
   * 阈值用在哪一步。
   *
   * <p>★★ 两种口径<strong>必须分开</strong>，不能取其一当"统一行为"：
   *
   * <ul>
   *   <li>{@link #HIT_TEST}：阈值只作为向量召回的入口参数（{@code vectorSearch(...,
   *       threshold)}）。命中测试要回答的是"按这个阈值能召回什么"， 所以它<strong>不</strong>在结果上再做一次过滤 —— 否则"把阈值调到 0.9
   *       应该一条都没有"这件事在界面上看不出来。
   *   <li>{@link #CONVERSATION}：<strong>两段式</strong>（`#187`）。先按入口阈值收候选、但不立即丢弃，等卡片取回来之后
   *       <strong>按域</strong>判：共用库的分段要过阈值，会话附件不吃阈值（用户刚上传的东西，语义相似度天然低， 卡在 0.6
   *       会让"我刚传的文件"永远搜不到）。判定的两段必须这样分，因为 {@code Scored} 不带"这条属于哪个域"的信息， 边收边丢就再也分不出来了。
   * </ul>
   */
  public enum ThresholdPolicy {
    /** 阈值只影响向量召回入口，结果不再后置过滤。 */
    HIT_TEST,
    /** 两段式：先收候选，取回卡片后按域判（会话域不吃阈值）。 */
    CONVERSATION
  }

  /** 命中测试的请求（没有会话、不并个人库、阈值只做入口参数）。 */
  public static RetrievalRequest of(HitTestQuery request) {
    return new RetrievalRequest(
        request.mode(),
        request.threshold(),
        request.topN(),
        request.noRefStrategy(),
        request.kbIds(),
        request.question(),
        request.queryCount(),
        request.decomposeEnabled(),
        request.maxSubQuestions(),
        request.multiHopEnabled(),
        request.maxHops(),
        request.rerankListwise(),
        null,
        false,
        ThresholdPolicy.HIT_TEST);
  }

  public RetrievalMode modeOrDefault() {
    return RetrievalMode.parse(mode);
  }

  public double thresholdOrDefault() {
    return threshold == null ? 0.6 : threshold;
  }

  public int topNOrDefault() {
    return topN == null ? 5 : topN;
  }

  public int queryCountOrDefault() {
    return queryCount == null ? HitTestQuery.DEFAULT_QUERY_COUNT : queryCount;
  }

  public boolean decomposeEnabledOrDefault() {
    return decomposeEnabled == null || decomposeEnabled;
  }

  public int maxSubQuestionsOrDefault() {
    return maxSubQuestions == null ? HitTestQuery.DEFAULT_MAX_SUB_QUESTIONS : maxSubQuestions;
  }

  public boolean multiHopEnabledOrDefault() {
    return multiHopEnabled != null && multiHopEnabled;
  }

  public int maxHopsOrDefault() {
    return maxHops == null ? HitTestQuery.DEFAULT_MAX_HOPS : maxHops;
  }

  /** 沿用 {@link HitTestQuery} 的默认值（{@code 10 §3.4.2}：默认关，成本留给想付的人）。 */
  public ThresholdPolicy thresholdPolicyOrDefault() {
    return thresholdPolicy == null ? ThresholdPolicy.HIT_TEST : thresholdPolicy;
  }
}
