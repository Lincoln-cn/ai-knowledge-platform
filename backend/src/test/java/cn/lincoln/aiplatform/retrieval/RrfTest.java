package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.retrieval.domain.Rrf;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RRF 的表驱动单测（{@code ROADMAP} 第 10 周判据 ④ 的纯函数侧）。
 *
 * <p>★ 这个类**不碰库、不碰模型**：融合是纯函数，它的正确性不该靠集成测试来间接证明 —— 而它又是整条混合检索里唯一"算错了也不报错"的部分（名次错一点，结果看起来仍然合理）。
 *
 * <p>★ 断言直接读 {@code ranks}（每一路的名次），因为判据要的是 <strong>"两路都命中 ⇒ 分更高"</strong>这件事本身，而不只是"输出非空"。
 */
class RrfTest {

  private static UUID id(String tail) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000" + tail);
  }

  @Test
  void bothPathsHitScoresHigher() {
    UUID both = id("01");
    UUID vectorOnlyTop = id("02");

    // 向量路：02 第一、01 第二；全文路：01 第一、02 不在
    var fused = Rrf.fuse(List.of(List.of(vectorOnlyTop, both), List.of(both)));

    assertThat(fused).extracting(Rrf.Fused::segmentId).containsExactly(both, vectorOnlyTop);
    assertThat(fused.getFirst().score())
        .as("1/(60+2) + 1/(60+1) = 0.0325 —— 两路都命中必须压过单路第一的 1/61")
        .isGreaterThan(fused.get(1).score());
    assertThat(fused.getFirst().ranks()).as("ranks 留证：向量第 2、全文第 1").containsExactly(2, 1);
    assertThat(fused.get(1).ranks()).as("另一路没命中记 -1").containsExactly(1, -1);
    assertThat(fused.getFirst().paths()).as("命中了 2 路").isEqualTo(2);
  }

  @Test
  void sameSegmentAppearsOnceAndScoresAccumulate() {
    UUID same = id("03");
    var fused = Rrf.fuse(List.of(List.of(same), List.of(same), List.of(same)));

    assertThat(fused).as("合并的主键是 segmentId，不是「出现次数」").hasSize(1);
    double expected = 3 * (1.0 / (Rrf.K + 1));
    assertThat(fused.getFirst().score())
        .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void aSingleFirstPlaceIsNotBuriedByTwoMidRanks() {
    UUID onlyFirstInOnePath = id("42");
    UUID midInBothPaths = id("43");
    UUID filler = id("44");
    UUID filler2 = id("45");
    UUID filler3 = id("46");
    UUID filler4 = id("47");

    // 全文路：42 第一，43 第五；向量路：43 第五，42 不在。
    // ⇒ 纯 RRF 下 43 得 2/65 ≈ 0.0308、42 只得 1/61 ≈ 0.0164，43 会**压过** 42。
    var fused =
        Rrf.fuse(
            List.of(
                List.of(onlyFirstInOnePath, filler, filler2, filler3, midInBothPaths),
                List.of(filler, filler2, filler3, filler4, midInBothPaths)));

    // `#293` 的判据：融合后的名次**不得差于两路里较好的那一路**。
    // 42 的最好名次是 1，43 的是 5 ⇒ 42 必须排在前面，哪怕它的 RRF 分只有 43 的一半。
    // `#293` 的判据：融合后的名次**不得差于两路里较好的那一路**。
    // 42 的最好名次是 1、43 的是 5 ⇒ 42 必须排在 43 前面，
    // 哪怕 42 的 RRF 分（1/61 ≈ 0.0164）只有 43 的（2/65 ≈ 0.0308）一半。
    // ★ 不断言「42 正好第一」：向量路第 1 的那些填充段与 42 的 bestRank 同为 1，
    //   同分裁决给"两路都中"的更靠前 —— 那是**想要**的（它们排第一并不违反本判据）。
    List<UUID> order = fused.stream().map(Rrf.Fused::segmentId).toList();
    assertThat(order.indexOf(onlyFirstInOnePath))
        .as("一路第 1 不能被两路都第 5 压过（`#293` 实测：SIN-004-2 全文第 1 → 融合第 8）")
        .isLessThan(order.indexOf(midInBothPaths));
    assertThat(
            fused.stream()
                .filter(f -> f.segmentId().equals(midInBothPaths))
                .findFirst()
                .orElseThrow()
                .score())
        .as("RRF 分数本身没变——它退为同分裁决，只用来分「最好名次相同」的那些")
        .isGreaterThan(
            fused.stream()
                .filter(f -> f.segmentId().equals(onlyFirstInOnePath))
                .findFirst()
                .orElseThrow()
                .score());
  }

  @Test
  void constantKFlattensTopTwoGap() {
    UUID first = id("04");
    UUID second = id("05");
    var fused = Rrf.fuse(List.of(List.of(first, second)));

    double gap = fused.get(0).score() - fused.get(1).score();
    assertThat(gap).as("1/61 - 1/62 ≈ 0.000264 —— 差距极小，所以「两路都上榜」能轻易反超「一路第一」").isLessThan(0.001);
  }

  @Test
  void tieOrderIsStable() {
    UUID a = id("06");
    UUID b = id("07");
    // 同样的输入、连调两次：结果必须逐字相同（否则闸门会在同分样本上偶发红）
    assertThat(ids(Rrf.fuse(List.of(List.of(a), List.of(b)))))
        .isEqualTo(ids(Rrf.fuse(List.of(List.of(a), List.of(b)))));
    // 两路各命中一个、分数相同 ⇒ 次序来自「首次出现的顺序」，不是 hash 顺序
    assertThat(ids(Rrf.fuse(List.of(List.of(a), List.of(b))))).containsExactly(a, b);
    assertThat(ids(Rrf.fuse(List.of(List.of(b), List.of(a))))).containsExactly(b, a);
  }

  /** 只比 id 序列：{@code Fused} 里带 {@code int[]}，record 的 equals 对数组是引用比较，不能直接比对象。 */
  private static List<UUID> ids(List<Rrf.Fused> fused) {
    return fused.stream().map(Rrf.Fused::segmentId).toList();
  }

  @Test
  void emptyAndSinglePathAreAccepted() {
    assertThat(Rrf.fuse(List.of())).isEmpty();
    assertThat(Rrf.fuse(List.of(List.of()))).isEmpty();
    UUID only = id("08");
    assertThat(Rrf.fuse(List.of(List.of(only))))
        .extracting(Rrf.Fused::segmentId)
        .containsExactly(only);
  }

  @Test
  void rejectsInvalidK() {
    assertThatThrownBy(() -> Rrf.fuse(0, List.of(List.of(id("09")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k 必须 ≥ 1");
  }
}
