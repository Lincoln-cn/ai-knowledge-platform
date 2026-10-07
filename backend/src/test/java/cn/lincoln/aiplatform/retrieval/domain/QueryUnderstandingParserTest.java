package cn.lincoln.aiplatform.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 查询理解的解析与校验（第 12 周 · 0.4.0，{@code 10 §3.1.2} / {@code §3.3.2}）—— <strong>表驱动，零依赖</strong>。
 *
 * <h3>为什么这一层值得一张表</h3>
 *
 * <p>这一层的工作是"把没人保证过的形状收拾干净"，而失败模式全是<strong>静默</strong>的： 少截了一条查询 ⇒ 白花一次检索；两个子问题被合并 ⇒
 * "问了两件事只答一件"；查询超长 ⇒ 全文路退化（{@code tsquery} 的 AND 太严）；完全解析不了却当成"产出了一条空查询" ⇒ 检索返空而没人报错。
 * 所以每条规则都要有一个"给定这种输入 ⇒ 断言这个产出"的格子。
 *
 * <h3>★ 与 ROADMAP 的一处口径差异</h3>
 *
 * <p>{@code ROADMAP} 第 12 周判据 ① 写"数量不符回退原查询"，{@code 10 §3.3.2} 写"超上限按顺序截断"。 这里取设计册口径（截断），只对"产出 0 条
 * / 完全解析不了"回退 —— 理由见 {@link QueryUnderstandingParser} 的类注释。两种口径的差异在 {@link
 * #truncatedInsteadOfFallingBack()} 里钉住。
 */
class QueryUnderstandingParserTest {

  private static final String QUESTION = "差旅报销标准和审批流程分别是多少";

  /**
   * @param usable 期望"没有失败"（{@code true}）还是"失败了"（{@code false}）
   */
  private record Case(
      String name,
      String raw,
      int queryCount,
      int maxSubQuestions,
      boolean decomposeEnabled,
      int m,
      int n,
      boolean truncated,
      boolean usable) {

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * ★ 用 {@code @MethodSource} 而不是 {@code @CsvSource}：这些输入里全是 <strong>JSON 的引号</strong>， 而 CSV 也把
   * {@code "} 当引号 ⇒ 行会被 CSV 先"解析"一遍，列数与参数对不上。 症状是 {@code No ParameterResolver registered for
   * parameter …}，看起来像 JUnit 的问题， 其实是夹具写法的问题 —— 第一次就是这么错的。
   */
  static Stream<Case> cases() {
    return Stream.of(
        // 标准形状：两个子问题、各两条查询
        new Case(
            "标准 json",
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\",\"a2\"]},"
                + "{\"question\":\"B\",\"queries\":[\"b1\",\"b2\"]}]}",
            3,
            3,
            true,
            2,
            4,
            false,
            true),
        // 模型最常见的两种包装：代码块围栏 + 前后废话
        new Case(
            "围栏与废话",
            "好的，以下是结果：```json\n{\"subQuestions\":[{\"question\":\"A\","
                + "\"queries\":[\"a1\"]}]}\n```",
            3,
            3,
            true,
            1,
            1,
            false,
            true),
        // ROADMAP 判据 ① 的"换行分隔"：带列表装饰的行
        new Case("换行分隔", "- 差旅报销标准\n2. 差旅费报销办法\n* 出差补助标准", 3, 3, true, 1, 3, false, true),
        // 子问题数上限：3 个砍到 2 个，并标记截断
        new Case(
            "子问题超上限",
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\"]},"
                + "{\"question\":\"B\",\"queries\":[\"b1\"]},{\"question\":\"C\",\"queries\":[\"c1\"]}]}",
            3,
            2,
            true,
            2,
            2,
            true,
            true),
        // 查询条数上限：3 条砍到 2 条
        new Case(
            "查询超上限",
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\",\"a2\",\"a3\"]}]}",
            2,
            3,
            true,
            1,
            2,
            true,
            true),
        // 去重：重复的查询只留一条
        new Case(
            "查询去重",
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\",\"a1\",\"a2\"]}]}",
            3,
            3,
            true,
            1,
            2,
            false,
            true),
        // 子问题的查询为空 ⇒ 用它的 question 兜底（丢掉它等于丢掉用户问过的一个意图）
        new Case(
            "查询为空则兜底",
            "{\"subQuestions\":[{\"question\":\"B 的审批流程\",\"queries\":[]}]}",
            3,
            3,
            true,
            1,
            1,
            false,
            true),
        // 包含关系粗判去重：两个子问题是同一个问题
        new Case(
            "子问题去重",
            "{\"subQuestions\":[{\"question\":\"差旅报销标准\",\"queries\":[\"a1\"]},"
                + "{\"question\":\"差旅报销标准是多少\",\"queries\":[\"b1\"]}]}",
            3,
            3,
            true,
            1,
            1,
            false,
            true),
        // 只有 queries（模型简化成单意图）：按单意图处理，不是失败
        new Case("只有 queries", "{\"queries\":[\"a1\",\"a2\"]}", 3, 3, true, 1, 2, false, true),
        // 顶层是字符串数组
        new Case("顶层数组", "[\"a1\",\"a2\"]", 3, 3, true, 1, 2, false, true),
        // 完全解析不了 ⇒ failure 非空（调用方据此降级到 D4）
        new Case("解析不了", "这不是 json，也不是行列表……大概吧", 3, 3, true, 1, 1, false, false),
        // 空对象 ⇒ 解析不出子问题 ⇒ 失败
        new Case("空对象", "{}", 3, 3, true, 1, 1, false, false),
        // 关掉分解 ⇒ 多子问题合并成一个，问题回到原始问题
        new Case(
            "关闭分解",
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\"]},"
                + "{\"question\":\"B\",\"queries\":[\"b1\"]}]}",
            3,
            3,
            false,
            1,
            2,
            false,
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void parsesModelOutputIntoTheShapeDownstreamNeeds(Case item) {
    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            item.raw(),
            QUESTION,
            item.queryCount(),
            item.maxSubQuestions(),
            item.decomposeEnabled());

    assertThat(parsed.usable()).as("是否可用（failure 为空）").isEqualTo(item.usable());
    assertThat(parsed.understanding().subQuestionCount()).as("子问题数 M").isEqualTo(item.m());
    assertThat(parsed.understanding().totalQueries()).as("查询总数 N").isEqualTo(item.n());
    assertThat(parsed.truncated()).as("截断标记").isEqualTo(item.truncated());
    if (!item.usable()) {
      assertThat(parsed.failure()).as("失败必须给出原因（否则调用方无法解释为什么降级）").isNotBlank();
      // ★ 失败时的形状必须**已经可用**：原始问题单路 —— 调用方不需要自己造兜底
      assertThat(parsed.understanding().subQuestions().getFirst().queries())
          .containsExactly(QUESTION);
    }
  }

  // ────────────────────────── 逐条规则：几处需要看得更细的地方 ──────────────────────────

  @Test
  void truncatedInsteadOfFallingBack() {
    // ★ 口径差异的钉子：多给了两条**不**回退原查询，而是截断 + 记事件
    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\",\"a2\",\"a3\",\"a4\"]}]}",
            QUESTION,
            3,
            3,
            true);

    assertThat(parsed.usable()).isTrue();
    assertThat(parsed.understanding().totalQueries()).isEqualTo(3);
    assertThat(parsed.truncated()).isTrue();
    assertThat(parsed.events()).as("砍了几条要留痕 —— 否则'模型只给了 3 条'与'我砍到 3 条'长得一样").isNotEmpty();
  }

  @Test
  void overlongQueryIsTruncatedToTheLengthLimit() {
    String longQuery = "这是一条特别长的查询".repeat(12);

    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"" + longQuery + "\"]}]}",
            QUESTION,
            3,
            3,
            true);

    assertThat(parsed.understanding().subQuestions().getFirst().queries().getFirst())
        .hasSize(QueryUnderstandingParser.MAX_QUERY_LENGTH);
  }

  @Test
  void singleQueryWhenQueryCountIsOneMeansRewriteOnly() {
    // queryCount=1 = 纯改写：仍然是一个子问题、一条查询，只是那条查询是改写后的
    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"改写后的完整问题\"]}]}",
            QUESTION,
            1,
            3,
            true);

    assertThat(parsed.understanding().totalQueries()).isEqualTo(1);
    assertThat(parsed.understanding().subQuestions().getFirst().queries())
        .containsExactly("改写后的完整问题");
  }

  @Test
  void capsAreClampedToTheirHardLimits() {
    // 配置越界（比如直接调接口传 queryCount=99）时不能"按 99 去跑"，而要夹到硬顶
    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            "{\"subQuestions\":[{\"question\":\"A\",\"queries\":[\"a1\",\"a2\",\"a3\",\"a4\",\"a5\",\"a6\"]}]}",
            QUESTION,
            99,
            99,
            true);

    assertThat(parsed.understanding().totalQueries())
        .isEqualTo(QueryUnderstandingParser.MAX_QUERY_COUNT_CAP);
  }

  @Test
  void singleQuestionShapeIsTheFallbackUsedByCallers() {
    QueryUnderstanding single = QueryUnderstanding.single(QUESTION);

    assertThat(single.subQuestionCount()).isEqualTo(1);
    assertThat(single.totalQueries()).isEqualTo(1);
    assertThat(single.subQuestions().getFirst().question()).isEqualTo(QUESTION);
  }
}
