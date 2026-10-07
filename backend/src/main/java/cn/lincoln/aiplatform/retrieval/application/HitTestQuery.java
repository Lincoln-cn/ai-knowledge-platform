package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import java.util.List;
import java.util.UUID;

/**
 * 命中测试的请求（契约 {@code HitTestRequest}）。
 *
 * <p>★ {@code mode} 收成 <strong>String</strong> 而不是枚举：契约是字符串 （{@code 'VECTOR' | 'FULLTEXT' |
 * 'HYBRID'}），交给 Jackson 直接映射枚举时， 一个拼错的值会变成 Jackson 那句面向开发者的报错；而这里要的是<strong>面向用户的</strong> "检索模式只支持
 * VECTOR / FULLTEXT / HYBRID，收到：xxx"（{@link RetrievalMode#parse}）。
 *
 * <p>★ 三个数值字段可空并各有默认值（{@code HYBRID / 0.6 / 5}）—— 与 mock 的 {@code findParams}
 * 逐字一致。两边默认值不同的话，症状是"演示时好用、部署后不一样"。
 *
 * <p>★ 查询理解那三个字段（{@code 10 §8.1} 新增）也是<strong>全部可选</strong>：不传 = 按上面的默认值， 于是"老前端 +
 * 新后端"照常工作（冻结契约下只加可选字段）。
 *
 * @param noRefStrategy 无引用时的策略：命中测试<strong>不用它</strong>（那是问答那侧的口径）， 收下来只为让请求体形状与契约一致，免得前端"少传一个字段"就撞
 *     400
 * @param kbIds 限定知识库；不传 = 全部可见
 * @param queryCount 查询理解每条查询数（{@code 0} = 不做，{@code 1} = 只改写）；缺省 {@value #DEFAULT_QUERY_COUNT}
 * @param decomposeEnabled 是否允许拆子问题；缺省 true
 * @param maxSubQuestions 子问题数上限；缺省 {@value #DEFAULT_MAX_SUB_QUESTIONS}
 */
public record HitTestQuery(
    String mode,
    Double threshold,
    Integer topN,
    String noRefStrategy,
    List<UUID> kbIds,
    String question,
    Integer queryCount,
    Boolean decomposeEnabled,
    Integer maxSubQuestions) {

  /** TopN 上限与 mock 的 PUT 校验一致（1–20）。 */
  public static final int MAX_TOP_N = 20;

  /** 查询理解条数的默认值与上限（{@code 10 §8.1} / {@code §6.2} 第 3 项）—— 与 mock 的 {@code findParams} 逐字一致。 */
  public static final int DEFAULT_QUERY_COUNT = 3;

  public static final int MAX_QUERY_COUNT = 5;

  /** 子问题上限的默认值与上限（{@code 10 §8.1} / {@code §6.2} 第 2 项）。 */
  public static final int DEFAULT_MAX_SUB_QUESTIONS = 3;

  public static final int MAX_SUB_QUESTIONS_CAP = 5;

  public RetrievalMode modeOrDefault() {
    return RetrievalMode.parse(mode);
  }

  public double thresholdOrDefault() {
    return threshold == null ? 0.6 : threshold;
  }

  public int topNOrDefault() {
    return topN == null ? 5 : topN;
  }

  /** {@code 0} = 不做查询理解（直接用原始问题）—— ★ 这是一个**合法配置**，不是错误。 */
  public int queryCountOrDefault() {
    return queryCount == null ? DEFAULT_QUERY_COUNT : queryCount;
  }

  /** ★ 缺省 <strong>true</strong>：多意图问题本来就该被拆开，"默认不做"等于把这层功能关在配置里。 */
  public boolean decomposeEnabledOrDefault() {
    return decomposeEnabled == null || decomposeEnabled;
  }

  public int maxSubQuestionsOrDefault() {
    return maxSubQuestions == null ? DEFAULT_MAX_SUB_QUESTIONS : maxSubQuestions;
  }
}
