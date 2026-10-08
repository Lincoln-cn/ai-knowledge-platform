package cn.lincoln.aiplatform.retrieval.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 子问题之间的配额合并（第 13 周 · 0.4.1，{@code 10 §3.3.3} / {@code §4.3}）—— **"多问题收口整理"的落点**。
 *
 * <h3>★ 它解决什么：问题 A 的命中不许挤掉问题 B 的</h3>
 *
 * <p>{@code 10 §3.3.3} 开头明确否掉了那个最自然的做法 —— <em>把所有子问题的所有路丢进一个大 RRF 全局排名</em>： 问题 A
 * 的资料若与全局查询词面更近，就会**把问题 B 的名额全挤掉**，表现为"用户问了两个问题， 系统只答了一个"。这不是"效果差一点"，而是**功能没做到**。
 *
 * <h3>两段（{@code §3.3.3} 的表）</h3>
 *
 * <ol>
 *   <li><strong>保底配额</strong>：每个子问题先拿走 <b>{@code q = max(2, topN / M)}</b>（向下取整）条；
 *   <li><strong>补足</strong>：剩余名额按**全局 RRF 分**补足 —— 分是"该分段在它出现的每个单元里的名次"累加 （{@code §4.2}
 *       的去重累加：同一个分段在多个单元里都靠前 ⇒ 分更高，这正是它该被补进来的理由）。
 * </ol>
 *
 * <p>举 {@code topN=5, M=2}：{@code q = max(2, 2) = 2} ⇒ 每个子问题至少 2 条（共 4 条），剩 1 条给全局最高分。 ★ 即使 A
 * 的相关度远超 B，B 也拿得到 2 条 ⇒"两个问题都被回答"从**策略上**被保证，而不是靠运气。
 *
 * <h3>★ 输出顺序：让人一眼看出"哪些是保底进来的"</h3>
 *
 * <p>先是各单元的保底部分（单元顺序 → 单元内排名），再是补足部分（全局分降序）。 {@code §3.3.4} 说"编号顺序 = 子问题顺序 → 子问题内排名"，那描述的是保底部分的顺序；
 * 补足部分放在其后是因为它们**不属于任何一个子问题的保底份额** —— 打散混排会让"为什么这条在第 3 位"再也说不清（{@code §4.1} 要的可解释性）。
 *
 * <h3>为什么是纯函数</h3>
 *
 * <p>配额分配是一组**边界条件**（M 比 topN 大、单元资料不足、同一分段出现在多个单元、q 向下取整的余数……）， 而这些条件的组合靠"跑一遍看看"根本盖不全。所以它做成静态纯函数 +
 * 表驱动单测 （判据 ①："配额分配、去重累加、子问题保底"）。
 */
public final class RrfQuotaJoiner {

  /** RRF 的 k —— 与 {@link Rrf#K} 同一个常数，**不新增算法**（{@code §4.1}：一个 RRF 复用三次）。 */
  private static final int K = Rrf.K;

  private RrfQuotaJoiner() {}

  /**
   * 一个意图单元的候选序（已按 RRF 排好）。
   *
   * @param index 单元序号（从 0 起，用于保底顺序与可解释信息）
   * @param rankedIds 该单元的候选**次序**（越前越相关）—— ★ 是"序"不是"分"：融合只看名次（{@code §4.1}）
   */
  public record Unit(int index, List<UUID> rankedIds) {}

  /**
   * 合并结果。
   *
   * @param selected 最终入选的分段（顺序见类注释）
   * @param quotas 每个单元的配额与实际情况（给埋点用：{@code §7.1} 的"每单元的配额、被裁掉几条"）
   * @param fillInCount 靠全局分补进来的条数（可解释性：这几条不属于任何单元的保底份额）
   */
  public record Result(List<UUID> selected, List<UnitQuota> quotas, int fillInCount) {}

  /**
   * @param unitIndex 单元序号
   * @param quota 分到的保底配额
   * @param taken 实际靠保底进来几条（★ 单元资料不足时 < {@code quota}，余量回流给全局，{@code §4.4} 步骤 4 同源）
   * @param dropped 该单元级候选中**没进最终集合**的条数
   */
  public record UnitQuota(int unitIndex, int quota, int taken, int dropped) {}

  /**
   * 合并。
   *
   * @param units 意图单元的候选序（顺序即"子问题顺序"）
   * @param topN 最终要几条（≤ 0 ⇒ 空结果；M == 1 ⇒ 退化成"取前 topN"，不做配额）
   */
  public static Result join(List<Unit> units, int topN) {
    if (units == null || units.isEmpty() || topN <= 0) {
      return new Result(List.of(), List.of(), 0);
    }
    if (units.size() == 1) {
      // ★ 单单元不做配额：q = max(2, topN/1) 会把 topN>… 的情形算成"至少 2 条"，
      //   而单单元的正确语义就是"按序取前 topN"（配额是为**多单元之间**的公平而存在的）
      List<UUID> single = distinct(units.getFirst().rankedIds(), topN);
      return new Result(
          single,
          List.of(
              new UnitQuota(
                  0,
                  topN,
                  single.size(),
                  Math.max(0, distinctCount(units.getFirst().rankedIds()) - single.size()))),
          0);
    }

    int m = units.size();
    int quota = Math.max(2, topN / m);

    Set<UUID> selected = new LinkedHashSet<>();
    List<UnitQuota> quotas = new ArrayList<>(m);

    // 第一段：保底配额（单元顺序 → 单元内排名）
    for (Unit unit : units) {
      int before = selected.size();
      int taken = 0;
      for (UUID id : unit.rankedIds()) {
        if (taken >= quota || selected.size() >= topN) {
          break;
        }
        if (selected.add(id)) {
          taken++;
        }
      }
      int unitDistinct = distinctCount(unit.rankedIds());
      quotas.add(
          new UnitQuota(
              unit.index(),
              quota,
              selected.size() - before,
              // ★ 被裁掉 = 该单元里"没进最终集合"的去重条数（含被别的单元先占走的那部分，
              //   因为从这一单元的角度看，它确实没拿到名额）—— 埋点要的是"这个子问题丢了多少"
              Math.max(0, unitDistinct - taken)));
    }

    // 第二段：剩余名额按全局 RRF 分补足（分 = 在它出现的每个单元里的名次贡献之和）
    int fillIn = 0;
    if (selected.size() < topN) {
      for (UUID id : globalOrder(units)) {
        if (selected.size() >= topN) {
          break;
        }
        if (selected.add(id)) {
          fillIn++;
        }
      }
    }
    return new Result(List.copyOf(selected), List.copyOf(quotas), fillIn);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 全局次序：{@code score(d) = Σ 1/(K + rank_i(d))}，分高者先；同分按 id 兜底（可复现，闸门不会偶发红）。 */
  private static List<UUID> globalOrder(List<Unit> units) {
    Map<UUID, Double> scores = new HashMap<>();
    for (Unit unit : units) {
      List<UUID> ids = unit.rankedIds();
      for (int rank = 0; rank < ids.size(); rank++) {
        scores.merge(ids.get(rank), 1.0 / (K + rank + 1), Double::sum);
      }
    }
    List<UUID> ordered = new ArrayList<>(scores.keySet());
    ordered.sort(
        (a, b) -> {
          int byScore = Double.compare(scores.get(b), scores.get(a));
          return byScore != 0 ? byScore : a.compareTo(b);
        });
    return ordered;
  }

  private static List<UUID> distinct(List<UUID> ids, int limit) {
    return List.copyOf(new LinkedHashSet<>(ids)).stream().limit(limit).toList();
  }

  private static int distinctCount(List<UUID> ids) {
    return new LinkedHashSet<>(ids).size();
  }

  /** 给埋点与调试用的可读形态（{@code §7.1} 的"每单元的配额"）。 */
  public static Map<Integer, String> describe(Result result) {
    Map<Integer, String> out = new LinkedHashMap<>();
    result
        .quotas()
        .forEach(
            q ->
                out.put(
                    q.unitIndex(),
                    "配额 " + q.quota() + " / 实得 " + q.taken() + " / 丢 " + q.dropped()));
    return out;
  }
}
