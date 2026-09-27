package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ★ 可见性判定**只能有一处实现**（{@code ROADMAP §4.7.3} 第 3 周测试项 ③ 的金丝雀）。
 *
 * <p>{@code 07 §3.3} 说"可见性判定是全工程唯一一条"。这句话如果没有东西守着， 它会在第一次"我这里情况特殊，单独判一下"时失效 —— 而那次改动**不会报任何错**：
 * 两处判定各自都对，只是其中一处没跟着另一次需求变更走。 症状是"某个入口能看见不该看见的东西"，且只有那一个入口。
 *
 * <p>★ 与 {@code ModuleBoundaryTest} / {@code AuthorizationDeclarationTest} 同构：
 * 同一条规则跑两个命名空间，应用代码必须过、坏样本必须被抓到。
 */
class AccessScopeUniquenessTest {

  private static final JavaClasses APP_CLASSES =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  private static final JavaClasses CANARY_CLASSES =
      new ClassFileImporter().importPackages("canaryfixture");

  @Test
  void 判定实现只能出现在identity模块() {
    implementationsLiveIn("cn.lincoln.aiplatform").check(APP_CLASSES);
  }

  @Test
  void 金丝雀_第二个实现必须被抓到() {
    assertThatThrownBy(() -> implementationsLiveIn("canaryfixture").check(CANARY_CLASSES))
        .as("坏样本没被拦下 ⇒ 这条闸门已失效，主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 规则：{@code AccessScopePort} 的实现必须都住在 {@code identity} 模块下。
   *
   * <p>★ 为什么不直接断言"只能有一个实现"：数量断言会在"第二个实现写在 identity 内部" 时也放行（那也不对 —— 同一个模块里两处判定同样是分叉）， 而"都必须住在
   * identity"这条同时排除了两种情况，且它表达的是真实意图： <strong>判定属于身份域</strong>（端口在 platform、实现在 identity，{@code 08
   * §4.4.2}）。
   */
  private static ArchRule implementationsLiveIn(String basePackage) {
    DescribedPredicate<JavaClass> implementsThePort =
        new DescribedPredicate<>("实现 AccessScopePort 且位于 " + basePackage + " 下") {
          @Override
          public boolean test(JavaClass clazz) {
            return clazz.isAssignableTo(AccessScopePort.class)
                && clazz.getPackageName().startsWith(basePackage)
                // 接口自身与 platform 里的抽象不算"实现"
                && !clazz.isInterface()
                && !clazz.getPackageName().startsWith("cn.lincoln.aiplatform.platform");
          }
        };

    return classes()
        .that(implementsThePort)
        .should()
        .resideInAPackage("cn.lincoln.aiplatform.identity..");
  }
}
