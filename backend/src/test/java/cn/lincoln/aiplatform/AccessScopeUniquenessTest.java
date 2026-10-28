package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.support.ArchUnitImports;
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
 *
 * <h3>★ 扫描范围：只扫<strong>主代码</strong>（{@code src/main/java}）</h3>
 *
 * <p>{@code DO_NOT_INCLUDE_TESTS} 按类文件位置排除<strong>测试输出目录</strong>（Maven = {@code
 * target/test-classes}；Gradle / IntelliJ 的对应目录 ArchUnit 自己也认），于是这份导入剩下的就是 {@code src/main/java}
 * 编译出来的那一份。
 *
 * <p>★ 为什么必须限定：本闸门守的是<strong>生产代码</strong>的架构约束（"可见性判定只有一处实现"，{@code 07 §3.3}）， 而测试里的 {@code
 * AccessScopePort} 替身（{@code ChatStreamEventRecordingTest$AnonymousScope} / {@code
 * $KnownScope}）是<strong>刻意</strong>造的窄替身 —— 它们要构造"身份取不到"、"范围为空"这类<strong>边界输入</strong>，
 * 本来就该住在被测类旁边，而不是为了迁就一条针对主代码的规则搬进 {@code identity}。 ★ 让替身去迁就规则，会把规则的真实意图
 * （<strong>判定属于身份域</strong>）悄悄换成"测试替身也必须住在身份域"—— 那既没有意义，又会诱使后来者把替身塞进 {@code
 * identity}，而闸门从此对这些替身再也说不出任何话。
 *
 * <p>★★ 排除测试<strong>不等于放宽</strong>：主代码里再出现一个 {@code AccessScopePort} 的实现（非 {@code platform}、
 * 非接口）照样会红。★ 反过来也要说清：下面那条金丝雀证明的是<strong>规则</strong>还能红，它<strong>证明不了</strong> "这份收窄后的导入还看得见主代码"。
 *
 * <p>★ 那件事现在由 {@link ArchUnitImports} + {@code ArchUnitImportsTest} 常驻守着（收窄看不见测试类 · 不收窄看得见它 ·
 * 收窄仍看得见主代码），不再依赖"每次改动都记得手工造一次主代码违规"（{@code #135}）。★ 但**规则**仍要靠本类的金丝雀 —— "看得见"与"判得对"是两件事。
 */
class AccessScopeUniquenessTest {

  private static final JavaClasses APP_CLASSES = ArchUnitImports.mainSources();

  /** ★ 金丝雀住在测试源集里，所以它单独导入、<strong>不</strong>套用上面的收窄。 */
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
