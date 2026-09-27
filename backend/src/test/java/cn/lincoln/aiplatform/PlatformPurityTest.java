package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * 共享内核的纯净性（{@code ROADMAP §4.7.3} 第 1 周测试项 ③）。
 *
 * <p>为什么这两条禁令值得单独一个测试类：
 *
 * <ul>
 *   <li><strong>禁 {@code org.springframework.ai.*}</strong>：{@code platform} 是所有模块的底座。
 *       它一旦直接依赖某个模型实现，"换个模型厂商"就变成"改内核"， 而 {@code modelcatalog} 存在的意义（把模型接入收在一个模块里，见 {@code 01 §二} 第
 *       3 行）也就没了。
 *   <li><strong>禁 {@code reactor.*}</strong>：我们选了 MVC + 虚拟线程（ADR-01）。 Spring AI 的流式客户端会把 Reactor
 *       带进依赖树（{@code 09 §4.5}）， 所以这条禁令不是"理论上不会发生"—— 它是<em>随时会被顺手违反</em>的那种。 只要 {@code Mono}/{@code
 *       Flux} 从内核漏出去，响应式类型就会渗透到所有模块的领域层。
 * </ul>
 *
 * <p>⚠️ 依赖树里有 Reactor ≠ 我们选了 WebFlux 栈：{@code spring-boot-starter-webmvc} 在， Boot 的 {@code
 * WebApplicationType} 仍是 SERVLET。本测试管的是"谁在<em>代码里</em>用它"，不是依赖存在与否。
 */
class PlatformPurityTest {

  private static final JavaClasses APP_CLASSES =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  @Test
  void 共享内核不得依赖SpringAI() {
    noClasses()
        .that()
        .resideInAPackage("cn.lincoln.aiplatform.platform..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.ai..")
        .because("共享内核依赖具体模型实现 ⇒ 换模型等于改内核，modelcatalog 的隔离就白做了")
        .check(APP_CLASSES);
  }

  @Test
  void 共享内核不得依赖响应式类型() {
    noClasses()
        .that()
        .resideInAPackage("cn.lincoln.aiplatform.platform..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("reactor..")
        .because("ADR-01 选了 MVC + 虚拟线程；Mono/Flux 从内核漏出去会渗透到所有模块的领域层")
        .check(APP_CLASSES);
  }
}
