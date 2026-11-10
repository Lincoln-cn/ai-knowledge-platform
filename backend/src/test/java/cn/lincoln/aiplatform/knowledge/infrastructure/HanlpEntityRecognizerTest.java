package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer.Recognized;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HanlpEntityRecognizer} 的判据。
 *
 * <p>★ 用例直接来自真语料实测的**错误形态**（见类的注释里的读数表），不是"跑一下看它出什么"。
 */
class HanlpEntityRecognizerTest {

  private final HanlpEntityRecognizer recognizer = HanlpEntityRecognizer.withDefaults();

  @Test
  @DisplayName("后缀是机构的词被抽出来")
  void extractsOrgBySuffix() {
    List<Recognized> got = recognizer.recognize("财务资产部是公司固定资产的归口管理部门。");

    assertThat(got)
        .filteredOn(r -> r.type().equals("ORG"))
        .extracting(Recognized::name)
        .contains("财务资产部");
  }

  @Test
  @DisplayName("★ HanLP 自己的 nt 会把「生产设备」当机构，后缀判定必须挡住它")
  void rejectsNonOrgThatHanlpLabelsAsNt() {
    // 实测：HanLP 的 enableOrganizationRecognize 给「生产设备/nt」「对生产设备/nt」
    List<Recognized> got = recognizer.recognize("对生产设备的购置结果进行复核确认。");

    assertThat(got)
        .filteredOn(r -> r.type().equals("ORG"))
        .extracting(Recognized::name)
        .noneMatch(name -> name.contains("生产设备"));
  }

  @Test
  @DisplayName("★ 人名一律不抽——HanLP 会把「应于」「须将」「应在」标成 nr")
  void neverReturnsPerson() {
    List<Recognized> got = recognizer.recognize("应于每年三月底前完成；须将结果报送；应在系统中登记。");

    assertThat(got).noneMatch(r -> r.type().equals("PERSON"));
    assertThat(recognizer.supportedTypes()).doesNotContain("PERSON");
  }

  @Test
  @DisplayName("书名号里的制度名抽成 DOC，置信度高于规则判定的机构")
  void extractsDocumentByBookTitle() {
    List<Recognized> got = recognizer.recognize("依据《固定资产管理办法》第十七条，本办法自发布之日起施行。");

    assertThat(got)
        .filteredOn(r -> r.type().equals("DOC"))
        .extracting(Recognized::name)
        .containsExactly("固定资产管理办法");
    assertThat(got)
        .filteredOn(r -> r.type().equals("DOC"))
        .first()
        .extracting(Recognized::confidence)
        .isEqualTo(0.95);
  }

  @Test
  @DisplayName("关系返回空——这不是「没有关系」，是「这一层抽不出」")
  void relationsAreEmptyByContract() {
    assertThat(recognizer.relations("财务资产部负责固定资产的购置。")).isEmpty();
  }

  @Test
  @DisplayName("后缀表为空 ⇒ 构造失败，不许静默给空结果")
  void emptySuffixesFailFast() {
    assertThatThrownBy(() -> new HanlpEntityRecognizer(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("机构后缀为空");
  }

  @Test
  @DisplayName("空文本安全")
  void blankIsSafe() {
    assertThat(recognizer.recognize(null)).isEmpty();
    assertThat(recognizer.recognize("   ")).isEmpty();
  }

  @Test
  @DisplayName("实现名带版本，便于日志里分清这次是谁跑的")
  void hasName() {
    assertThat(recognizer.name()).isEqualTo("hanlp-1.8.6");
    assertThat(recognizer.supportedTypes()).containsExactly("ORG", "DOC");
  }
}
