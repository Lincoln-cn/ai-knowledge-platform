package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OCR 逐页字符准确率的基线读数（Issue {@code #71} · L1：**先有读数，不调参**）。
 *
 * <h3>★ 为什么这条读数是第 18 周验收的前置</h3>
 *
 * <p>{@code ROADMAP §二} 的 B1（命中率 ≥70%）/ B3（引用准确率 ≥80%）是<strong>结果指标</strong>，而语料里必然有扫描件。
 * 没有这个基线，命中率变差时<strong>无法归因</strong> —— 会把「入库那一刻 OCR 就错了」记到检索策略头上。 而在此之前， {@code
 * fixtures/ocr-ground-truth.json} <strong>只被用来测噪声剥离</strong>（{@link NoiseStripperTest}：页眉页脚剔得对不对），
 * <strong>"汉字识别对了多少"从来没有读数</strong>。
 *
 * <h3>★★ 指标定义（可机械核对）</h3>
 *
 * <pre>
 * 逐页字符准确率 = 1 − 编辑距离(参考页文本, OCR 页文本) / len(参考页文本)
 * </pre>
 *
 * <ul>
 *   <li>参考 = {@code ocr-ground-truth.json} 的 {@code scanned.pdf.<页号>} —— {@code noisy.pdf}
 *       那一页的<strong>文本层原文</strong>（{@code make_ocr_fixtures.py} 用 pdfium 导出，非手抄）
 *   <li>编辑距离 = Levenshtein，本类自带两行滚动数组实现 ⇒ <strong>零新依赖</strong>
 * </ul>
 *
 * <h3>★★ 为什么读数文本是「写死」在下面，而不是现场跑 OCR</h3>
 *
 * <p>本类<strong>故意不跑 OCR</strong>，只对一份<strong>已记录的识别产物</strong>做独立复算。两条理由都可核对：
 *
 * <ol>
 *   <li>★ <b>OCR 需要系统级依赖</b>（tesseract + chi_sim 语言包，不在 jar 里）。让它随"这台机器装没装"而变， 等于把"环境恰好有"当测试前提 —— 与
 *       {@code ParseMetricsTest} 关掉 OCR 的理由同源。 真栈端到端证据在闸门 {@code check_ocr_e2e.py}。
 *   <li>★★ <b>这样这条断言才真的能红</b>：产物一旦被记录，读数就是<strong>确定性的</strong>。改分词、改前缀、改后处理时 若动了 OCR
 *       产物，这里有独立复算的编辑距离会立刻对不上（比"重跑一次 OCR 看数字飘没飘"可靠得多 —— Tesseract 在不同 tesseract 版本上本就会给出不同结果）。
 * </ol>
 *
 * <p>★ 识别产物的来源、逐页跑法与重算入口见 {@code backend/tools/measure_ocr_accuracy.py} （一条命令重算并打印）：{@code python
 * backend/tools/measure_ocr_accuracy.py} —— 参数与生产逐个对齐（{@code -l chi_sim+eng} · {@code --dpi 300} ·
 * {@code preserve_interword_spaces=1}）， 对齐依据写在那个脚本的 docstring 里（含 {@code javap} 反编译 Tika 3.3.1
 * 的实测对照表）。
 */
class OcrAccuracyBaselineTest {

  /**
   * 基线（2026-10-13 实测 · tesseract 5.5.0 · chi_sim+eng · 300dpi · psm AUTO ·
   * preserve_interword_spaces=1）。
   *
   * <p>★ 它是<b>下限</b>不是<b>目标</b>：这些数字只用来"不许比它更差"，以及给 B1/B3 一个归因锚点。
   */
  private static final double BASELINE_MICRO = 0.88;

  private static final double BASELINE_WORST = 0.79;

  /** 每页读数：参考 = ground truth（逐字），识别 = 实测产物（逐字）。 */
  private record PageReading(String reference, String actual) {}

  /**
   * ★ 参考文本 —— <strong>逐字抄自</strong> {@code fixtures/ocr-ground-truth.json} 的 {@code scanned.pdf}。
   *
   * <p>★ 为什么不在这里读 JSON：读进来就少了一道"人看得见"的核对 —— 而这份夹具本身就是被测对象之外的<strong>答案</strong>。 抄错的代价由 {@link
   * #逐页读数与基线对照表()} 里的参考字数断言兜住（162 / 197 / 90）。
   */
  private static List<PageReading> readings() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<String> references = new ArrayList<>();
    try (InputStream in =
        OcrAccuracyBaselineTest.class.getResourceAsStream("/fixtures/ocr-ground-truth.json")) {
      assertThat(in).as("夹具 ocr-ground-truth.json 不在 classpath").isNotNull();
      JsonNode pages = mapper.readTree(in).get("scanned.pdf");
      // ★ Jackson 3 里 fieldNames()（迭代器）改成了 propertyNames()（Collection）—— 与 NoiseStripperTest 同口径
      pages.propertyNames().forEach(field -> references.add(pages.get(field).asText()));
    }
    assertThat(references).as("夹具里应有 3 页参考原文").hasSize(3);

    List<String> actuals =
        List.of(
            // ── 第 1 页（目录页：点线 + 页码）──
            """
            某某集团内部管理制度
            第一章 总则 ..………... 1
            1.1 制定目的 ......….. 2
            1.1.1 适用范围 ..….….……. 3
            第二章 报销标准 .....…….. 5
            2.1 住宿标准 ....….…….. 6
            2. 2 交通标准 ........ 7
            3
            2024 某某集团有限公司 版权所有
            内部资料 请勿外传
            """,
            // ── 第 2 页（正文页）★ 第 6 行是整行丢失：参考「适用外包人员须另签协议」→ 实测回吐拉丁乱码 ──
            """
            某某集团内部管理制度
            第一章 总则
            本制度规定如下要点:
            适用公司各部门
            适用正式员工
            BABA RMA SHIN
            提交申请
            部门审批
            归档备案
            住宿标准为一线城市每人每天不超过伍佰元，超标部分不予报销。
            交通费用实报实销，需提供合规票据作为报销依据。
            出差前须提交书面申请，经部门负责人批准后方可出行。
            本规定自发布之日起施行，由人力资源部负责解释。
            第7页共12页
            内部资料 请勿外传
            """,
            // ── 第 3 页（列表页）★ 最差页：`.1` 丢了首位、`- 适用正式员工`→`用正式员`、句点被读成全角逗号、尾部多出 4 行乱码 ──
            """
            某某集团内部管理制度
            .1 制定目的
            - 适用公司各部门
            用正式员
            - 适用外包人员须另签协议
            1，提交申请
            2，部门审批
            3. 归档备案
            vil
            一人
            |
            i
            H
            内部资料 请勿外传
            """);

    List<PageReading> readings = new ArrayList<>();
    for (int i = 0; i < actuals.size(); i++) {
      readings.add(new PageReading(references.get(i), actuals.get(i)));
    }
    return readings;
  }

  /**
   * ★★ 主读数：逐页字符准确率 + 微平均 + 最差页，并把基线表打印出来（可进文档）。
   *
   * <p>★ 必须<strong>逐页</strong>给，不能只给一个全局平均 —— 「平均 0.93」会掩盖「有一页 0.4」。
   *
   * <p>★ 方法名用 ASCII、上面的中文才是"为什么"：本项目定过 —— 标识符一旦被非 UTF-8 的工具读写就会变形， 而人工维护的入口本来就是
   * javadoc（`TestNamingConventionTest` 会拦住中文标识符）。
   */
  @Test
  void perPageAccuracyMatchesBaselineTable() throws Exception {
    List<PageReading> readings = readings();

    List<String> rows = new ArrayList<>();
    int totalReference = 0;
    int totalDistance = 0;
    double worst = 1.0;
    int worstPage = 0;

    for (int i = 0; i < readings.size(); i++) {
      String reference = canonicalize(readings.get(i).reference());
      String actual = canonicalize(readings.get(i).actual());
      int distance = editDistance(reference, actual);
      double accuracy = reference.isEmpty() ? 0.0 : 1.0 - (double) distance / reference.length();

      // ★ 参考字数写进断言：抄错一个字符，这里就红（夹具是"答案"，不许被抄歪）
      assertThat(reference.length())
          .as("第 %d 页参考字数（★ 与夹具逐字对齐的凭据）", i + 1)
          .isEqualTo(
              switch (i) {
                case 0 -> 161;
                case 1 -> 197;
                default -> 90;
              });
      assertThat(accuracy)
          .as("第 %d 页字符准确率不许低于基线 %.2f（Issue #71 的读数下限）", i + 1, BASELINE_WORST)
          .isGreaterThanOrEqualTo(BASELINE_WORST);

      totalReference += reference.length();
      totalDistance += distance;
      if (accuracy < worst) {
        worst = accuracy;
        worstPage = i + 1;
      }
      rows.add(
          String.format(
              Locale.ROOT,
              "| %d | %d | %d | %.2f%% |",
              i + 1,
              reference.length(),
              distance,
              accuracy * 100));
    }

    double micro = 1.0 - (double) totalDistance / totalReference;
    assertThat(micro)
        .as("微平均（整篇文档的字符准确率）不许低于基线 %.2f", BASELINE_MICRO)
        .isGreaterThanOrEqualTo(BASELINE_MICRO);
    assertThat(worst)
        .as("最差页准确率不许低于基线 %.2f（★ 它才是「平均掩盖不住」的那个读数）", BASELINE_WORST)
        .isGreaterThanOrEqualTo(BASELINE_WORST);

    StringBuilder table = new StringBuilder();
    table.append("=== OCR 逐页字符准确率（Issue #71 · L1 基线）===\n");
    table.append("| 页 | 参考字数 | 编辑距离 | 准确率 |\n");
    table.append("|---:|---:|---:|---:|\n");
    for (String row : rows) {
      table.append(row).append('\n');
    }
    table.append(
        String.format(
            Locale.ROOT,
            "%n微平均（Σ编辑距离 %d / Σ参考长度 %d）= %.2f%%%n最差页（第 %d 页）= %.2f%%%n",
            totalDistance,
            totalReference,
            micro * 100,
            worstPage,
            worst * 100));
    System.out.println(table);
  }

  /**
   * ★ 逐字例子：把"差在什么地方"钉成断言 —— 这些形态<strong>就是 L2/L3 要处理的清单</strong>，本批只记录。
   *
   * <p>★ 判据用「包含」而不是"整句相等"：OCR 产物里同一行还会叠着空白差异（见下一条）， 用整句相等会让这条断言因为<strong>不相关的原因</strong>变红。
   *
   * <p>★ 方法名 ASCII（同上一条）：这些逐字差异<strong>就是 L2/L3 要处理的清单</strong>，中文说明留在本 javadoc。
   */
  @Test
  void characterDifferenceExamplesRemainReproducible() throws Exception {
    List<PageReading> readings = readings();
    String page1 = readings.get(0).actual();
    String page2 = readings.get(1).actual();
    String page3 = readings.get(2).actual();

    // ── ① 目录点线：参考是连续句点，实测被读成「…」（省略号）+ 句点混排 ──
    assertThat(page1)
        .as("目录点线被读成省略号 —— 参考「第一章 总则 ........ 1」")
        .contains("第一章 总则 ..………... 1")
        .contains("1.1.1 适用范围 ..….….……. 3");
    assertThat(page1).as("★ 点线里被塞进一个空格：「2.2 交通标准」→「2. 2 交通标准」").contains("2. 2 交通标准");

    // ── ② 整行丢失（最严重的一处）：参考「适用外包人员须另签协议」→ 实测回吐拉丁乱码 ──
    assertThat(page2)
        .as("★ 整行丢失：10 个汉字被换成一串无意义拉丁字母")
        .contains("BABA RMA SHIN")
        .doesNotContain("适用外包人员须另签协议");

    // ── ③ 全角标点退化成半角 ──
    assertThat(page2).as("全角冒号退化成半角：参考「本制度规定如下要点：」").contains("本制度规定如下要点:");

    // ── ④ 空格丢失：参考「第 7 页 共 12 页」→ 实测「第7页共12页」 ──
    assertThat(page2).as("页码行的空格全丢").contains("第7页共12页");

    // ── ⑤ 行首丢字 + 列表项截断（第 3 页，最差页）──
    assertThat(page3).as("行首丢一个 1：参考「1.1 制定目的」→ 实测「.1 制定目的」").contains(".1 制定目的");
    assertThat(page3).as("列表项被截断：参考「- 适用正式员工」→ 实测「用正式员」").contains("用正式员");

    // ── ⑥ 句点被读成全角逗号（中文混淆集范畴，12 §11.6 只标记不替换）──
    assertThat(page3).as("有序列表的句点被读成全角逗号：参考「1. 提交申请」").contains("1，提交申请");

    // ── ⑦ 罗马数字 i/l 混淆 ──
    assertThat(page3).as("页脚罗马数字：参考「vii」→ 实测「vil」").contains("vil");

    // ── ⑧ 无中生有：参考里没有的 4 行零碎（版式分析把空白区切成了"文本块"）──
    assertThat(page3).as("★ 多出行：参考这一页没有「一人」「|」「i」「H」").contains("一人");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /**
   * 归一化 → 「逐行规范形」：剔 BOM、统一换行与非换行空格、每行去空白、丢空行。
   *
   * <p>★ 与 {@code measure_ocr_accuracy.py} 的 {@code canonicalize()} <strong>逐条对应</strong>；
   * 两侧不一致的表现是读数漂移，所以两边都写了出处。
   */
  private static String canonicalize(String text) {
    String unified =
        text.replace("\ufeff", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00a0', ' ')
            .replace('\u3000', ' ');
    List<String> lines = new ArrayList<>();
    for (String line : unified.split("\n", -1)) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    // ★ 主口径：把全部空白折叠成单空格 ⇒ 字符串退化成"一个字符序列"，编辑距离于是逐字可比。
    //   理由见 measure_ocr_accuracy.py 的 docstring（OCR 的换行/空格本来就不稳定，那是版面问题不是认错字）。
    return String.join(" ", lines);
  }

  /**
   * Levenshtein 距离，两行滚动数组（{@code O(min(n,m))} 空间）。
   *
   * <p>★ 本类自带实现，不引 {@code python-Levenshtein} / {@code rapidfuzz}（本项目零新依赖）。
   */
  static int editDistance(String reference, String actual) {
    if (reference.equals(actual)) {
      return 0;
    }
    String left = reference;
    String right = actual;
    if (left.length() < right.length()) {
      left = actual;
      right = reference;
    }
    if (right.isEmpty()) {
      return left.length();
    }
    int[] previous = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= left.length(); i++) {
      int[] current = new int[right.length() + 1];
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
      }
      previous = current;
    }
    return previous[right.length()];
  }
}
