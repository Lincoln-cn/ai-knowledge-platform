package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * JSON 统一收口到 <strong>Jackson 3</strong>（用户 2026-10-12 拍板）。
 *
 * <h3>为什么值得一条架构闸门</h3>
 *
 * <p>两套 databind 并存时，代码是能跑、测试也能绿的 —— 直到某个对象在两台序列化器下表现不同（未知字段、日期格式、 null 处理、异常是否受检），而那时的症状是"偶发、且看不出与
 * JSON 有关"。本项目对这类 "看起来没事、实际两套口径"的东西极敏感，所以把"只能用一套"写成断言，而不是写进文档靠自觉。
 *
 * <h3>★ 允许的例外（不是宽容，是事实）</h3>
 *
 * <p>{@code com.fasterxml.jackson.annotation} 仍然可用：<strong>Jackson 3 的 databind 自己就依赖 {@code
 * com.fasterxml.jackson.core:jackson-annotations}</strong>（注解包在 3.x 里刻意没有改名，好让 2 与 3 共享同一套注解）——
 * 本项目两处 {@code @JsonInclude}（{@code ModelView} / {@code UserView}）就是它的消费者。
 * 把注解一起禁掉只会制造"白名单"，而那正是让闸门变成"看人心情"的第一步。
 *
 * <h3>为什么后端代码里"依赖 Jackson 2 的 jar"与"用 Jackson 2 的 API"是两件事</h3>
 *
 * <p>{@code jackson-databind 2.x} 仍然在依赖树里（Tika 用它），本闸门**管不到也不打算管**它 —— 它管的是<strong>我们的代码</strong>不再
 * import 2.x 的 {@code databind} / {@code core}。 换言之：jar 在不在不是判据，我们的 import 在不在才是。
 */
class JacksonUnificationTest {

  private static final JavaClasses APP_CLASSES =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  /**
   * 我们的代码不得再出现 Jackson 2 的 {@code databind} / {@code core} —— 中文的"为什么"写在类 javadoc 里 （标识符一律
   * ASCII：同一个标识符被非 UTF-8 的工具读写就会变形，而文档的入口本来就是 javadoc）。
   */
  @Test
  void noJackson2DatabindOrCore() {
    noClasses()
        .that()
        .resideInAPackage("cn.lincoln.aiplatform..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
        .because(
            "Jackson 3（tools.jackson）已由 Spring Boot 4 带进依赖树；两套 databind 并存会出现"
                + "「同一个对象两台序列化器」的两种行为，而症状偶发、且看不出与 JSON 有关。"
                + "★ 注解包（com.fasterxml.jackson.annotation）不在此列 —— Jackson 3 自己也用它")
        .check(APP_CLASSES);
  }
}
