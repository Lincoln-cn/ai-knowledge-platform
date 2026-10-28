package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.ArchUnitImports;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Jackson 2 在**测试源集**里的唯一合法出口（{@code #134} 的残余面）。
 *
 * <h3>★ 为什么需要它</h3>
 *
 * <p>{@link JacksonUnificationTest} 与 {@code AccessScopeUniquenessTest} 按裁定把扫描范围收窄到了主代码（{@code
 * ImportOption.Predefined.DO_NOT_INCLUDE_TESTS}）—— 它们的违规对象本来就是测试源集里的刻意替身。收窄是对的，但**收窄有价钱**：
 * 从此测试代码可以自由引 Jackson 2，于是可能出现"用 Jackson 2 断言的 JSON 形状 ≠ 生产 Jackson 3 实际发出的形状"这种**假绿**， 而没有任何闸门在守。
 *
 * <p>★ 本类正是那笔价钱的兑付方式，且**不回退**上一条的范围：主代码的规则由 {@link JacksonUnificationTest} 守，
 * 测试源集由本类守（白名单式，而不是"扫全量"）。
 *
 * <h3>★ 白名单只有一项，理由必须写在代码里</h3>
 *
 * <p>{@code SourceRefSerializationTest} 必须用 Jackson 2：它要复现的正是 Hibernate 的 {@code JsonFormatMapper}
 * 落 jsonb 时的序列化形状，而那个实现用的就是 Jackson 2 —— 换成 Jackson 3 之后，读数就不再是"库里的形状"，
 * 而变成"另一台序列化器的形状"，这条测试也就失去了意义。
 *
 * <p>★ 白名单是**按需登记**的：加一项之前先问"这个测试是真的要复现 Jackson 2 的形状，还是只是顺手 import 了它"。 后者应该改成 Jackson
 * 3（本闸门落地当天就抓到过一例：{@code SampleBusinessDataSeedTest} 用 Jackson 2 解析 HTTP 响应，, 与生产序列化器不同 ⇒ 已改）。
 */
class JacksonTwoUsageTest {

  /** 允许引 Jackson 2 的测试类，以及"为什么非它不可"。 */
  private static final Map<String, String> ALLOWED =
      Map.of(
          "cn.lincoln.aiplatform.conversation.domain.SourceRefSerializationTest",
          "要复现 Hibernate JsonFormatMapper 落 jsonb 的形状，而它用的就是 Jackson 2");

  private static final List<String> JACKSON_TWO_PACKAGES =
      List.of("com.fasterxml.jackson.databind", "com.fasterxml.jackson.core");

  @Test
  void jacksonTwoIsOnlyForbiddenOutsideTheWhitelist() {
    JavaClasses classes = ArchUnitImports.allSources();

    Map<String, String> offenders = new LinkedHashMap<>();
    for (var javaClass : classes) {
      if (ALLOWED.containsKey(javaClass.getName())) {
        continue;
      }
      for (var dependency : javaClass.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getName();
        if (JACKSON_TWO_PACKAGES.stream().anyMatch(target::startsWith)) {
          offenders.putIfAbsent(javaClass.getName(), target);
        }
      }
    }

    assertThat(offenders)
        .as(
            "测试源集里只允许 %s 引 Jackson 2（%s）。其余的 JSON 断言请用 Jackson 3（tools.jackson）——"
                + "用另一台序列化器断形状，绿了也不说明生产发出去的是什么",
            ALLOWED.keySet(), ALLOWED.values())
        .isEmpty();
  }

  /**
   * 自证：白名单里的那一项**确实**还在引 Jackson 2。
   *
   * <p>没有这条断言，白名单就只是一个不会过期的许可：哪天 {@code SourceRefSerializationTest} 改成 Jackson 3，条目还留着， 而下一个想用
   * Jackson 2 的人会拿它当先例。{@code #135} 同族的坏味是"闸门看不见东西也照样绿"，这里把它钉住。
   */
  @Test
  void theWhitelistEntryStillNeedsJacksonTwo() {
    JavaClasses classes = ArchUnitImports.allSources();

    assertThat(ALLOWED).isNotEmpty();
    for (String name : ALLOWED.keySet()) {
      boolean usesJacksonTwo =
          classes.get(name).getDirectDependenciesFromSelf().stream()
              .map(dependency -> dependency.getTargetClass().getName())
              .anyMatch(target -> JACKSON_TWO_PACKAGES.stream().anyMatch(target::startsWith));

      assertThat(usesJacksonTwo)
          .as("%s 在白名单里，但它已经不引 Jackson 2 ⇒ 该把这条白名单删掉（白名单必须随清理收缩）", name)
          .isTrue();
    }
  }
}
