package cn.lincoln.aiplatform.platform.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 基础数据字典（{@code 08 §十}）—— 真 PG，纯 SQL 夹具（不需要桩服务与 HTTP）。
 *
 * <h3>这个类守的是两条**不会报错**的规则</h3>
 *
 * <ol>
 *   <li><strong>密级禁止增删</strong>：往字典里插第五档的症状是"新档位的内容对**所有人**都不可见" （{@code classification_rank} 的
 *       {@code else 99} = fail-closed），而没有任何地方报错。
 *   <li><strong>停用 ≠ 删除</strong>：{@code code} 已经写进历史数据；删掉它会让历史数据的展示回落成
 *       机器码。所以"停用之后既有取值还查得到"这件事必须有个断言钉住 —— 否则某天有人把接口改成 DELETE， 谁也不会立刻发现问题。
 * </ol>
 *
 * <p>★ 种子数据的断言逐项对齐**前端原来那些常量**（`labels.ts` 的 {@code DOC_TYPE_SUGGESTIONS}、 mock
 * 的提示词类型与库分类）：照抄原值而不是"顺手改名"，理由与 `V20` 的默认值同源 —— 两边不一致的症状是"演示时好用、部署后不一样"。
 */
@SpringBootTest
@Testcontainers
class DictionaryServiceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID ACTOR = UUID.randomUUID();

  @Autowired DictionaryService dictionaries;

  @Autowired JdbcTemplate jdbc;

  @Test
  void seedCoversTheValuesThatUsedToLiveInFrontendConstants() {
    assertThat(codesOf(DictionaryKind.CLASSIFICATION))
        .as("密级四档与 V21/V22 的生成列、与 Java 枚举逐位一致")
        .containsExactly("PUBLIC", "INTERNAL", "SECRET", "CONFIDENTIAL");
    assertThat(codesOf(DictionaryKind.DOC_TYPE))
        .as("文种 = 前端 DOC_TYPE_SUGGESTIONS 那八个原值（不是新造的码）")
        .contains("制度", "合同", "报销规定", "管理办法", "操作规程", "通知公告", "工作报告", "培训手册");
    assertThat(codesOf(DictionaryKind.KB_CATEGORY)).contains("制度规范", "产品资料", "行业标准");
    assertThat(codesOf(DictionaryKind.PROMPT_TYPE)).contains("通用", "合同法务", "制度咨询", "写作辅助");
  }

  @Test
  void classificationRejectsNewLevelsAndSaysWhy() {
    assertThatThrownBy(
            () ->
                dictionaries.create(
                    DictionaryKind.CLASSIFICATION, "TOP_SECRET", "绝密", 9, null, ACTOR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("密级不允许新增档位")
        // ★ 报错要指路（08 §4.1）：文案里必须出现"要改什么"，否则下一个人只会把这一行删掉
        .extracting(e -> ((BusinessException) e).hint())
        .asString()
        .contains("classification_rank");
  }

  @Test
  void disablingKeepsTheCodeReadableForExistingData() {
    dictionaries.create(DictionaryKind.DOC_TYPE, "临时文种", "临时文种", 99, null, ACTOR);
    dictionaries.update(DictionaryKind.DOC_TYPE, "临时文种", null, null, false, null, ACTOR);

    assertThat(codesOf(DictionaryKind.DOC_TYPE)).as("停用之后不再出现在消费方的下拉里").doesNotContain("临时文种");
    assertThat(codesOfIncludingDisabled(DictionaryKind.DOC_TYPE))
        .as("★ 但维护页与既有数据仍然读得到它 —— 这正是'只能停用不能删除'的语义")
        .contains("临时文种");
  }

  @Test
  void labelCanChangeWithoutTouchingTheCode() {
    dictionaries.update(DictionaryKind.DOC_TYPE, "合同", "合同与协议", null, null, null, ACTOR);

    DictionaryItem item =
        dictionaries.items(DictionaryKind.DOC_TYPE, false).stream()
            .filter(i -> i.code().equals("合同"))
            .findFirst()
            .orElseThrow();
    // ★ code 必须原样：它进了 kb_document.doc_type 与召回前缀，改它要全表重向量化（改 label 不需要）
    assertThat(item.code()).isEqualTo("合同");
    assertThat(item.label()).isEqualTo("合同与协议");
  }

  @Test
  void duplicateCodeIsRejected() {
    assertThatThrownBy(
            () -> dictionaries.create(DictionaryKind.DOC_TYPE, "合同", "合同（重复）", 50, null, ACTOR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已经存在");
  }

  private List<String> codesOf(DictionaryKind kind) {
    return dictionaries.items(kind, false).stream().map(DictionaryItem::code).toList();
  }

  private List<String> codesOfIncludingDisabled(DictionaryKind kind) {
    return dictionaries.items(kind, true).stream().map(DictionaryItem::code).toList();
  }
}
