package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer.Recognized;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer.Relation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HanlpEntityRecognizer} 的判据。
 *
 * <p>★ 用例直接来自真语料实测的**错误形态**（见类的注释里的读数表），不是「跑一下看它出什么」。
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
  @DisplayName("★ 人名靠职务词后缀判定——HanLP 自己会把「应于」「须将」「应在」标成 nr")
  void neverReturnsPerson() {
    // ★★ 2026-11-11 加回 PERSON 之后，这条用例守的东西变了但不该被删：
    //   HanLP 的 nr 会把这三个介词短语当人名，规则层的职务词后缀不会（它们后面没有职务词）。
    //   所以断言从「supportedTypes 不含 PERSON」改成「没有职务词时抽不出人名」。
    List<Recognized> got = recognizer.recognize("应于每年三月底前完成；须将结果报送；应在系统中登记。");

    assertThat(got).noneMatch(r -> r.type().equals("PERSON"));
    assertThat(recognizer.supportedTypes()).contains("PERSON");
  }

  @Test
  @DisplayName("★ 有职务词后缀时才抽 PERSON（名字 + 经理/主任/部长 …）")
  void personNeedsTitleSuffix() {
    List<Recognized> got = recognizer.recognize("该申请由张伟经理审批，经李静主任复核后归档。");

    assertThat(got)
        .filteredOn(r -> r.type().equals("PERSON"))
        .extracting(Recognized::name)
        .as("有职务词后缀时必须抽到人；具体切到哪几个字由 {2,4}+职务词 的贪婪边界决定")
        .anyMatch(n -> n.contains("张伟"))
        .hasSizeGreaterThanOrEqualTo(1);
    // ★ 置信度比 ORG 低一档：机构有后缀表兜底，人名靠职务词、可能把「销售经理」这类复合词误判。
    assertThat(got).filteredOn(r -> r.type().equals("PERSON")).allMatch(r -> r.confidence() == 0.7);
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
    assertThat(recognizer.supportedTypes()).containsExactly("ORG", "DOC", "PERSON");
  }

  // ===== #328：词表必须按库取，而不是永远用构造时那份 =====

  /**
   * ★★ `#328` 的核心判据：同一个词、同一个文本，**换个库换一套后缀表，结果要不同**。
   *
   * <p>这条判据曾经红过 8 轮、最后被我删掉 —— 真因在 `endsWithOrgSuffix` 里：参数 `suffixes` 传进来了， 循环却读字段
   * `orgSuffixes`，于是库级字典永远进不来。修完这条就能绿，所以它同时是那条 bug 的守卫。
   */
  @Test
  void usesTheSuffixTableOfEachKnowledgeBase() {
    var recognizer = new HanlpEntityRecognizer(List.of("公司"));
    recognizer.setDictionaryPort(
        kbId -> new EntityExtractor.Dictionary(List.of("部"), List.of(), java.util.Map.of()));

    String text = "财务资产部负责固定资产管理。";

    // 不传库标识 ⇒ 用构造时那份（只有「公司」）⇒ 抽不到
    assertThat(recognizer.recognize(text)).isEmpty();

    // 传库标识 ⇒ 用这个库配的（「部」）⇒ 抽得到
    assertThat(recognizer.recognize(java.util.UUID.randomUUID(), text))
        .extracting(HanlpEntityRecognizer.Recognized::name)
        .contains("财务资产部");
  }

  @Test
  @DisplayName("库级字典为空 ⇒ 退回构造时那份，不是什么都抽不出")
  void fallsBackToDefaultWhenLibraryHasNoDictionary() {
    HanlpEntityRecognizer fresh = new HanlpEntityRecognizer(List.of("部", "中心"));
    fresh.setDictionaryPort(kbId -> new EntityExtractor.Dictionary(List.of(), List.of(), Map.of()));

    // 空字典不是错：新建库还没配字典，退回默认表才能抽到「财务资产部」
    assertThat(fresh.recognize(UUID.randomUUID(), "财务资产部是归口管理部门。"))
        .filteredOn(r -> r.type().equals("ORG"))
        .extracting(Recognized::name)
        .contains("财务资产部");
  }

  @Test
  @DisplayName("relations 必须真的抽得出关系 —— 恒返回空会让关系表永远是 0 行")
  void relationsAreNotAlwaysEmpty() {
    // ★ 这条是 2026-11-11 真栈撞出来的：relations() 曾经直接 return List.of()，
    //   注释写"关系由规则层补"，但规则层从没被接进这条链 ⇒ kb_entity 263 行、kb_relation 0 行，
    //   两边都没有异常。判据：内置标记词「归口」必须能抽出 BELONGS_TO。
    HanlpEntityRecognizer fresh = new HanlpEntityRecognizer(List.of("部", "中心"));

    assertThat(fresh.relations("综合部归口财务资产部管理。"))
        .extracting(Relation::type, Relation::from, Relation::to)
        .contains(tuple("BELONGS_TO", "综合部", "财务资产部"));
  }
}
