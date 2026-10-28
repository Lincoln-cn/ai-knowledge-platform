package cn.lincoln.aiplatform.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;

/**
 * 收窄过的导入构造**能不能自证**（{@code #135} 的常驻化）。
 *
 * <p>坏样本住在这里（本类是一个测试类），所以这三条断言同时是"规则"和"样本"： 收窄必须看不见它、不收窄必须看得见它、收窄仍须看得见主代码。
 */
class ArchUnitImportsTest {

  /** 一个已知的测试类（就是本类）—— 收窄必须把它排除。 */
  private static final String A_KNOWN_TEST_CLASS = ArchUnitImportsTest.class.getName();

  /** 一个已知的主代码类（`#221` 引入的认证头唯一构造点）—— 收窄若把它也排除，闸门就真的瞎了。 */
  private static final String A_KNOWN_MAIN_CLASS =
      "cn.lincoln.aiplatform.modelcatalog.infrastructure.AuthHeader";

  @Test
  void narrowedImportCannotSeeTestSources() {
    JavaClasses narrow = ArchUnitImports.mainSources();

    assertThat(narrow.contain(A_KNOWN_TEST_CLASS))
        .as("收窄后的导入**不该**看得见测试类 %s —— 看得见说明 DO_NOT_INCLUDE_TESTS 没生效", A_KNOWN_TEST_CLASS)
        .isFalse();
  }

  @Test
  void widenedImportDoesSeeThatSameTestClass() {
    JavaClasses wide = ArchUnitImports.allSources();

    assertThat(wide.contain(A_KNOWN_TEST_CLASS))
        .as("不收窄的导入**必须**看得见 %s。否则上一条会因为\"根本没导入它\"而通过 —— 那是假绿", A_KNOWN_TEST_CLASS)
        .isTrue();
  }

  @Test
  void narrowedImportStillSeesMainSources() {
    JavaClasses narrow = ArchUnitImports.mainSources();

    assertThat(narrow.contain(A_KNOWN_MAIN_CLASS))
        .as("收窄后的导入必须仍看得见主代码类 %s —— 看不见说明收过头了，闸门从此对生产代码说不出话", A_KNOWN_MAIN_CLASS)
        .isTrue();
  }
}
