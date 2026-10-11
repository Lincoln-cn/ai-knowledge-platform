package cn.lincoln.aiplatform.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 集合重合度（{@code 10 §3.4.4} 停止条件 4）—— 多跳"防原地打转"的判据。
 *
 * <p>★ 这个类很小，但它守的是一个**只用轮数看不出来**的失败模式：模型给不出新查询时会换一种说法 再问一遍同一件事 —— 第 2 轮照样"有结果"，只是那 8 条里 7 条第 1 轮就有。
 */
class JaccardTest {

  private static final Set<UUID> A = ids(1, 2, 3, 4, 5);
  private static final Set<UUID> SAME = ids(1, 2, 3, 4, 5);
  private static final Set<UUID> HALF = ids(1, 2, 3, 9, 10);
  private static final Set<UUID> DISJOINT = ids(6, 7, 8, 9, 10);

  @Test
  void identicalSetsAreOne() {
    assertThat(Jaccard.similarity(A, SAME)).isEqualTo(1.0);
    assertThat(Jaccard.repeats(A, SAME)).as("完全一样 ⇒ 一定是原地打转").isTrue();
  }

  @Test
  void disjointSetsAreZero() {
    assertThat(Jaccard.similarity(A, DISJOINT)).isZero();
    assertThat(Jaccard.repeats(A, DISJOINT)).as("一条都不重合 ⇒ 这一轮有真东西").isFalse();
  }

  /**
   * ★★ 阈值边界：{@code |交|=3、|并|=7 ⇒ 0.428 < 0.6} 不算重复，而 {@code 4/6 = 0.667 > 0.6} 算。
   *
   * <p>把这两侧都钉住，是因为"0.6"这条线是设计册定的产品口径（{@code §3.4.4}）， 谁把它改成 {@code >=} 或改成 0.5，都要在这里现形。
   */
  @Test
  void thresholdBoundaryIsExclusiveAtSixTenths() {
    Set<UUID> threeOfSeven = ids(1, 2, 3, 9, 10, 11, 12);
    assertThat(Jaccard.similarity(ids(1, 2, 3, 4), threeOfSeven)).isLessThan(0.6);
    assertThat(Jaccard.repeats(ids(1, 2, 3, 4), threeOfSeven)).isFalse();

    Set<UUID> fourOfSix = ids(1, 2, 3, 4, 9, 10);
    assertThat(Jaccard.similarity(ids(1, 2, 3, 4), fourOfSix)).isGreaterThan(0.6);
    assertThat(Jaccard.repeats(ids(1, 2, 3, 4), fourOfSix)).isTrue();
  }

  /**
   * ★ "两轮都是空" ⇒ 0，不是 1、也不是 NaN。
   *
   * <p>返回 1 会让"什么都没找到"被当成"原地打转"（那会掩盖真正的原因），而 NaN 会让比较静默为 false。 "0 命中"是另一条分支（{@code §3.4.6} 的
   * {@code NO_NEW_HITS}）负责的事。
   */
  @Test
  void emptySetsAreZeroNotNaN() {
    assertThat(Jaccard.similarity(Set.of(), Set.of())).isZero();
    assertThat(Jaccard.similarity(A, Set.of())).isZero();
    assertThat(Jaccard.similarity(Set.of(), A)).isZero();
    assertThat(Jaccard.similarity(null, A)).isZero();
    assertThat(Jaccard.repeats(Set.of(), Set.of())).isFalse();
  }

  /** 入参是别人的集合，不许被改（这一层是纯函数，副作用会让调用方"莫名少了几条"）。 */
  @Test
  void inputsAreNotMutated() {
    Set<UUID> left = new LinkedHashSet<>(A);
    Set<UUID> right = new LinkedHashSet<>(HALF);

    Jaccard.similarity(left, right);

    assertThat(left).containsExactlyElementsOf(A);
    assertThat(right).containsExactlyElementsOf(HALF);
  }

  private static Set<UUID> ids(int... values) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (int value : values) {
      // 固定 UUID：越界不参与判定的都是**确定性**的值，读数是可复现的
      ids.add(UUID.fromString(String.format("00000000-0000-4000-8000-%012d", value)));
    }
    return ids;
  }
}
