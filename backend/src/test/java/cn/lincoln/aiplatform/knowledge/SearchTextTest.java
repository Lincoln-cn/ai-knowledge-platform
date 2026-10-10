package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.SearchText;
import org.junit.jupiter.api.Test;

/**
 * 结构化前缀（第 7 周 · 0.2.2 批 1）—— {@code 12 §4.2}。
 *
 * <p>★ 这是本批的<strong>召回率主武器</strong>：零 LLM 的确定性前缀（Meta-Prefix 实证：失败率 30% → 3.3%， {@code 12 §4.1}）。
 *
 * <p>三条纪律（{@code 12 §4.2}）：
 *
 * <ol>
 *   <li><strong>前缀绝不进 {@code content}</strong> —— 引用抽屉展示原文，不许带方括号前缀
 *   <li><strong>前缀里的标签也是可过滤的列</strong> —— 前缀给向量/全文看，过滤走 SQL WHERE
 *   <li><strong>改了前缀组成 ⇒ contentHash 必须变</strong>（{@code SEG-I7}）—— 否则"改了部门标签但向量没更新"⇒ 静默不一致
 * </ol>
 *
 * <p>前缀形态（{@code 12 §4.2}）：<strong>文种·部门·标题｜章节路径</strong>（L1 文档级标识 + L2 章节路径）。 ★ 其中「部门」自 {@code
 * T300} 起是该部门的<strong>稳定 {@code code}</strong>（{@code 07 §11.2}）—— 可变、可重名的展示名不许进命中材料，所以本类的夹具值一律写成
 * code。
 */
class SearchTextTest {

  // ────────────────────────── 1 · 前缀构成 ──────────────────────────

  /** ★ 前缀三层（{@code 12 §4.2}）：L1 文档级标识（文种·部门code·标题）贡献最大， L2 章节路径便宜但有效。分隔符固定，避免各处拼法不一。 */
  @Test
  void 前缀按文种部门标题章节路径拼装() {
    String prefix = SearchText.prefix("报销规定", "FINANCE", "差旅费报销管理办法", "第三章 差旅费 > 3.2 住宿标准");

    assertThat(prefix)
        .as("前缀形态钉死：文种·部门code·标题｜章节路径（12 §4.2 + 07 §11.2：部门段是稳定 code，不是展示名）")
        .isEqualTo("报销规定·FINANCE·差旅费报销管理办法｜第三章 差旅费 > 3.2 住宿标准");
  }

  /**
   * ★ 前缀里的部门段是<strong>传入的那个标识</strong>，本方法不做任何"翻译" —— 这是刻意的：{@code SearchText} 不认识组织树，所以"放 code 还是放
   * name"只能在**调用点**收口 （{@code OrgCodeResolver} 一处）。这条断言把"它是纯函数、不会偷偷改材料"钉住， 而"调用点给的是不是 name"由 {@code
   * DocumentOrgShapeTest} 的端到端哨兵守（{@code 07 §11.4} 判据 1/2）。
   */
  @Test
  void 前缀是纯函数_不翻译也不改写传入的部门标识() {
    assertThat(SearchText.prefix("制度", "PURCHASING", "采购管理办法", "第一章"))
        .as("传入什么就是什么（部门段不许在这里被换成名字/path）")
        .isEqualTo("制度·PURCHASING·采购管理办法｜第一章")
        .doesNotContain("采购部")
        .doesNotContain("/ROOT/");
  }

  /**
   * ★ {@code search_text = 前缀 + 正文}（{@code 12 §4.2}）。 同时断言：<strong>前缀绝不进 content</strong> ——
   * content 是用户看到的原文，搜索文本才是拼了前缀的那一份。
   */
  @Test
  void searchText是前缀加正文_且正文不被污染() {
    String body = "一线城市住宿标准为每人每天 500 元。";
    String prefix = SearchText.prefix("制度", "PURCHASING", "差旅规定", "第三章");

    String searchText = SearchText.of(prefix, body);

    assertThat(searchText)
        .as("search_text = 前缀 + 正文（12 §4.2 一列两用）")
        .isEqualTo(prefix + SearchText.SEPARATOR + body);
    assertThat(body).as("纪律 1：前缀绝不进 content —— 否则引用抽屉里出现方括号前缀").doesNotContain("制度·");
    assertThat(SearchText.contentOf(searchText, prefix))
        .as("从 searchText 能无损还原正文（前缀与正文可分离）")
        .isEqualTo(body);
  }

  /**
   * ★ 前缀长度上限（{@code 12 §10.2} 风险 1：前缀挤占向量预算 ⇒ 设长度上限，如 ≤80 字）。 超长前缀<strong>截到上限</strong>，而不是拒绝整篇 ——
   * 标题再长也不该让入库失败。
   */
  @Test
  void 超长前缀截断到上限而非拒绝() {
    String longTitle = "标题".repeat(60); // 120 字，远超上限
    String prefix = SearchText.prefix("制度", "PURCHASING", longTitle, "A > B > C > D > E > F");

    assertThat(prefix.length())
        .as("前缀必须截到 MAX_PREFIX_LENGTH（12 §10.2 风险 1）")
        .isLessThanOrEqualTo(SearchText.MAX_PREFIX_LENGTH);
    assertThat(prefix).as("截断保留开头（L1 文档级标识最重要），丢掉尾部").startsWith("制度·PURCHASING·标题");
  }

  // ────────────────────────── 2 · contentHash 必须含前缀（SEG-I7） ──────────────────────────

  /**
   * ★ {@code SEG-I7}：前缀的任何组成部分（文种/部门/标题/章节路径）变更 ⇒ {@code contentHash} 必须变。
   *
   * <p>为什么这条是<strong>不变量</strong>而不是建议：contentHash 用来判断"要不要重新向量化"（{@code 02 §2.3}）。 若 hash 不含前缀，
   * 改了部门标签后 hash 不变 ⇒ 向量不重算 ⇒ <strong>"标签改了但向量还是旧的"的静默不一致</strong>（{@code 12 §4.2} 纪律 3）。
   */
  @Test
  void 改前缀任一组成_contentHash必须变() {
    String body = "一线城市住宿标准为每人每天 500 元。";

    String base = SearchText.contentHash("制度", "PURCHASING", "差旅规定", "第三章", body);

    assertThat(SearchText.contentHash("合同", "PURCHASING", "差旅规定", "第三章", body))
        .as("改文种 ⇒ hash 必须变（SEG-I7）")
        .isNotEqualTo(base);
    assertThat(SearchText.contentHash("制度", "HR", "差旅规定", "第三章", body))
        .as("改部门 ⇒ hash 必须变（SEG-I7）")
        .isNotEqualTo(base);
    assertThat(SearchText.contentHash("制度", "PURCHASING", "住宿规定", "第三章", body))
        .as("改标题 ⇒ hash 必须变（SEG-I7）")
        .isNotEqualTo(base);
    assertThat(SearchText.contentHash("制度", "PURCHASING", "差旅规定", "第四章", body))
        .as("改章节路径 ⇒ hash 必须变（SEG-I7）")
        .isNotEqualTo(base);
    assertThat(SearchText.contentHash("制度", "PURCHASING", "差旅规定", "第三章", body + "补充一句。"))
        .as("改正文 ⇒ hash 也必须变（本来就要重向量）")
        .isNotEqualTo(base);
  }

  /** ★ 反例：同样的输入必须得到<strong>同样的 hash</strong>（幂等）。 hash 不稳定会让每次入库都触发重向量。 */
  @Test
  void 相同输入contentHash幂等() {
    String a = SearchText.contentHash("制度", "PURCHASING", "差旅规定", "第三章", "正文内容。");
    String b = SearchText.contentHash("制度", "PURCHASING", "差旅规定", "第三章", "正文内容。");

    assertThat(a).isEqualTo(b).hasSize(64);
  }

  /** ★ 前缀截断后 hash 仍要含<strong>完整的前缀组成</strong>（而不是截断后的字符串） —— 否则改了被截掉的那半截标题，hash 不变，违反 SEG-I7。 */
  @Test
  void 前缀被截断时hash仍含完整组成() {
    String body = "正文。";
    String longTitle1 = "标题甲".repeat(40);
    String longTitle2 = "标题乙".repeat(40);

    String prefix1 = SearchText.prefix("制度", "PURCHASING", longTitle1, "P");
    String prefix2 = SearchText.prefix("制度", "PURCHASING", longTitle2, "P");

    assertThat(prefix1)
        .as("夹具前提：两个前缀都被截断，截断后可能相同或不同")
        .hasSizeLessThanOrEqualTo(SearchText.MAX_PREFIX_LENGTH);

    String hash1 = SearchText.contentHash("制度", "PURCHASING", longTitle1, "P", body);
    String hash2 = SearchText.contentHash("制度", "PURCHASING", longTitle2, "P", body);

    assertThat(hash1).as("SEG-I7：标题变了（哪怕被前缀截断吃掉）⇒ hash 必须变").isNotEqualTo(hash2);
  }
}
