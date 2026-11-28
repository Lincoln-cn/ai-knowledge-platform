package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * 检索端口 —— 对话要的是「排好序的分段 + 哪条是直答」，不是命中测试的展示形状。
 *
 * <p>★★ 这个接口为什么存在（{@code #378} 收口时当场暴露的）：{@code RetrievalService} 住在 {@code
 * retrieval.application}，而那个包不在检索模块的暴露面上，{@code ModuleBoundaryTest} 立刻判红 ——
 *
 * <pre>{@code Module 'conversation' depends on non-exposed type
 * cn.lincoln.aiplatform.retrieval.application.RetrievalService within module 'retrieval'}</pre>
 *
 * <p>★ 处置<strong>不是</strong>给 {@code retrieval.application} 开 {@code @NamedInterface}。本仓库对这条路
 * 否定过四次，理由写在 {@link HopDeciderPort}、{@link EntityRecallPort}、{@link QueryUnderstandingPort} 与
 * {@code AppBindingPort} 的 javadoc 里，是同一句：<strong>「搬端口而不是给实现开 NamedInterface ——
 * 后者的读数是闸门绿了，前者才是依赖方向真的合法」</strong>。{@code AiChatNodeExecutor} 的注释还记着那次的教训： 「后来不得不把实现类开成
 * {@code @NamedInterface}」，于是那一条边至今说不清是"允许"还是"漏过了"。
 *
 * <p>所以：<strong>跨模块 SPI 放 {@code platform}、实现放提供方模块</strong>。对话侧只认识本接口，不 import 检索模块的任何内部类型；{@code
 * RetrievalService} 实现它，命中测试仍用它自己的完整类型（{@code HitTestQuery} → {@code HitTestView}，那是展示形状，不该往
 * platform 里搬）。
 *
 * <p>★ 本接口只有<strong>一个</strong>方法，是有意的：端口一旦长出第二个方法，就说明有第二件事在跨模块共享， 而"两处各写一份"正是 {@code #378}
 * 要根治的病。命中测试要的 trace / detected / degradeLevel 全部留在检索模块内部 —— 它们回答的是"检索为什么长这样"，不是"检索结果是什么"。
 */
public interface SegmentRetrievalPort {

  /**
   * 跑一次检索。
   *
   * @param spec 这次要检索什么、按什么口径（见 {@link Spec}）
   * @return 排好序的分段（带卡片与渠道）+ 命中时的那条直答型问答对（没有则为 {@code null}）
   */
  Outcome retrieve(Spec spec);

  /**
   * 阈值用在哪一步。
   *
   * <p>★★ 两种口径<strong>必须分开</strong>，不能取其一当"统一行为"：
   *
   * <ul>
   *   <li>{@link #HIT_TEST}：阈值只作为向量召回的入口参数（{@code vectorSearch(..., threshold)}）。
   *       命中测试要回答的是"按这个阈值能召回什么"，所以它<strong>不</strong>在结果上再做一次过滤 —— 否则"把阈值调到 0.9
   *       应该一条都没有"这件事在界面上看不出来。
   *   <li>{@link #CONVERSATION}：<strong>两段式</strong>（{@code #187}）。先按入口阈值收候选、但不立即丢弃，
   *       等卡片取回来之后<strong>按域</strong>判：共用库的分段要过阈值，会话附件不吃阈值（用户刚上传的东西， 语义相似度天然低，卡在 0.6
   *       会让"我刚传的文件"永远搜不到）。判定的两段必须这样分，因为 {@code Scored} 不带"这条属于哪个域"的信息，边收边丢就再也分不出来了。
   * </ul>
   */
  enum ThresholdPolicy {
    /** 阈值只影响向量召回入口，结果不再后置过滤。 */
    HIT_TEST,
    /** 两段式：先收候选，取回卡片后按域判（会话域不吃阈值）。 */
    CONVERSATION
  }

  /**
   * 一次检索的请求 —— <strong>唯一的那一份</strong>。
   *
   * <p>★★ 它存在的理由只有一条（{@code #378}）：检索策略在 0.9.8 之前有<strong>两套</strong> —— 命中测试走 {@code
   * RetrievalService}（四路 + 配额合并 + 重排），对话走 {@code ChatService}（两路、无配额合并）。两套的差别
   * 不是"谁更简"，而是<strong>同一句问话在两个入口召回的东西不一样</strong>，于是 B1/B3 的读数测的不是用户拿到的东西。 收敛到一处之后，"加第五路"只改一个地方。
   *
   * <p>字段与 {@code HitTestQuery} <strong>同名同义</strong>（{@code mode}/{@code threshold}/{@code
   * topN}/…）， 连 {@code ...OrDefault()} 这些访问器都照抄 —— 这不是复制粘贴的偷懒，而是让搬运检索主体时<strong>一行逻辑都不用改</strong>：
   * 搬迁本身已经够危险了，不该再叠加"把 {@code request.question()} 改成 {@code request.query()} 时漏了一处"这种风险。
   *
   * <p>★ 与命中测试的差别只在最后三个字段：命中测试没有会话（{@code conversationId = null}）、个人库不参与 （{@code includePersonal =
   * false}）、阈值只当"向量召回的入口参数"（{@link ThresholdPolicy#HIT_TEST}）； 对话相反，且阈值要<strong>两段式</strong>判 —— 见
   * {@link ThresholdPolicy}。
   *
   * @param conversationId 会话 id（对话链路用来把会话附件并进可见范围）；命中测试为 {@code null}
   * @param includePersonal 是否把"我的资料"（个人库）并入范围；命中测试恒 false
   * @param thresholdPolicy 阈值怎么用 —— 见 {@link ThresholdPolicy}
   * @param scope 已经解析好的可见范围；{@code null} 表示"让检索服务自己解析"
   */
  record Spec(
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
      ThresholdPolicy thresholdPolicy,
      RetrievalScope scope) {

    public RetrievalMode modeOrDefault() {
      return RetrievalMode.parse(mode);
    }

    public double thresholdOrDefault() {
      return threshold == null ? 0.6 : threshold;
    }

    public int topNOrDefault() {
      return topN == null ? 5 : topN;
    }

    /** 与命中测试的缺省同值（{@code 10 §3.4.2}：{@code 0} = 不做，{@code 1} = 只改写）。 */
    public int queryCountOrDefault() {
      return queryCount == null ? 3 : queryCount;
    }

    public boolean decomposeEnabledOrDefault() {
      return decomposeEnabled == null || decomposeEnabled;
    }

    /** 与 {@code RetrievalParams.DEFAULT_MAX_SUB_QUESTIONS} 同值（{@code #297}）。 */
    public int maxSubQuestionsOrDefault() {
      return maxSubQuestions == null ? RetrievalParams.DEFAULT_MAX_SUB_QUESTIONS : maxSubQuestions;
    }

    public boolean multiHopEnabledOrDefault() {
      return multiHopEnabled != null && multiHopEnabled;
    }

    /** 与 {@code RetrievalParams.DEFAULT_MAX_HOPS} 同值。 */
    public int maxHopsOrDefault() {
      return maxHops == null ? RetrievalParams.DEFAULT_MAX_HOPS : maxHops;
    }

    /** 沿用命中测试的默认值（{@code 10 §3.4.2}：默认关，成本留给想付的人）。 */
    public ThresholdPolicy thresholdPolicyOrDefault() {
      return thresholdPolicy == null ? ThresholdPolicy.HIT_TEST : thresholdPolicy;
    }
  }

  /**
   * 一条检索结果。
   *
   * <p>★ 它<strong>只</strong>带排名、卡片与渠道，不带"第几条"这种展示序号 —— 命中测试的 {@code HitTestView.Item} 是展示形状（带
   * rank），对话要的是 {@code SourceRef}（带文档 id / 库 id / 密级）。两种形状都从 本类型翻，谁也别想替对方定形状。
   *
   * @param channels 这条候选是被哪几路捞上来的（{@code vector}/{@code text}/{@code entity}/{@code
   *     relation}/{@code qa}），顺序由检索服务固定 —— 命中测试界要靠它说清"这条只被关系路捞上来"
   */
  record Ranked(UUID segmentId, SegmentSearchPort.Card card, double score, List<String> channels) {}

  /**
   * 检索的产出。
   *
   * <p>★ 只有两个分量，是刻意的：对话要的只是"哪些分段、按什么顺序"与"这条问话是不是正好命中一条直答型问答对"。 命中测试要的 trace / detected /
   * 耗时全部留在检索模块内部 —— 那是"检索为什么长这样"的解释，不是结果本身。
   *
   * @param hits 排好序的分段（已过阈值、已去重、已按配额合并）
   * @param directAnswer 命中即可原样回复的那条问答对；没有则为 {@code null}。★ 它是<strong>排在明细之外</strong>单独给的：
   *     明细有条数上限（{@code QA_DETAIL_LIMIT = 5}），一条最相关的直答完全可能排不进明细，让调用方从明细里挑 会让这个开关时而生效、时而不生效且看不出来
   */
  record Outcome(List<Ranked> hits, QaRecallPort.Match directAnswer) {}
}
