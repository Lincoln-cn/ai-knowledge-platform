package cn.lincoln.aiplatform.retrieval.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RRF（倒数排名融合，{@code 03 §2.2}）—— <strong>纯函数</strong>，不碰库、不碰模型。
 *
 * <p>公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，{@code k = 60}。
 *
 * <p>★ 为什么选 RRF 而不是加权求和（设计册给了三条，这里记最关键的两条）：
 *
 * <ul>
 *   <li><strong>不需要分数归一化</strong>：余弦相似度是 0–1，而 {@code ts_rank} 是无界的， 直接加权毫无意义 —— RRF
 *       只看<strong>排名</strong>，天然免疫量纲问题；
 *   <li><strong>不需要调权重</strong>：加权求和要一个"向量占 0.7 还是 0.6"的超参，只能靠试。
 * </ul>
 *
 * <p>★★ 判据 ④ 要的"可观测特征"就落在返回值里：<strong>两路都命中的分段分更高</strong>。 所以 {@link Fused#ranks()}
 * 把每一路的名次都留下（{@code -1} = 这一路没命中）—— 没有它，"为什么这条排前面"就只能靠猜，而 RRF 的全部价值恰恰是
 * <strong>"在两种检索里都排得靠前的，总体排最前"</strong>这句话能被人读懂。
 */
public final class Rrf {

  /**
   * 常数 60（{@code 03 §2.2} 的取值）。
   *
   * <p>它的作用是把"第 1 名与第 2 名"的差距压平：{@code 1/61} vs {@code 1/62}。 于是"一路第 1 + 另一路没命中"（≈0.0164）会输给"两路都第
   * 5"（≈2×0.0154） —— 这正是混合检索想要的性质。
   */
  public static final int K = 60;

  private Rrf() {}

  /** 融合若干路已排序的候选。{@code rankedLists} 的顺序即"路的顺序"（第 0 路写进 {@code ranks[0]}）。 */
  public static List<Fused> fuse(List<List<UUID>> rankedLists) {
    return fuse(K, rankedLists);
  }

  /**
   * 融合（{@code k} 可注入 —— 只为测试能造小数字，生产一律用 {@link #K}）。
   *
   * <p>★ 同一分段被多路命中 ⇒ <strong>分累加、只出现一次</strong>（{@code 10 §4.1}： 合并的主键是 {@code segmentId}）。
   */
  public static List<Fused> fuse(int k, List<List<UUID>> rankedLists) {
    if (k < 1) {
      throw new IllegalArgumentException("RRF 的 k 必须 ≥ 1，收到：" + k);
    }
    Map<UUID, Accumulator> acc = new LinkedHashMap<>();
    for (int path = 0; path < rankedLists.size(); path++) {
      List<UUID> ranked = rankedLists.get(path);
      for (int index = 0; index < ranked.size(); index++) {
        UUID id = ranked.get(index);
        if (id == null) {
          continue;
        }
        Accumulator row = acc.computeIfAbsent(id, key -> new Accumulator(rankedLists.size()));
        // rank 从 1 起（公式是 1/(k + rank_i)，不是 1/(k + index)）
        row.score += 1.0 / (k + index + 1);
        row.ranks[path] = index + 1;
      }
    }

    List<Fused> fused = new ArrayList<>(acc.size());
    acc.forEach((id, row) -> fused.add(new Fused(id, row.score, row.ranks)));
    // ★ 同分时的次序不能随 HashMap 漂：先按"命中的路数多"（同样的分，两路命中比一路更可信），
    //   再按插入序（LinkedHashMap 保证了"第一次出现在哪一路、哪个名次"这个自然顺序）。
    //   没有这条，闸门会在同分样本上偶发红 —— 而那种红会被误当成"融合坏了"。
    fused.sort(
        Comparator.comparingDouble(Fused::score)
            .reversed()
            .thenComparing(f -> -hitCount(f.ranks())));
    return fused;
  }

  private static int hitCount(int[] ranks) {
    int n = 0;
    for (int rank : ranks) {
      if (rank > 0) {
        n++;
      }
    }
    return n;
  }

  /**
   * 融合后的一行。
   *
   * @param segmentId 分段 id（合并的主键）
   * @param score RRF 分（越小量级越大：单路第 1 ≈ 0.0164）
   * @param ranks 每一路的名次（1 起；{@code -1} = 这一路没命中）—— ★ 它就是"可观测特征"
   */
  public record Fused(UUID segmentId, double score, int[] ranks) {

    /** 命中了几个路 —— 判据 ④ 的断言直接读它（≥2 就说明"两路都命中"）。 */
    public int paths() {
      return hitCount(ranks);
    }
  }

  private static final class Accumulator {
    private double score;
    private final int[] ranks;

    private Accumulator(int paths) {
      this.ranks = new int[paths];
      java.util.Arrays.fill(this.ranks, -1);
    }
  }
}
