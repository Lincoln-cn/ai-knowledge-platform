package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.PlannedSegment;
import cn.lincoln.aiplatform.knowledge.domain.SearchText;
import cn.lincoln.aiplatform.knowledge.domain.Segmenter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code Segmenter} —— 预览与入库共用的唯一分块入口（第 7 周 · 0.2.2 批 2a · 判据 ④）。
 *
 * <p>★ 这套测试钉住四件事：
 *
 * <ol>
 *   <li><strong>search_text = 前缀 + 正文</strong>，且前缀绝不进 {@code content}（{@code 12 §4.2} 纪律 1）
 *   <li><strong>SEG-I1 / SEG-I5</strong>：校验的是 {@code searchText} 全长，不是 {@code content}；超长必须抛，不许截断
 *   <li><strong>SEG-I7</strong>：前缀组成任一项变更 ⇒ {@code contentHash} 必须变
 *   <li><strong>确定性</strong>：同输入逐项相等 —— 这是「预览与入库同输出」的基础
 * </ol>
 *
 * <p>★ {@code maxChars} <strong>不是常量</strong>，是随绑定向量模型变化的领域配置（{@code 02 §2.3} / {@code 12 §9.1}）。
 * 本批不接模型目录，由调用方传入；测试把它当参数拧来拧去，正是为了证明这一点。
 */
class SegmenterTest {

  private final Segmenter segmenter = new Segmenter();

  private static final String DOC_TYPE = "制度";

  /**
   * ★ 前缀里的部门段自 {@code T300} 起是**稳定 org code**（不是展示名，{@code 07 §11.2}）： 所以这里的夹具值也跟着写成 code ——
   * 若有人再把它换回名字，先看这条注释。
   */
  private static final String ORG_CODE = "PURCHASING";

  private static final String TITLE = "差旅报销规定";

  private List<PlannedSegment> plan(String markdown, int segmentLength, int overlap, int maxChars) {
    return segmenter
        .plan(markdown, segmentLength, overlap, maxChars, DOC_TYPE, ORG_CODE, TITLE)
        .segments();
  }

  // ────────────────────────── 1 · 结构化前缀（12 §4.2 纪律 1） ──────────────────────────

  /** ★ 纪律 1：前缀绝不进 {@code content}（引用抽屉展示原文）。 search_text 形态钉死为 「文种·部门code·文档名｜章节路径」+ 分隔 + 正文。 */
  @Test
  void searchText是前缀加正文_且前缀绝不进content() {
    String md =
        """
        # 第三章 差旅费

        一线城市住宿标准为每人每天 500 元。
        """;

    List<PlannedSegment> plan = plan(md, 200, 0, 1000);

    assertThat(plan).hasSize(1);
    PlannedSegment seg = plan.get(0);
    // ★ 部门段是 org code（不是"采购部"这个展示名）—— 07 §11.2：名字可变/可重名，不许进命中材料
    String expectedPrefix = "制度·PURCHASING·差旅报销规定｜第三章 差旅费";

    assertThat(seg.searchText())
        .as("search_text = 前缀 + 分隔 + 正文（12 §4.2）")
        .isEqualTo(expectedPrefix + SearchText.SEPARATOR + seg.content());
    assertThat(seg.searchText()).startsWith(expectedPrefix);
    assertThat(seg.content())
        .as("纪律 1：前缀绝不进 content —— 否则引用里出现一堆元信息")
        .doesNotContain("制度·PURCHASING")
        .isEqualTo("# 第三章 差旅费\n一线城市住宿标准为每人每天 500 元。");
    assertThat(seg.titlePath()).isEqualTo("第三章 差旅费");
    assertThat(seg.title()).isEqualTo("第三章 差旅费");
  }

  // ────────────────────────── 2 · 有效目标长度（SEG-I5，可直接断言） ──────────────────────────

  /**
   * ★ {@code SEG-I5}：进向量的是 searchText（含前缀）⇒ 实际切分目标要扣掉前缀与分隔符， 再与 {@code segmentLength}
   * 取更紧的一侧。overlap 由 {@code MarkdownChunker} 内部预留， 不在此处扣（避免两处各扣一次）。
   */
  @Test
  void 有效目标长度扣掉前缀并与segmentLength取紧() {
    assertThat(Segmenter.effectiveTargetLength(200, 1000, 30))
        .as("segmentLength 更紧 ⇒ 用 segmentLength")
        .isEqualTo(200);
    assertThat(Segmenter.effectiveTargetLength(500, 100, 30))
        .as("向量预算更紧：100 - 30 - 1 = 69")
        .isEqualTo(100 - 30 - SearchText.SEPARATOR.length());
    assertThat(Segmenter.effectiveTargetLength(500, 50, 80))
        .as("前缀吃穿向量预算时正文目标为负 —— 由 plan() 抛 SEGMENT_TOO_LONG，而不是静默切出空块")
        .isNegative();
  }

  // ────────────────────────── 3 · SEG-I1 / SEG-I5 金丝雀 ──────────────────────────

  /**
   * ★ 金丝雀：把 {@code maxChars} 调到很小，必须抛 {@code SEGMENT_TOO_LONG} 且 hint 非空 ——
   * 上限是参数不是常量。若有人把校验写死成固定数，这条会红。
   */
  @Test
  void 金丝雀_把maxChars调小必须抛SEGMENT_TOO_LONG() {
    String md = "正文内容若干，用于占位。";

    assertThatThrownBy(() -> plan(md, 100, 0, 5))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.code()).isEqualTo(ErrorCode.SEGMENT_TOO_LONG);
              assertThat(be.hint()).isNotBlank();
            });
  }

  /**
   * ★★ 自动路径绝不因「章节路径长」把整篇文档判死，且任何一段的 searchText 都不得超上限 （{@code 12 §10.2} 风险 1
   * 定的处置方式是<strong>设上限</strong>，<strong>不是拒绝整篇</strong>）。
   *
   * <p>为什么这条是硬要求：装箱预算若按「文档级前缀」（文种·部门·文档名｜，很短）估，就会出现内部自相矛盾 —— 估算器认为内容装得下、校验器却因真实前缀（含 {@code
   * titlePath}）更长而拒绝 ⇒ <strong>整篇建档失败</strong>。 触发它只需要两个都很普通的条件：用户把「分段长度」设成接近上限的值（向导滑块上限 2000、默认
   * {@code modelLimit} 1200，选 1200 是很自然的动作），且该文档有较长的章节标题。
   *
   * <p>所以预算必须按<strong>最坏前缀</strong>（{@link SearchText#MAX_PREFIX_LENGTH}，前缀由构造保证不超它）估： {@code
   * searchText ≤ (maxChars − 80 − 1) + 1 + 80 = maxChars} 成为算术恒等式 —— 自动路径的 SEG-I1 永不触发。
   * 代价：文档级前缀很短时每段少用几十字预算（≤ 4%），换「自动路径不可能整篇失败」，值。
   *
   * <p>★ 硬校验不因此消失：{@link cn.lincoln.aiplatform.knowledge.domain.SegmentContent#ofSearchText} 仍是
   * SEG-I1 的执法点，它的目标是<strong>用户编辑过的切分计划</strong>（{@code 12 §3.5}：用户可能粘进超长内容）， 那条路径由批 2b 的端点校验并已有
   * {@code SegmentContentTest} 覆盖。
   */
  @Test
  void 章节路径很长也不得把整篇判死_且预算按最坏前缀预留() {
    String longHeading = "很长的章节标题".repeat(8); // 48 字：真实前缀会明显长于文档级前缀
    String body = "本办法适用于公司全体员工的差旅报销管理活动，各部门应当严格执行。".repeat(50);
    String md = "# " + longHeading + "\n\n" + body;
    int maxChars = 1200;
    int worstContent = maxChars - SearchText.MAX_PREFIX_LENGTH - SearchText.SEPARATOR.length();

    assertThat(SearchText.prefix(DOC_TYPE, ORG_CODE, TITLE, longHeading).length())
        .as("夹具前提：真实前缀必须显著长于文档级前缀，否则这条用例抓不到问题")
        .isGreaterThan(SearchText.prefix(DOC_TYPE, ORG_CODE, TITLE, "").length() + 20);

    // 分段长度 = 向量上限：预算最紧的一档
    List<PlannedSegment> plan = plan(md, maxChars, 0, maxChars);

    assertThat(plan).as("必须切出分段，而不是整篇失败").isNotEmpty();
    for (PlannedSegment seg : plan) {
      assertThat(seg.searchText())
          .as("SEG-I1 不变量：前缀按最坏值预留 ⇒ 任何一段的 searchText 都不超上限")
          .hasSizeLessThanOrEqualTo(maxChars);
      assertThat(seg.content().length())
          .as("正文目标必须扣掉「最坏前缀」与分隔符（SEG-I5：进向量的是 searchText 而不是 content）")
          .isLessThanOrEqualTo(worstContent);
    }
  }

  /**
   * ★ 预算真的装不下前缀时，必须给<strong>可读原因 + 下一步建议</strong>（与 {@code SegmentContentTest} 同族口径）， 而不是切出空块或抛
   * NullPointerException。
   */
  @Test
  void 预算装不下前缀时给可读原因与建议() {
    String longHeading = "章节".repeat(30);
    String md =
        """
        # %s

        正文。
        """
            .formatted(longHeading);
    int maxChars = 40;

    assertThatThrownBy(() -> plan(md, 100, 0, maxChars))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              String message = ((BusinessException) e).getMessage();
              assertThat(message).contains(String.valueOf(maxChars));
              assertThat(message).as("message 必须给出可读的长度信息").contains("字");
            });
  }

  // ────────────────────────── 4 · SEG-I7：前缀组成变更 ⇒ hash 必变 ──────────────────────────

  /**
   * ★ {@code SEG-I7}（{@code 12 §4.2} 纪律 3）：文种/部门 code/标题/章节路径任一项变更 ⇒ contentHash 必须变。
   *
   * <p>否则「改了部门标签但向量没更新」会变成静默不一致 —— contentHash 是重新向量化的触发依据。
   */
  @Test
  void 改部门或章节路径时contentHash必须全变() {
    String md =
        """
        # 第一章 总则

        本制度规定了差旅费的报销标准与流程。
        """;

    List<PlannedSegment> base = plan(md, 200, 0, 1000);
    List<PlannedSegment> otherDept =
        segmenter.plan(md, 200, 0, 1000, DOC_TYPE, "FINANCE", TITLE).segments();
    List<PlannedSegment> otherPath =
        plan(
            """
            # 第二章 附则

            本制度规定了差旅费的报销标准与流程。
            """,
            200,
            0,
            1000);

    assertThat(base).isNotEmpty();
    assertThat(base).hasSameSizeAs(otherDept).hasSameSizeAs(otherPath);

    for (int i = 0; i < base.size(); i++) {
      assertThat(otherDept.get(i).contentHash())
          .as("SEG-I7：部门变更 ⇒ 第 %d 段 hash 必须变", i)
          .isNotEqualTo(base.get(i).contentHash());
      assertThat(otherPath.get(i).contentHash())
          .as("SEG-I7：章节路径变更 ⇒ 第 %d 段 hash 必须变", i)
          .isNotEqualTo(base.get(i).contentHash());
    }
  }

  /** ★ hash 形态：sha256 十六进制 64 位，且同输入幂等（它要落 {@code varchar(64)}）。 */
  @Test
  void contentHash幂等且为64位十六进制() {
    String md = "这是一段用于哈希的正文内容。";

    List<PlannedSegment> a = plan(md, 200, 0, 1000);
    List<PlannedSegment> b = plan(md, 200, 0, 1000);

    assertThat(a).isNotEmpty();
    assertThat(a.get(0).contentHash())
        .isEqualTo(b.get(0).contentHash())
        .hasSize(64)
        .matches("[0-9a-f]{64}");
  }

  // ────────────────────────── 5 · 确定性（判据 ④ 的基础） ──────────────────────────

  /** ★ 同输入连续两次 plan() 必须逐项相等 —— 否则「预览与入库同输出」无从谈起。 */
  @Test
  void 同输入两次plan逐项相等() {
    String md =
        """
        # 第一章

        第一条 本办法适用于公司全体员工的差旅报销管理活动。
        第二条 住宿标准见下表，超标部分由个人承担。

        | 城市 | 标准 |
        | --- | --- |
        | 一线 | 500 |
        """;

    List<PlannedSegment> first = plan(md, 60, 8, 800);
    List<PlannedSegment> second = plan(md, 60, 8, 800);

    assertThat(first).isNotEmpty().isEqualTo(second);
  }

  // ────────────────────────── 6 · 计划字段完整性 ──────────────────────────

  /** ★ 每段都要带齐入库/预览需要的字段（{@code 12 §3.2} 规则 4 + §4.2）。 */
  @Test
  void planSegmentsCarryAllFields() {
    String md =
        """
        # 住宿标准

        | 城市 | 标准 |
        | --- | --- |
        | 一线 | 500 |

        正文补充说明一句。
        """;

    List<PlannedSegment> plan = plan(md, 80, 0, 500);

    assertThat(plan).hasSizeGreaterThanOrEqualTo(2);
    for (PlannedSegment seg : plan) {
      String prefix = SearchText.prefix(DOC_TYPE, ORG_CODE, TITLE, seg.titlePath());
      assertThat(seg.content()).isNotBlank();
      if (seg.isTable()) {
        // ★ 0.3.2 起的分工（判据 ③）：content 保留**原表**（引用可溯源），
        //   search_text 用**行级陈述**替换表格 —— 于是两列必然不同。
        assertThat(seg.content()).as("content 仍是原表").contains("| --- |").contains("| 一线 | 500 |");
        assertThat(seg.searchText())
            .as("search_text 里是陈述、不是原表")
            .contains("城市：一线")
            .doesNotContain("| --- |")
            .isNotEqualTo(SearchText.of(prefix, seg.content()));
      } else {
        assertThat(seg.searchText()).isEqualTo(SearchText.of(prefix, seg.content()));
      }
      assertThat(seg.contentHash()).hasSize(64);
      assertThat(seg.charCount()).isEqualTo(seg.content().length());
      assertThat(seg.title()).isNotBlank();
      assertThat(seg.titlePath()).isNotBlank();
    }
    assertThat(plan.get(0).isTable()).isTrue();
    assertThat(plan.get(plan.size() - 1).isTable()).isFalse();
    assertThat(plan.get(0).ordinal()).isEqualTo(0);
  }

  // ────────────────────────── 7 · 空输入 ──────────────────────────

  @Test
  void 空markdown返回空列表() {
    assertThat(plan("", 100, 0, 500)).isEmpty();
    assertThat(plan(null, 100, 0, 500)).isEmpty();
    assertThat(plan("   \n  ", 100, 0, 500)).isEmpty();
  }

  /** ★ 与 MarkdownChunker 同族：非法分段/重叠参数禁止静默纠正。 */
  @Test
  void 非法参数必须拒绝() {
    String md = "正文。";

    assertThatThrownBy(() -> plan(md, 0, 0, 500)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plan(md, 100, -1, 500)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plan(md, 100, 100, 500)).isInstanceOf(IllegalArgumentException.class);
  }
}
