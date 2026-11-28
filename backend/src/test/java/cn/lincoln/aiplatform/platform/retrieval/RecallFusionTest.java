package cn.lincoln.aiplatform.platform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RecallFusion} 的定序与展示分口径。
 *
 * <p>这一族断言存在的理由：它是{@code #378}那次收敛的<b>护栏</b>。融合规则从两份代码并成一份时，"并完之后行为没变"不能靠读，
 * 只能靠<b>把两条性质分别钉住</b>——一条是"两路都命中的排前面"（{@link Rrf} 的可观测特征），一条是"展示分取最大的那一路"
 * （对话侧原来那份各自的取法）。任何一条被改坏，用户看到的候选次序或相似度就会悄悄变。
 */
class RecallFusionTest {

  private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID S3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private static SegmentSearchPort.Scored hit(UUID id, double score) {
    return new SegmentSearchPort.Scored(id, score);
  }

  private static List<UUID> idsOf(List<SegmentSearchPort.Scored> fused) {
    return fused.stream().map(SegmentSearchPort.Scored::segmentId).toList();
  }

  @Test
  @DisplayName("两路都命中的排在最前 —— 即使它在各自那一路里名次都更差")
  void hitOnBothRoutesOutranksTheSingleRouteOne() {
    // s1 向量第 1、全文没有；s2 向量第 2、全文第 1
    var vector = List.of(hit(S1, 0.90), hit(S2, 0.80));
    var text = List.of(hit(S2, 0.95));

    var fused = RecallFusion.fuse(vector, text);

    // RRF：s2 = 1/62 + 1/61 > s1 = 1/61 ⇒ s2 在前，尽管它在向量那一路里输给 s1
    assertThat(idsOf(fused)).containsExactly(S2, S1);
  }

  @Test
  @DisplayName("展示分取该分段在各路里的最大原始分 —— 不是向量那一路的、也不是两路相加")
  void displayScoreTakesTheBestRouteNotTheFirstOrTheSum() {
    var vector = List.of(hit(S1, 0.90), hit(S2, 0.80));
    var text = List.of(hit(S2, 0.95));

    var fused = RecallFusion.fuse(vector, text);

    assertThat(fused).hasSize(2);
    var s2 = fused.getFirst();
    assertThat(s2.segmentId()).isEqualTo(S2);
    // ★ 取 0.95（全文那一路更好），不是 0.80（向量那一路）、也不是 1.75（相加）
    assertThat(s2.score()).isEqualTo(0.95);
  }

  @Test
  @DisplayName("一路为空时不占名额：结果就是另一路的次序与原分")
  void anEmptyRouteDoesNotEatQuota() {
    var vector = List.of(hit(S1, 0.9), hit(S2, 0.8), hit(S3, 0.7));

    var fused = RecallFusion.fuse(vector, List.of());

    assertThat(idsOf(fused)).containsExactly(S1, S2, S3);
    assertThat(fused).extracting(SegmentSearchPort.Scored::score).containsExactly(0.9, 0.8, 0.7);
  }

  @Test
  @DisplayName("同一分段被多路命中只出现一次")
  void theSameSegmentAppearsOnce() {
    var vector = List.of(hit(S1, 0.6), hit(S2, 0.5));
    var text = List.of(hit(S1, 0.7), hit(S2, 0.4));

    var fused = RecallFusion.fuse(vector, text);

    assertThat(fused).hasSize(2);
    assertThat(idsOf(fused)).containsExactlyInAnyOrder(S1, S2);
  }

  @Test
  @DisplayName("没有候选、或一路都没有时给空表 —— 不抛、不补占位")
  void emptyInputYieldsEmptyOutput() {
    assertThat(RecallFusion.fuse(List.<List<SegmentSearchPort.Scored>>of())).isEmpty();
    assertThat(RecallFusion.fuse(List.of(), List.of())).isEmpty();
    assertThat(RecallFusion.fuse(List.of(List.of(), List.of()))).isEmpty();
    assertThat(RecallFusion.fuse((List<List<SegmentSearchPort.Scored>>) null)).isEmpty();
  }
}
