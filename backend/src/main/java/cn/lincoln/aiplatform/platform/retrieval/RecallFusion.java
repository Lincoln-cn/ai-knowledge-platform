package cn.lincoln.aiplatform.platform.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多路召回的融合：<strong>RRF 定序 + 展示分取各路原始分的最大值</strong> —— 纯函数，不碰库、不碰模型。
 *
 * <h3>为什么它必须只有一份</h3>
 *
 * <p>「融合」这件事原来写在两个地方：{@code conversation} 的 {@code ChatService} 里有一份自实现的 RRF（两路/多路两个重载），{@code
 * retrieval} 的 {@code RetrievalService} 里直接用 {@link Rrf}。 两份的<b>定序口径一致</b> （主序都是 RRF 分，{@code k =
 * 60}），但{@code ChatService} 那份多带一个"展示分"（给用户看的相似度）， 于是
 * <strong>两处各自演进</strong>：加一路召回时两边都要改，忘了改哪边就出现"命中测试比真实对话多两路"（{@code #378}）。
 *
 * <p>它的性质与 {@link Rrf} 一样是"可独立验证的算术"，所以放在 {@code platform}（跨模块共享类型与纯函数的口径）。
 *
 * <h3>两个分量的口径（都不许随手改）</h3>
 *
 * <ul>
 *   <li><strong>定序 = {@link Rrf}</strong> —— 直接复用同一个实现，包括它那条"同分时命中的路数多的排前"的 次序规则。★
 *       这条规则在对话侧此前<b>没有</b>（{@code ChatService} 自己那份只按分排序）；两路名次互换时 分数完全相同，而
 *       "两路都命中"确实比"只被一路捞到"更可信，所以统一到 {@code Rrf} 的口径是<b>收敛而不是改变</b>。
 *   <li><strong>展示分 = 该分段在各路中的最大原始分</strong> —— 不是 RRF 分（{@code 1/61} 这种量级对用户没有意义）， 也不是各路的和（{@code
 *       ts_rank} 与余弦相似度量纲不同，相加无意义）。{@code Math::max} 是唯一诚实的取法： 它回答的是"这条在它<b>表现最好</b>的那一路里有多像"。
 * </ul>
 *
 * <p>★ 空的一路不占名额：{@link Rrf} 对空列表不产生任何名次，所以"某一路失败退化为空"与"这一路没命中"在结果上 都是"少一路投票"，不会把名额挤掉。
 */
public final class RecallFusion {

  private RecallFusion() {}

  /** 融合若干路已排序的候选（路的顺序即名次口径，第 0 路写进 {@code ranks[0]}）。 */
  public static List<SegmentSearchPort.Scored> fuse(List<List<SegmentSearchPort.Scored>> routes) {
    if (routes == null || routes.isEmpty()) {
      return List.of();
    }
    List<List<UUID>> ranked = new ArrayList<>(routes.size());
    Map<UUID, Double> display = new LinkedHashMap<>();
    for (List<SegmentSearchPort.Scored> route : routes) {
      if (route == null) {
        ranked.add(List.of());
        continue;
      }
      List<UUID> ids = new ArrayList<>(route.size());
      for (SegmentSearchPort.Scored hit : route) {
        if (hit == null) {
          continue;
        }
        ids.add(hit.segmentId());
        // ★ 展示分只升不降：同一分段被多路命中时取"最好的那一路"的原始分
        display.merge(hit.segmentId(), hit.score(), Math::max);
      }
      ranked.add(ids);
    }
    List<SegmentSearchPort.Scored> fused = new ArrayList<>();
    for (Rrf.Fused row : Rrf.fuse(ranked)) {
      fused.add(
          new SegmentSearchPort.Scored(
              row.segmentId(), display.getOrDefault(row.segmentId(), 0.0)));
    }
    return fused;
  }

  /** 两路融合（最常见形态的可读入口）。 */
  public static List<SegmentSearchPort.Scored> fuse(
      List<SegmentSearchPort.Scored> routeA, List<SegmentSearchPort.Scored> routeB) {
    return fuse(List.of(routeA, routeB));
  }
}
