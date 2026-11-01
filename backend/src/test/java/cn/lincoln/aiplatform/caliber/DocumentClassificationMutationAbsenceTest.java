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
 * ★ <strong>文档密级变更入口与它的三个配套</strong>（{@code #247}）—— 从「缺席」断言改成「配套」断言。
 *
 * <p>{@code #27} 的判据 ② 要求「升密一份文档 ⇒ 相关会话密级被重算」。{@code #247} 之前这条判据无法被触发，
 * 因为变更入口本身不存在；这条断言当时钉的就是那个缺席（{@code #252}）。
 *
 * <h3>★ 为什么改成正面断言（2026-11-01 合并 main 时）</h3>
 *
 * <p>{@code #247} 落地后（{@code bf3fe29}），「断言没有入口」只剩一种读数：永远为假的红。而"把闸门改成绿的" 与"闸门坏了"读数完全一样 ——
 * 所以这里换个方向断言<b>配套</b>：入口、分段重打、会话水位补偿、审计留痕， 少一件就红。这四件写错了不会报错，只会悄悄留下"越权能看到"的窗口（同族教训见 {@code #108}）。
 *
 * <h3>★ 不做的事</h3>
 *
 * <p>本类不测行为（行为在 {@code DocumentClassificationTest} 4/4 与 {@code AuditTrailTest} 里），
 * 只做源码级守卫：保证那四件在同一个类里成对存在，防止将来有人重构时删掉其中一件还自以为没事。
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

  /**
   * ★ <strong>变更入口必须在，且三个配套与它同处一个类</strong>（{@code #247} 已交付；main 侧 {@code #252} 的「缺席」断言于
   * 2026-11-01 合并后改写）。
   *
   * <p>原来钉的是「还没有变更入口」。入口落地之后，那条断言变成了一条永远为假的红 —— 而"把闸门改成绿的"与"闸门坏了"读数一样，
   * 所以方向反过来：<b>入口存在时，配套少一件就红</b>。
   *
   * <p>三件可核对：分段跟着重打（V22 冗余密级列）· 升密同事务抬会话水位（{@code 13 §2.2}）· 审计留痕（{@code 13 §七}）。 第四件（引用快照标注）在
   * {@code #247} 收尾里定成"不追溯"：{@code SourceRef.classification} 是快照、落库后永不改写， 口径见 {@code 13 §2.4.1} ——
   * 那是设计决定，不是漏做。
   */
  @Test
  void classificationMutationCarriesItsCompanions() {
    String source = read(DOCUMENT_SERVICE);
    List<String> hits = new ArrayList<>(List.of());
    Matcher matcher = MUTATION.matcher(source);
    while (matcher.find()) {
      hits.add(matcher.group());
    }

    assertThat(hits).as("★ #247 已交付：变更入口必须还在 —— 删掉它是回归，不是回到「留缝」").isNotEmpty();
    assertThat(source)
        .as("★ 分段必须跟着重打：V22 把密级冗余进了 segment，只改文档行 = 升密看着生效、" + "低 clearance 用户照样检索得到")
        .contains("reclassify(");
    assertThat(source)
        .as("★ 升密必须同事务抬会话水位：13 §2.2 的不变量一旦破，会话会按旧档位继续筛")
        .contains("raiseForDocument(");
    assertThat(source)
        .as("★ 变更必须留痕：查不出谁改的密级 = 越权之后无法追")
        .contains("AuditAction.DOCUMENT_RECLASSIFY");
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
