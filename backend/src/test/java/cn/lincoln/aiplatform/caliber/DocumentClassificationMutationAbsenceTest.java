package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ★ <strong>文档密级「只能入库时定」的见证人</strong>（{@code #247}）—— 留缝的三件之一：一条能红的断言。
 *
 * <p>{@code #27} 的判据 ② 要求「升密一份文档 ⇒ 相关会话密级被重算」。这条判据今天<strong>无法被触发</strong>： 文档密级只在入库时校验上界（{@code
 * DocumentService} 的上传路径），没有任何变更入口 —— 也就是说这不是"重算忘了写"，而是"变更这件事本身还不存在"。
 *
 * <h3>★ 为什么用「缺席」当判据</h3>
 *
 * <p>一段"还没写"的代码不会让任何测试变红。把缺席本身钉成断言，是为了让 {@code #247} 的处置不被读成"已经做了"： 谁哪天加了 {@code
 * changeClassification} 一类的方法，这条就会红，提醒同一批里必须一起落 <b>四问</b>（分段/向量是否重算 · 会话水位同事务补偿 · 引用快照怎么标注 ·
 * 审计怎么留痕）—— 那四问写错了不会报错，只会悄悄留下"越权能看到"的窗口（同族教训见 {@code #108}）。
 *
 * <h3>★ 不做的事</h3>
 *
 * <p>本类不断言"应该有什么"，也不造一个空方法当接口位 —— 签名一旦写进代码就有人调， 而"能调但什么都没补偿"正是这族缺陷的形态。留缝的位置写在 {@code #247} 正文 （计划签名
 * {@code DocumentService#changeClassification(UUID, ClassificationLevel, String reason)}）与 {@code
 * 13} 册里。
 */
class DocumentClassificationMutationAbsenceTest {

  private static final Path DOCUMENT_SERVICE =
      Path.of(
          "src",
          "main",
          "java",
          "cn",
          "lincoln",
          "aiplatform",
          "knowledge",
          "application",
          "DocumentService.java");

  private static final Path DESIGN = Path.of("..", "docs", "系统设计方案", "13-密级与数据隔离设计.md");

  /** 任何「改密级」形状的方法名都算 —— 只认名字，因为签名一改就测不到「意图」。 */
  private static final Pattern MUTATION =
      Pattern.compile(
          "(?i)\\b(?:change|update|raise|lower|set|reclassify|regrade)\\w*Class\\w*\\s*\\(");

  @Test
  void documentServiceHasNoClassificationMutationEntryYet() {
    String source = read(DOCUMENT_SERVICE);
    List<String> hits = new ArrayList<>(List.of());
    Matcher matcher = MUTATION.matcher(source);
    while (matcher.find()) {
      hits.add(matcher.group());
    }

    assertThat(hits)
        .as(
            "★ 出现改密级的方法 ⇒ #247 的处置必须同批落地：分段/向量重算、会话水位同事务补偿、"
                + "引用快照标注、审计留痕（并能被 #27 判据 ② 的用例驱动）。只加方法不加补偿 = 静默留洞。")
        .isEmpty();
  }

  @Test
  void theFourQuestionsOfAClassificationChangeAreStillOnTheBooks() {
    String design = read(DESIGN);

    assertThat(design).as("密级变更的四问必须有出处，否则 #247 只是「想起来再说」").contains("密级");
    assertThat(design)
        .as("13 §二 的密级口径段必须仍在（文档密级上界由库 maxClassification 决定）")
        .contains("maxClassification");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("读不到：" + path.toAbsolutePath(), failure);
    }
  }
}
