package cn.lincoln.aiplatform.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * ArchUnit 闸门共用的**导入构造**（{@code #135}）。
 *
 * <p>★ 为什么把它抽出来：靠 {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS} 收窄过的闸门有一个天然盲区 ——
 * 坏样本只能住在测试源集里，而收窄恰好把测试源集排除在外，于是"规则还能红"的常驻金丝雀**证明不了**"收窄之后还看得见主代码"。
 * 以前这件事只能靠一次临时的主代码违规在报告里取证（`JacksonUnificationTest` / `AccessScopeUniquenessTest` 的 javadoc
 * 都写了这句），而"每次改动都记得去手工取证"不是一条能长期站住的口径。
 *
 * <p>★ 抽到这里之后，收窄这件事本身变成可测的：{@code ArchUnitImportsTest} 常驻断言三件事 —— ① 收窄后的集合**看不见**一个已知的测试类（收窄真的生效）；②
 * 不收窄的集合**看得见**它（坏样本确实在同一个基包里， 所以上一条不是因为"根本没导入"才通过的）；③ 收窄后的集合**仍然看得见**一个已知的主代码类（没有收过头）。
 * 三条合起来，等于把"导入构造"钉住：任何人改坏它，这个测试立刻红。
 *
 * <p>★ 它证不了的东西也要说清：它证明的是**导入构造**，不是**规则**。规则能不能红仍要靠各闸门自己的金丝雀
 * （或在主代码里临时造一次违规）。两件事分开证，才不会把"看得见"读成"判得对"。
 */
public final class ArchUnitImports {

  /** 全项目基包：主代码与测试代码都在它下面。 */
  public static final String BASE_PACKAGE = "cn.lincoln.aiplatform";

  /** 只导入**主代码**（测试源集排除）。给"违规对象是测试替身"的那类闸门用。 */
  public static JavaClasses mainSources() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BASE_PACKAGE);
  }

  /** 主代码 + 测试代码。给"既要守主代码、又要守测试源集"的闸门用（如 {@code JacksonTwoUsageTest}）。 */
  public static JavaClasses allSources() {
    return new ClassFileImporter().importPackages(BASE_PACKAGE);
  }

  private ArchUnitImports() {}
}
