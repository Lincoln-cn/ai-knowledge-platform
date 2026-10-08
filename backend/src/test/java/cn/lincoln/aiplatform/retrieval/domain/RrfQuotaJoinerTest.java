package cn.lincoln.aiplatform.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 子问题之间的配额合并（第 13 周 · 0.4.1，{@code 10 §3.3.3}）—— <strong>表驱动，零依赖</strong>。
 *
 * <h3>判据 ① 就落在这张表上：配额分配 / 去重累加 / 子问题保底</h3>
 *
 * <p>这三件事都是<strong>边界条件</strong>：M 比 topN 大、某个单元资料不足、同一个分段在多个单元里都靠前、
 * 向下取整的余数归谁……靠"跑一遍看看"盖不全，而它们的失效全部是**静默**的 （最典型的就是"问了两件事只答一件"，{@code §3.3.3} 的原文）。
 *
 * <h3>★★ 判据 ② 的金丝雀：{@link #quotaKeepsTheWeakerIntentAlive()} 就是它</h3>
 *
 * <p>那个用例刻意造成"单元 A 的每一条都比单元 B 靠前"。如果谁把配额"优化"成 一个全局排名（那是本节开头明确否掉的做法），B 就会**一条都进不去** ⇒ 用例变红。 ★
 * 它守的不是"算法长什么样"，而是"<strong>两个问题都要被回答</strong>"这个产品承诺。
 */
class RrfQuotaJoinerTest {

  // ────────────────────────── 表驱动 ──────────────────────────

  /**
   * @param expectedSelected 期望入选的 id（★ 顺序也要对：单元顺序 → 单元内排名，见类注释）
   */
  private record Case(
      String name,
      List<List<UUID>> unitIds,
      int topN,
      List<UUID> expectedSelected,
      int expectedFillIn) {

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * id 用固定字面量而不是随机 UUID：断言里要比"哪一条被选中"，随机的 id 让失败信息读不出来。 ★ 用可排序的字符串（{@code a1 <
   * b1}）还有个副作用：全局分同分时的兜底排序可预测（闸门不会偶发红）。
   */
  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static List<UUID> ids(String... names) {
    return Stream.of(names).map(RrfQuotaJoinerTest::id).toList();
  }

  static Stream<Case> cases() {
    return Stream.of(
        // topN=5、M=2 ⇒ q = max(2, 2) = 2：各拿 2 条，剩 1 条给全局分最高的
        new Case(
            "保底各两条 + 余量一条",
            List.of(ids("a1", "a2", "a3", "a4"), ids("b1", "b2", "b3")),
            5,
            ids("a1", "a2", "b1", "b2", "a3"),
            1),
        // 同一个分段出现在两个单元里 ⇒ 只出现一次，且分数累加（累加后它比其它人高，被补进来）
        new Case(
            "去重累加",
            List.of(ids("a1", "a2", "shared"), ids("b1", "b2", "shared")),
            5,
            ids("a1", "a2", "b1", "b2", "shared"),
            1),
        // 某个单元资料不足（只有 1 条）⇒ taken=1 < quota=3；余量回流给全局：
        //   保底拿 1 + 3 = 4 条，剩 2 个名额由全局分补足 ⇒ 最终 6 条（从不浪费名额）
        new Case(
            "单元资料不足则余量回流",
            List.of(ids("a1"), ids("b1", "b2", "b3", "b4", "b5")),
            6,
            ids("a1", "b1", "b2", "b3", "b4", "b5"),
            2),
        // ★ 退化情形：topN(2) < M(3) —— "每个子问题至少 2 条"不可能满足。
        //   本实现按契约的保底公式走：先到先得、绝不超 topN（后面的单元拿不到名额）。
        //   这里把它**钉住**而不是留白：不然"为什么第三个子问题没结果"会永远像 bug。
        new Case(
            "topN 小于 2M 时先到先得",
            List.of(ids("a1", "a2"), ids("b1", "b2"), ids("c1", "c2")),
            2,
            ids("a1", "a2"),
            0),
        // 单单元：退化成"取前 topN"，不做配额
        new Case("单单元不做配额", List.of(ids("a1", "a2", "a3", "a4")), 2, ids("a1", "a2"), 0));

    // 注意：seed 的顺序即"子问题顺序"，而单元内的顺序即"单元内排名"
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void joinsUnitsByQuota(Case item) {
    List<RrfQuotaJoiner.Unit> units = new ArrayList<>();
    for (int i = 0; i < item.unitIds().size(); i++) {
      units.add(new RrfQuotaJoiner.Unit(i, item.unitIds().get(i)));
    }

    RrfQuotaJoiner.Result result = RrfQuotaJoiner.join(units, item.topN());

    assertThat(result.selected())
        .as("选中的分段（含顺序）")
        .containsExactlyElementsOf(item.expectedSelected());
    assertThat(result.selected()).as("一个分段只出现一次（去重）").doesNotHaveDuplicates();
    assertThat(result.selected()).as("绝不超过 topN").hasSizeLessThanOrEqualTo(item.topN());
    assertThat(result.fillInCount()).as("靠全局分补进来的条数").isEqualTo(item.expectedFillIn());
  }

  // ────────────────────────── ★★ 金丝雀（判据 ②） ──────────────────────────

  @Test
  void quotaKeepsTheWeakerIntentAlive() {
    // 单元 A 的每一条都排在单元 B 的前面（模拟"问题 A 的资料与查询词面更近"）
    List<RrfQuotaJoiner.Unit> units =
        List.of(
            new RrfQuotaJoiner.Unit(0, ids("a1", "a2", "a3", "a4", "a5")),
            new RrfQuotaJoiner.Unit(1, ids("b1", "b2", "b3")));

    RrfQuotaJoiner.Result result = RrfQuotaJoiner.join(units, 5);

    // ★★ 这条断言就是判据 ②：把配额"优化"成一个全局排名 ⇒ B 的 3 条全被 A 挤掉 ⇒ 这里变红。
    //   它守的是产品承诺（"两个问题都被回答"），不是算法形状。
    assertThat(result.selected())
        .as("问题 B 必须拿得到它的保底份额 —— 否则就是'问了两件事只答一件'（10 §3.3.3）")
        .contains(id("b1"), id("b2"));
    assertThat(result.quotas().get(1).taken()).as("第二个单元的实得条数 = 它的保底配额").isEqualTo(2);
    assertThat(result.quotas().get(1).dropped())
        .as("该单元丢掉的条数要如实报（埋点要能解释'为什么这个子问题答得少'）")
        .isEqualTo(1);
  }

  // ────────────────────────── 退化与边界 ──────────────────────────

  @Test
  void emptyInputsGiveEmptyResult() {
    assertThat(RrfQuotaJoiner.join(List.of(), 5).selected()).isEmpty();
    assertThat(RrfQuotaJoiner.join(null, 5).selected()).isEmpty();
    assertThat(RrfQuotaJoiner.join(List.of(new RrfQuotaJoiner.Unit(0, ids("a1"))), 0).selected())
        .as("topN ≤ 0 ⇒ 空（契约里 topN 至少 1，这里是防御）")
        .isEmpty();
  }

  @Test
  void quotasAreReportedForObservability() {
    RrfQuotaJoiner.Result result =
        RrfQuotaJoiner.join(
            List.of(
                new RrfQuotaJoiner.Unit(0, ids("a1", "a2", "a3")),
                new RrfQuotaJoiner.Unit(1, ids("b1"))),
            5);

    assertThat(RrfQuotaJoiner.describe(result))
        .as("埋点要能回答'每个子问题分了几条、丢了几条'（10 §7.1）")
        .containsKeys(0, 1);
    assertThat(result.quotas().get(1).taken()).as("资料不足的单元如实报 1 条").isEqualTo(1);
  }
}
