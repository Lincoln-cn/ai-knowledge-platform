package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.MarkdownChunker;
import cn.lincoln.aiplatform.knowledge.domain.SegmentChunk;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 分块四层边界规则（第 7 周 · 0.2.2 批 1）—— 表驱动，出自 {@code 12 §3.2}。
 *
 * <p>★ 这套测试要钉住的是<strong>「切在哪里」</strong>，不是「切出多少字」。 分块器最常见的静默故障是<strong>把一条法规切成两半</strong> ——
 * 不抛异常、不报错，只是召回时上下文残缺、引用时用户看到半句话。 所以断言都对着边界：
 *
 * <ol>
 *   <li><strong>「第X条」不跨切</strong>（L2 语义单元）：法律效力单元必须整条落在同一 chunk
 *   <li><strong>表格不跨块</strong>（硬规则 1）：整表一块，表头与数据行不许被拆散
 *   <li><strong>标题层级是分段锚点</strong>（L1 结构层）：章节边界处必须切开，并带上 {@code titlePath}
 * </ol>
 *
 * <p>四层优先级（{@code 12 §3.2}）：L1 标题 → L2 语义单元（条/段/表/代码）→ L3 句边界 → L4 硬切（打 {@code oversizedRisk}）。
 * 长度只在最后兜底 —— 优先在语义边界切。
 */
class MarkdownChunkerTest {

  private final MarkdownChunker chunker = new MarkdownChunker();

  // ────────────────────────── 表驱动：给定 Markdown ⇒ 断言 chunk 边界 ──────────────────────────

  /**
   * 一行用例的形状：期望块数 + 「必须同块」/「必须异块」的文本锚点。
   *
   * <p>为什么用文本锚点而不是角标：角标会随夹具微调而碎，锚点（如「第一条」「| 一线」）直接对应边界规则的语义。
   *
   * @param name 用例名（失败时好定位）
   * @param markdown 输入 Markdown（解析产物 {@code Document.parsedContent} 的形态）
   * @param segmentLength 分块目标长度
   * @param expectedCount 期望 chunk 数
   * @param together 每组内的文本必须落在<strong>同一</strong>chunk
   * @param apart 每组内的文本必须落在<strong>不同</strong>chunk
   */
  record ChunkCase(
      String name,
      String markdown,
      int segmentLength,
      int expectedCount,
      List<List<String>> together,
      List<List<String>> apart) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<ChunkCase> boundaryCases() {
    return Stream.of(
        // ① ★ 「第X条」不跨切：两条各自完整，不许出现「一条的尾巴 + 下一条的头」
        new ChunkCase(
            "第X条不跨切：相邻条文各占整块",
            """
            # 差旅报销规定

            第一条 出差前须提交书面申请并经部门负责人批准后方可出行，未经批准的费用不予报销。
            第二条 住宿标准见下表，超标部分由个人承担且不得以其他票据替代冲抵。
            """,
            50,
            2,
            List.of(List.of("第一条", "未经批准的费用不予报销"), List.of("第二条", "不得以其他票据替代")),
            List.of(List.of("第一条", "第二条"))),
        // ② ★ 「第X条」不跨切的反例方向：条文够短时允许合并成一块（不跨切 ≠ 必须切）
        new ChunkCase(
            "短条文允许合并_不跨切约束的是边界位置不是块数",
            """
            第一条 适用范围。
            第二条 解释权。
            """,
            200,
            1,
            List.of(List.of("第一条", "第二条")),
            List.of()),
        // ③ ★ 表格不跨块：表头 + 分隔行 + 数据行必须在同一 chunk
        new ChunkCase(
            "表格不跨块：整表一块",
            """
            # 住宿标准

            住宿标准如下表所示，请严格遵照执行。

            | 城市 | 标准 |
            | --- | --- |
            | 一线城市 | 500 元/天 |
            | 二线城市 | 400 元/天 |

            以上标准自发布之日起生效。
            """,
            80,
            3,
            List.of(
                List.of("| 城市 |", "| --- |", "| 一线城市 |", "| 二线城市 |"),
                List.of("住宿标准如下表所示"),
                List.of("以上标准自发布之日起生效")),
            List.of(List.of("| 一线城市 |", "以上标准自发布"))),
        // ④ ★ 标题层级是分段锚点：章节边界必须切开
        new ChunkCase(
            "标题是分段锚点：章节边界处切开",
            """
            # 第一章 总则

            本制度规定了差旅费的报销标准与流程，适用于公司各部门。

            ## 1.1 制定目的

            为规范差旅费管理，特制定本规定，自发布之日起施行。
            """,
            40,
            2,
            List.of(List.of("第一章 总则", "适用于公司各部门"), List.of("1.1 制定目的", "自发布之日起施行")),
            List.of(List.of("适用于公司各部门", "为规范差旅费管理"))),
        // ⑤ ★ 代码围栏不跨切（硬规则 2）：整段围栏一块
        new ChunkCase(
            "代码围栏不跨切",
            """
            配置示例如下。

            ```yaml
            segment:
              length: 200
              overlap: 50
            ```

            以上为默认值。
            """,
            60,
            3,
            List.of(List.of("```yaml", "segment:", "overlap: 50", "```")),
            List.of(List.of("```yaml", "以上为默认值"))),
        // ⑥ L3 句边界：超长段落按句切，不硬砍在句中。
        //    三句约 26+25+26=77 字，segmentLength=60 ⇒ 前两句一包（51）、末句一包 —— 若按 60 字硬砍会切在句中。
        new ChunkCase(
            "超长段落按句边界切",
            "第一条 本办法适用于公司全体员工的差旅报销管理活动。"
                + "各部门负责人应严格审核出差申请与费用单据的真实性。"
                + "财务部负责本办法的解释与修订工作并定期评估执行效果。",
            60,
            2,
            List.of(),
            List.of()),
        // ⑦ 无标题文档也能出块（PDF 段落流形态）
        new ChunkCase(
            "无标题段落流也能分块",
            "这是一段没有标题的正文内容，长度较短应当独占一块。\n\n这是第二段正文内容，同样没有标题层级。",
            40,
            2,
            List.of(List.of("一段没有标题", "独占一块"), List.of("第二段正文")),
            List.of(List.of("一段没有标题", "第二段正文"))),
        // ⑧ ★★ 「第X条」锚点：两条法条连着写、且第二条自己含多个句子时，
        //    边界必须落在「第二条」之前 —— 否则会切出「第一条的尾巴 + 第二条的前半」。
        //    ⚠️ limit 的取值是这个用例的关键（实测长度，不是手数的）：line1=26 字、line2=26 字。
        //       有锚点 ⇒ 两块（26 / 26）；无锚点 ⇒ 句装箱先吃掉「第一条 26 + 第二条的前半 15 = 41 ≤ 45」，
        //       再吐「12」—— 块数**同样是 2**。所以抓跨条切的是 together 断言，不是块数。
        new ChunkCase(
            "第X条锚点_多条连写时不切进条文中间",
            """
            第一条 本办法适用于公司全体员工的差旅报销管理活动。
            第二条 一、住宿标准见附表。二、超标部分由个人承担。
            """,
            45,
            2,
            List.of(List.of("第一条", "本办法适用于公司全体员工的差旅报销管理活动"), List.of("第二条", "二、超标部分由个人承担")),
            List.of(List.of("第一条", "第二条"))));
  }

  /** ★ 表驱动主断言。 同时检查三件事：块数（粗）、同块组（细）、异块组（边界位置）。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundaryCases")
  void 分块边界必须符合四层规则(ChunkCase kase) {
    List<SegmentChunk> chunks = chunker.chunk(kase.markdown(), kase.segmentLength());

    assertThat(chunks)
        .as("用例「%s」期望 %d 块", kase.name(), kase.expectedCount())
        .hasSize(kase.expectedCount());

    for (List<String> group : kase.together()) {
      for (String anchor : group) {
        int idx = indexOfChunkContaining(chunks, anchor);
        assertThat(idx).as("用例「%s」锚点「%s」必须落在某一块内（且同组各锚点同块）", kase.name(), anchor).isNotNegative();
      }
      // 同组锚点必须在同一 chunk
      int first = indexOfChunkContaining(chunks, group.get(0));
      for (String anchor : group) {
        assertThat(indexOfChunkContaining(chunks, anchor))
            .as("用例「%s」「%s」与「%s」必须同块", kase.name(), group.get(0), anchor)
            .isEqualTo(first);
      }
    }

    for (List<String> group : kase.apart()) {
      int first = indexOfChunkContaining(chunks, group.get(0));
      for (String anchor : group.subList(1, group.size())) {
        assertThat(indexOfChunkContaining(chunks, anchor))
            .as("用例「%s」「%s」与「%s」必须异块（边界不许跨过语义单元）", kase.name(), group.get(0), anchor)
            .isNotEqualTo(first);
      }
    }
  }

  // ────────────────────────── 标题路径（titlePath） ──────────────────────────

  /**
   * ★ {@code 12 §3.4}：{@code titlePath} 是结构化前缀的主体，也是「引用来自哪里」的材料。 格式固定为 {@code H1 > H2 >
   * H3}，防止各处拼法不一。
   */
  @Test
  void 标题路径按层级用大于号拼接() {
    String md =
        """
        # 报销管理办法

        ## 第三章 差旅费

        ### 3.2 住宿标准

        一线城市住宿标准为每人每天 500 元。
        """;

    List<SegmentChunk> chunks = chunker.chunk(md, 200);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).titlePath())
        .as("titlePath 必须是 H1 > H2 > H3 的完整路径（12 §3.4）")
        .isEqualTo("报销管理办法 > 第三章 差旅费 > 3.2 住宿标准");
    assertThat(chunks.get(0).title()).isEqualTo("3.2 住宿标准");
  }

  /** ★ 同一章节的后续 chunk 标题加序号（{@code 12 §3.4}）—— 引用抽屉里要能区分「同章第几块」。 */
  @Test
  void 同章节后续块标题带序号() {
    String md =
        """
        # 第三章 差旅费

        第一条 本章规定差旅费的报销标准。各部门应当严格执行本章各项条款并做好台账登记备查工作。
        第二条 超标费用由个人承担。任何单位不得以任何形式变通报销超标费用，违者追究责任。
        """;

    List<SegmentChunk> chunks = chunker.chunk(md, 60);

    assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    assertThat(chunks.get(0).title()).isEqualTo("第三章 差旅费");
    assertThat(chunks.get(1).title()).isEqualTo("第三章 差旅费（2）");
  }

  // ────────────────────────── 分块器契约：不产出超长块 ──────────────────────────

  /**
   * ★ {@code 12 §3.2} 硬规则 3：「保证不超长」是分块器的<strong>契约</strong>。 若分块器产出超长 chunk，入库时 {@code SEG-I1}
   * 会直接拒绝整篇 —— 所以这里断言每块都不超限（L4 兜底必须起效）。
   */
  @Test
  void 分块器契约_任何块都不超过segmentLength() {
    String md =
        """
        # 超长内容

        第一条
        %s
        """
            .formatted("无标点长数字".repeat(40));

    List<SegmentChunk> chunks = chunker.chunk(md, 80);

    assertThat(chunks).isNotEmpty();
    for (SegmentChunk chunk : chunks) {
      assertThat(chunk.charCount())
          .as("L4 兜底必须把每块压进 segmentLength，否则 SEG-I1 会拒绝整篇")
          .isLessThanOrEqualTo(80);
    }
    // 硬切过的块要打标，便于预览里提示质量风险
    assertThat(chunks.stream().anyMatch(SegmentChunk::oversizedRisk))
        .as("L4 硬切过的块必须打 oversizedRisk（12 §3.2 规则 4）")
        .isTrue();
  }

  @Test
  void 空输入返回空列表() {
    assertThat(chunker.chunk("", 100)).isEmpty();
    assertThat(chunker.chunk(null, 100)).isEmpty();
  }

  private static int indexOfChunkContaining(List<SegmentChunk> chunks, String anchor) {
    for (int i = 0; i < chunks.size(); i++) {
      if (chunks.get(i).content().contains(anchor)) {
        return i;
      }
    }
    return -1;
  }
}
