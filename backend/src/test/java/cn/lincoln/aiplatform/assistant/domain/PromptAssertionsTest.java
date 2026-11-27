package cn.lincoln.aiplatform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第 1 层机械断言（ROADMAP §11.63.2）。
 *
 * <p>★ 这一层不判断"答得好不好"，只回答"这句话里有没有它必须有 / 不该有的东西"。 所以这里的断言钉的是**口径**而不是聪明：读数是给人复查用的，判错一次就没人信第二次。
 */
@DisplayName("第 1 层机械断言")
class PromptAssertionsTest {

  private static PromptAssertions.Def def(PromptAssertions.Kind kind, String value) {
    return new PromptAssertions.Def(UUID.randomUUID().toString(), "", kind, value);
  }

  @Test
  @DisplayName("CONTAINS：答里有这个词就过，没有就不过")
  void containsLooksAtTheAnswerText() {
    var hit =
        PromptAssertions.check(
            "术语解释：RAG 指检索增强生成", List.of(def(PromptAssertions.Kind.CONTAINS, "检索增强")));
    assertThat(hit).singleElement().matches(r -> r.passed());

    var miss =
        PromptAssertions.check("RAG 是一种架构", List.of(def(PromptAssertions.Kind.CONTAINS, "检索增强")));
    assertThat(miss).singleElement().matches(r -> !r.passed());
  }

  @Test
  @DisplayName("NOT_CONTAINS：空答案不算违规 —— 没说话不等于说了不该说的")
  void notContainsPassesOnAnEmptyAnswer() {
    var empty =
        PromptAssertions.check("", List.of(def(PromptAssertions.Kind.NOT_CONTAINS, "内部编号")));
    assertThat(empty).singleElement().matches(r -> r.passed());

    var leaked =
        PromptAssertions.check(
            "见内部编号 A-12", List.of(def(PromptAssertions.Kind.NOT_CONTAINS, "内部编号")));
    assertThat(leaked).singleElement().matches(r -> !r.passed());
  }

  @Test
  @DisplayName("REGEX：写错正则报「正则写错了」，不说成答案不合格")
  void brokenRegexIsADefinitionErrorNotAnAnswerError() {
    var bad = PromptAssertions.check("任意答案", List.of(def(PromptAssertions.Kind.REGEX, "(未闭合")));
    assertThat(bad).singleElement().matches(r -> !r.passed() && r.detail().startsWith("正则写错了"));

    var good =
        PromptAssertions.check("编号 A-12", List.of(def(PromptAssertions.Kind.REGEX, "A-\\d+")));
    assertThat(good).singleElement().matches(r -> r.passed());
  }

  @Test
  @DisplayName("MAX_CHARS / MIN_CHARS：空答案在「不能太长」上是通过的")
  void lengthBoundsTreatAnEmptyAnswerAsShort() {
    var tooLong =
        PromptAssertions.check("一二三四五六", List.of(def(PromptAssertions.Kind.MAX_CHARS, "3")));
    assertThat(tooLong).singleElement().matches(r -> !r.passed());

    var emptyIsShort =
        PromptAssertions.check("", List.of(def(PromptAssertions.Kind.MAX_CHARS, "3")));
    assertThat(emptyIsShort).singleElement().matches(r -> r.passed());

    var tooShort = PromptAssertions.check("短", List.of(def(PromptAssertions.Kind.MIN_CHARS, "5")));
    assertThat(tooShort).singleElement().matches(r -> !r.passed());

    var nonNumeric =
        PromptAssertions.check("任意", List.of(def(PromptAssertions.Kind.MAX_CHARS, "很多")));
    assertThat(nonNumeric)
        .singleElement()
        .matches(r -> !r.passed() && r.detail().startsWith("上限写错了"));
  }

  @Test
  @DisplayName("LIST：至少两项才算列表；一整段话不算")
  void listNeedsAtLeastTwoItems() {
    var twoItems =
        PromptAssertions.check(
            "可以这样做：\n1. 先分段\n2. 再向量化", List.of(def(PromptAssertions.Kind.LIST, "")));
    assertThat(twoItems).singleElement().matches(r -> r.passed());

    var paragraph =
        PromptAssertions.check("先分段再向量化即可", List.of(def(PromptAssertions.Kind.LIST, "")));
    assertThat(paragraph).singleElement().matches(r -> !r.passed());
  }

  @Test
  @DisplayName("UNCERTAIN：说「没有提到」算过；给了一个确定答案算不过")
  void uncertainExpectsTheModelToAdmitItDoesNotKnow() {
    var honest =
        PromptAssertions.check("资料未涉及这一点。", List.of(def(PromptAssertions.Kind.UNCERTAIN, "")));
    assertThat(honest).singleElement().matches(r -> r.passed());

    var confident =
        PromptAssertions.check("答案是 42。", List.of(def(PromptAssertions.Kind.UNCERTAIN, "")));
    assertThat(confident).singleElement().matches(r -> !r.passed());
  }

  @Test
  @DisplayName("没设断言就返回空结果 —— 不许伪造「全过」")
  void noAssertionsMeansNoResults() {
    assertThat(PromptAssertions.check("任意答案", List.of())).isEmpty();
    assertThat(PromptAssertions.check("任意答案", null)).isEmpty();
    assertThat(PromptAssertions.passedCount(List.of())).isZero();
    assertThat(PromptAssertions.failedCount(List.of())).isZero();
  }

  @Test
  @DisplayName("校验：kind 缺失、value 为空、条数超限都要在保存时就拒绝")
  void validateRejectsDefinitionsThatCanNeverBeJudged() {
    assertThatThrownBy(
            () ->
                PromptAssertions.validate(
                    new PromptAssertions.Def(UUID.randomUUID().toString(), "", null, "x"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("第 1 条断言");

    assertThatThrownBy(
            () -> PromptAssertions.validate(def(PromptAssertions.Kind.CONTAINS, "  "), 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("第 2 条断言");

    // 「要分点输出」不需要参数 —— 空 value 是合法的，别把它当成写漏了
    assertThatCode(
            () ->
                PromptAssertions.validate(
                    new PromptAssertions.Def(
                        UUID.randomUUID().toString(), "", PromptAssertions.Kind.LIST, ""),
                    0))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("JSON 来回一趟不变形（库里的列就是这两串）")
  void jsonRoundTripsBothWays() {
    var defs =
        List.of(
            new PromptAssertions.Def(
                UUID.randomUUID().toString(), "要说清出处", PromptAssertions.Kind.CONTAINS, "《"));
    var back = PromptAssertions.parseDefs(PromptAssertions.writeDefs(defs));
    assertThat(back).hasSize(1);
    assertThat(back.get(0).label()).isEqualTo("要说清出处");
    assertThat(back.get(0).kind()).isEqualTo(PromptAssertions.Kind.CONTAINS);

    var results = PromptAssertions.check("见《安全生产法》", defs);
    var backResults = PromptAssertions.parseResults(PromptAssertions.writeResults(results));
    assertThat(backResults).singleElement().matches(r -> r.passed());
  }

  @Test
  @DisplayName("标签留空时按类型给一句人话（读数要能直接读懂）")
  void blankLabelsFallBackToSomethingReadable() {
    assertThat(PromptAssertions.labelOr(def(PromptAssertions.Kind.CONTAINS, "检索增强")))
        .contains("检索增强");
    assertThat(PromptAssertions.labelOr(def(PromptAssertions.Kind.LIST, ""))).isNotBlank();
  }
}
