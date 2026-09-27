package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界闸门（{@code ROADMAP §4.7.1} 的"架构测试"道 + "越界依赖"金丝雀）。
 *
 * <p>它做两件不同的事，两者都必须有：
 *
 * <ol>
 *   <li><strong>闸门</strong>：{@link ApplicationModules#verify()} 校验模块依赖无环、无越界访问、
 *       无未声明依赖。这是"把架构约定变成构建失败"的主机制。
 *   <li><strong>金丝雀</strong>：证明上面那条闸门<em>真的在检查</em>。
 * </ol>
 *
 * <p>★ 金丝雀的写法值得说明 —— 它<strong>不需要手动改代码让构建变红</strong>：同一个 {@link #l3MustNotDependOnL4(String)}
 * 生成同一条规则，对<em>干净的应用代码</em>断言"必须通过"， 对 {@code canaryfixture} 里的<em>故意越界样本</em>断言"必须失败"。
 * 于是"规则写反了、包名打错了、断言方向颠倒了"这三种最常见的失效方式， 都会让<em>金丝雀</em>这一条红 —— 而不是让一个永远为真的测试静静地绿下去。
 * 这正是本项目最怕的那类问题："报红会有人修，'少了一条'没人会注意到"。
 */
class ModuleBoundaryTest {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(AiPlatformApplication.class);

  private static final JavaClasses APP_CLASSES =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  /** 金丝雀样本所在的命名空间，与应用包刻意分开（见 ConversationInternal 的说明）。 */
  private static final JavaClasses CANARY_CLASSES =
      new ClassFileImporter().importPackages("canaryfixture");

  @Test
  void 模块边界必须合法_无环且无越界依赖() {
    MODULES.verify();
  }

  /**
   * ★ 只调 {@code verify()} 是不够的：它只校验<strong>已识别</strong>的模块。 若有模块的 {@code package-info.java} 写错包名或漏写
   * {@code @ApplicationModule}， 那个包会从模块视图里消失，{@code verify()} 反而更容易通过。 这条断言把"12 个模块一个都不少"摆到明面上。
   */
  @Test
  void 十二个模块必须全部被识别() {
    assertThat(MODULES.stream()).as("模块数不对 ⇒ 有 package-info 没被识别成模块，此时 verify() 的绿是假绿").hasSize(12);

    List.of(
            "platform",
            "identity",
            "modelcatalog",
            "vectorindex",
            "knowledge",
            "ingestion",
            "retrieval",
            "assistant",
            "conversation",
            "feedback",
            "analytics",
            "workflow")
        .forEach(
            name ->
                assertThat(MODULES.getModuleByName(name))
                    .as("模块 %s 没被识别 —— 它的 package-info 包名或注解有问题", name)
                    .isPresent());
  }

  @Test
  void 闸门_检索不得依赖问答() {
    l3MustNotDependOnL4("cn.lincoln.aiplatform").check(APP_CLASSES);
  }

  @Test
  void 金丝雀_同一闸门必须抓到故意越界的样本() {
    assertThatThrownBy(() -> l3MustNotDependOnL4("canaryfixture").check(CANARY_CLASSES))
        .as("坏样本没被拦下 ⇒ 闸门本身已失效。这时主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * L3（检索）不得依赖 L4（问答）。方向来自 {@code 01 §三.1} 的分层图， 被依赖的是"调用者"—— 反向依赖意味着分层塌了。
   *
   * @param basePackage 命名空间前缀。同一个规则跑在两个空间上：应用代码必须过，样本必须不过。
   */
  private static ArchRule l3MustNotDependOnL4(String basePackage) {
    return noClasses()
        .that()
        .resideInAPackage(basePackage + ".retrieval..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(basePackage + ".conversation..");
  }
}
