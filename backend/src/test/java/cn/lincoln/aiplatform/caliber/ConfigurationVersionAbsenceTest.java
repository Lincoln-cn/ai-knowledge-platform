package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #88}）：<strong>埋点里没有"配置版本"这个维度 —— 同一份读数无法按版本分组</strong>。
 *
 * <h3>它断言的是"当前没做这件事"，所以它将来必须被刻意改掉</h3>
 *
 * <p>{@code #88} 的现象是：换提示词、换模型、调分段参数、调阈值 —— <strong>每次都是全量上线</strong>，
 * 改前改后无法归因。这条断言钉住的是"今天没有那个维度"，因为它是{@code #88}里唯一<strong>现在就该留</strong>的东西：
 *
 * <blockquote>
 *
 * 埋点里带上「应用配置版本 / 提示词版本 / 检索参数版本」—— <strong>只留维度，不做分流</strong>。 等 {@code #73}
 * 的指标骨架落地后，这个维度就能让"改前改后"变成<strong>可分组对比</strong>的两个数。 （{@code #88} 处置 ①）
 *
 * </blockquote>
 *
 * <h3>★ 为什么断言打在 {@code HitTestView.Trace} 上</h3>
 *
 * <p>它是 {@code 10 §7.1} 埋点口径<strong>今天的载体</strong>（{@code HitTestView} 的类注释原话：
 * "后端不做埋点服务，就把它放进回包里"）。所以"轨迹里有没有配置版本"就是"埋点维度有没有留"的可核对读数。 换别的地方（例如某个 {@code analytics}
 * 类）反而会变成"挑一个不重要的类凑数"—— {@code analytics} 模块今天<strong>只有 package-info，一个类都没有</strong>，这是 {@code
 * #73} 的账，不是本条的。
 *
 * <h3>★ 为什么红线不画在"当前观测不到"这句空话上</h3>
 *
 * <p>"观测不到"是不可断言的主观判断。可断言的是两件具体的事：<strong>轨迹的字段清单</strong>与
 * <strong>检索模块里存不存在实验分组字段</strong>。两条都是要引入"对照实验"就必然先动的地方 —— 要么给轨迹加字段（读侧），要么在检索侧记分组（写侧），绕不过去。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>。
 */
class ConfigurationVersionAbsenceTest {

  /** 检索模块的字节码（{@code 10} 的 V1 清单落在这一册，所以"对照实验"的第一个落点在这里）。 */
  private static final com.tngtech.archunit.core.domain.JavaClasses RETRIEVAL =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform.retrieval");

  @Test
  void assertRetrievalTraceHasNoConfigVersionDimension() {
    JavaClass trace =
        Caliber.APP.get("cn.lincoln.aiplatform.retrieval.application.HitTestView$Trace");

    assertThat(CaliberShape.instanceFields(trace))
        .as(
            """
            ★ HitTestView.Trace 的字段清单变了。它今天是 10 §7.1 埋点口径的载体，能回答
            "这层做了什么"（子问题数/查询数/路数/降级档/多跳轮次…），但**回答不了**
            "这是哪一版配置做出来的" —— 没有 configVersion / promptVersion / experimentGroup。
            若新增了版本维度，那是 #88 的位置被填上了：那一刻要同时做两件事 ——
            ① 10 §10.3 的 V1 清单里"对照实验"那一行的触发条件要复核；
            ② 说明"这个维度从哪取值"（应用配置版本 / 提示词版本 / 检索参数版本）。
            当前基线：%s\
            """,
            CaliberShape.render(Caliber.TRACE_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.TRACE_FIELDS);
  }

  /**
   * ★ 第二条读数：<strong>检索模块里一个"实验分组"字段都没有</strong>。
   *
   * <p>扫的是<strong>编译产物里声明的字段</strong>（不是源码文本里的词）：注释里出现 {@code A/B} 不算数。 这条与上一条互补 ——
   * 上一条管读侧（轨迹回包），这一条管写侧（检索自身有没有在记"我属于哪一组"）。
   */
  @Test
  void assertRetrievalModuleHasNoExperimentGroupField() {
    List<String> offenders =
        RETRIEVAL.stream()
            .flatMap(type -> CaliberShape.instanceFields(type).stream())
            .filter(field -> Caliber.EXPERIMENT_MARKERS.stream().anyMatch(field::startsWith))
            .sorted()
            .toList();

    assertThat(offenders)
        .as(
            """
            ★ 检索模块的类上出现了实验/灰度维度的字段。
            #88 的处置是"先记账、留一个便宜的位置"，而**分流**明确排在 0.5.x 之后
            （分流要动路由与配额，与 MVP 无交集）。若真开始做分流了，
            先确认 10 的 V1 清单已经把它从"留 V1"改成"进 MVP"，并写清触发条件。实际命中：%s\
            """,
            CaliberShape.render(offenders))
        .isEmpty();
  }
}
