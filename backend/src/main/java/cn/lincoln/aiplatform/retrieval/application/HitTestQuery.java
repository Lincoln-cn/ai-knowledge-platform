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
 * @param noRefStrategy 无引用时的策略：命中测试<strong>不用它</strong>（那是问答那侧的口径）， 收下来只为让请求体形状与契约一致，免得前端"少传一个字段"就撞
 *     400
 * @param kbIds 限定知识库；不传 = 全部可见
 */
public record HitTestQuery(
    String mode,
    Double threshold,
    Integer topN,
    String noRefStrategy,
    List<UUID> kbIds,
    String question) {

  /** TopN 上限与 mock 的 PUT 校验一致（1–20）。 */
  public static final int MAX_TOP_N = 20;

  public RetrievalMode modeOrDefault() {
    return RetrievalMode.parse(mode);
  }

  public double thresholdOrDefault() {
    return threshold == null ? 0.6 : threshold;
  }

  public int topNOrDefault() {
    return topN == null ? 5 : topN;
  }
}
