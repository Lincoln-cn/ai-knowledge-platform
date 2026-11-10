package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor.Candidate;
import cn.lincoln.aiplatform.knowledge.application.EntityExtractor.Dictionary;
import cn.lincoln.aiplatform.knowledge.application.EntityExtractor.Relation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityExtractor} 的判据（0.6.1）。
 *
 * <p>★ 每条用例都对应一个**已知的失败模式**，不是"跑一下看它出什么"。没有失败模式的 用例只能证明"实现没变"，那种用例在项目里已经出过一次事（`#315` 把粘连抄成期望值）。
 *
 * <p>★ 词表由 {@link #dict()} 传入而不是写在抽取器里 —— 用户 2026-11-11 要求字典可配， 改字典不重启。用例自己造字典，顺带证明"抽取器里没有业务词"。
 */
class EntityExtractorTest {

  private static Dictionary dict() {
    return new Dictionary(
        List.of("部", "局", "处", "科", "室", "中心", "集团", "公司", "委员会", "办公室"),
        List.of("经理", "主任", "部长", "总裁", "总经理", "主管", "专员"),
        Map.of(
            "隶属", "BELONGS_TO",
            "归口", "BELONGS_TO",
            "包括", "CONTAINS",
            "下设", "CONTAINS",
            "负责", "RESPONSIBLE_FOR",
            "主管", "RESPONSIBLE_FOR",
            "是指", "DEFINES"));
  }

  private final EntityExtractor extractor = new EntityExtractor(dict());

  @Test
  @DisplayName("机构名按后缀抽出来，且不跨虚词吞并")
  void extractsOrganizationNames() {
    List<Candidate> got = extractor.extract("固定资产的归口管理部门是财务资产部。信息技术中心负责运维。");

    assertThat(got).extracting(Candidate::name).contains("财务资产部", "信息技术中心");
    // ★ 反向：不能把"的/是"当名字的一部分 —— 第一版红在这里（抽出"固定资产的归口管理部"）
    assertThat(got)
        .extracting(Candidate::name)
        .noneMatch(name -> name.contains("的") || name.contains("是"));
  }

  @Test
  @DisplayName("书名号里的制度文书名单独成类，不与机构名混")
  void extractsDocumentNames() {
    List<Candidate> got = extractor.extract("依据《固定资产管理办法》第十七条，本办法自发布之日起施行。");

    assertThat(got)
        .filteredOn(c -> c.type().equals("DOC"))
        .extracting(Candidate::name)
        .containsExactly("固定资产管理办法");
  }

  @Test
  @DisplayName("关系两端都必须是已抽到的实体——右端是动宾短语时不成边")
  void relationNeedsBothEndsToBeEntities() {
    // "负责固定资产的购置" —— 右端是动宾短语，抽不出实体 ⇒ 不该成边
    List<Relation> got = extractor.relations("财务资产部负责固定资产的购置。");

    assertThat(got).isEmpty();
  }

  @Test
  @DisplayName("隶属关系在两端都是机构时成边")
  void relationBetweenTwoOrganizations() {
    List<Relation> good = extractor.relations("综合部隶属财务资产部。");

    assertThat(good)
        .filteredOn(r -> r.type().equals("BELONGS_TO"))
        .anySatisfy(
            r -> {
              assertThat(r.from()).isEqualTo("综合部");
              assertThat(r.to()).isEqualTo("财务资产部");
            });
  }

  @Test
  @DisplayName("别名只做机械变换：去掉机构后缀，且不含原名本身")
  void aliasStripsOrgSuffix() {
    assertThat(extractor.aliasCandidates("财务资产部")).contains("财务资产");
    // ★ 不能把"财务部"这种跨词的相似名字算成别名 —— 那是编辑距离的活，不是机械变换
    assertThat(extractor.aliasCandidates("财务资产部")).doesNotContain("财务部");
    // 原名自己不是自己的别名
    assertThat(extractor.aliasCandidates("财务资产部")).doesNotContain("财务资产部");
  }

  @Test
  @DisplayName("全角字符归一后才参与别名生成")
  void aliasNormalizesFullWidth() {
    List<String> got = extractor.aliasCandidates("ＡＢＣ中心");

    assertThat(got).contains("ABC");
  }

  @Test
  @DisplayName("空文本不抛异常也不造实体")
  void blankInputIsSafe() {
    assertThat(extractor.extract(null)).isEmpty();
    assertThat(extractor.extract("   ")).isEmpty();
    assertThat(extractor.relations(null)).isEmpty();
    assertThat(extractor.aliasCandidates("")).isEmpty();
  }

  // ── 字典相关的两条（用户 2026-11-11 要求的"可配置"） ──────────────────────

  @Test
  @DisplayName("用户加的机构后缀立刻生效——不需要改代码，也不需要重启")
  void customSuffixTakesEffect() {
    Dictionary withCustom =
        new Dictionary(
            List.of("部", "中心", "研究院"), // ★ 自带一个"研究院"
            List.of(),
            Map.of());

    List<String> names =
        new EntityExtractor(withCustom)
            .extract("智能制造研究院下设三个实验室。").stream()
                .map(Candidate::name)
                .filter(n -> n.endsWith("研究院"))
                .toList();

    assertThat(names).contains("智能制造研究院");
    // 反证方向：同一条文本用**不含**"研究院"的字典抽，抽不出来
    assertThat(extractor.extract("智能制造研究院下设三个实验室。"))
        .extracting(Candidate::name)
        .noneMatch(n -> n.endsWith("研究院"));
  }

  @Test
  @DisplayName("字典里机构后缀为空 ⇒ 构造直接失败，不许静默跑出一个空结果")
  void emptySuffixDictionaryFailsFast() {
    Dictionary broken = new Dictionary(List.of(), List.of(), Map.of());

    assertThatThrownBy(() -> new EntityExtractor(broken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("机构后缀为空");
  }
}
