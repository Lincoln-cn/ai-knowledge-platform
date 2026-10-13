package cn.lincoln.aiplatform.caliber;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组自身的约束：<strong>这六条断言一条都不许依赖库、Spring 上下文或桩服务</strong>（第 16 周并行轨）。
 *
 * <h3>为什么值得一个单独的类</h3>
 *
 * <p>口径组的全部价值在于"它今天能跑、并且将来会红"。如果它写着 {@code @SpringBootTest} 或 {@code @Testcontainers}，那么在<strong>没装
 * Docker 的机器上它会被跳过</strong> —— 而"跳过的绿"与"跑过的绿"在读数上完全一样（本项目已记的坑：{@code 一条会静默跳过的哨兵等于没有哨兵}）。
 *
 * <p>所以这条约束不是一个风格要求，而是这六条断言<strong>能不能算数</strong>的前提，必须由闸门守住， 不能靠约定。
 *
 * <p>★ 它自己也<strong>不需要库、不需要 Spring</strong>：读的是本包自己的字节码。
 */
class CaliberAssertionsMustNotNeedInfrastructureTest {

  /** 本包（{@code cn.lincoln.aiplatform.caliber}）的字节码。 */
  private static final JavaClasses CALIBER =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform.caliber");

  @Test
  void caliberAssertionsMustNotDependOnTestcontainers() {
    noClasses()
        .that()
        .resideInAPackage("cn.lincoln.aiplatform.caliber..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.testcontainers..")
        .because(
            "★ 本机 Docker 没在运行时 Testcontainers 类跑不起来 ⇒ 断言只能被跳过。"
                + "口径组的断言必须「本机与 CI 同一份结论」，所以它一条都不许碰库")
        .check(CALIBER);
  }

  @Test
  void caliberAssertionsMustNotDependOnSpringBootTest() {
    noClasses()
        .that()
        .resideInAPackage("cn.lincoln.aiplatform.caliber..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.boot.test..", "org.springframework.test.context..")
        .because("★ 起 Spring 上下文要真 PG（本仓的数据源指向 Postgres）⇒ 同样只能被跳过。" + "六条断言读的都是字节码与迁移资源，不需要容器")
        .check(CALIBER);
  }

  /**
   * 正面读数：本包的断言类数量。
   *
   * <p>★ 它是"口径组没有被删空"的哨兵：口径组的风险不是写错，而是<strong>被当成噪音删掉</strong> —— 一条断言消失时，没人会注意到某个缺口又变回了"读起来像已经做了"。
   */
  @Test
  void caliberAssertionClassesAreStillPresent() {
    long assertionClasses =
        CALIBER.stream()
            .filter(type -> type.getSimpleName().endsWith("Test"))
            .filter(
                type ->
                    !type.getSimpleName().equals("CaliberAssertionsMustNotNeedInfrastructureTest"))
            .count();

    assertThat(assertionClasses)
        .as(
            """
            ★ 口径组的断言类变少了。六条缺口各有一条断言（#81/#82/#84/#85/#86/#88），
            删掉任何一条都等于把那个缺口重新变成"文档里写着、但没有读数"的状态。
            若确实要删，请先确认 13/07/02/12/10 里那一节口径还在，并在 ROADMAP §11.9.3 记账。\
            """)
        .isGreaterThanOrEqualTo(6);
  }
}
