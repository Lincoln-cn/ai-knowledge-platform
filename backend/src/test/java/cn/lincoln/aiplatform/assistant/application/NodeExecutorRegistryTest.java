package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 执行器注册表的装配判据（{@code #326}）。
 *
 * <p>★ <strong>这条测试断的是"主代码装配"，不是"测试替身"。</strong> {@code #113} 当时的三条执行器测试全部自己 {@code new} 了 {@link
 * NodeExecutor} 替身，所以「主代码里一个实现都没有」这件事一直没暴露 —— 直到 {@code #326} 才发现 {@link LinearWorkflowExecutor}
 * 的注册表在运行态是空的，而 {@link LinearWorkflowExecutor#SUPPORTED_TYPES} 对外宣称四种节点能跑。
 *
 * <p>★ 两件事必须一起看：{@code SUPPORTED_TYPES} 是<strong>发布校验</strong>引用的集合（{@code
 * WorkflowValidationService} 的 {@code NODE_NOT_EXECUTABLE}
 * 规则），主代码里的实现是<strong>运行时</strong>真的能跑的。差集非空 = 「发布成功、点试用失败」。
 *
 * <p>★ 为什么扫源码而不是起 {@code @SpringBootTest}：这个判据只关心"有没有这类 bean"，而仓里同类闸门（{@code
 * SlaSurfaceContractTest}、{@code AuthorizationDeclarationTest}）一律扫源码。第一版写 {@code @SpringBootTest}
 * 跑出的是 {@code Errors: 1}（上下文起不来，要连数据库）—— <strong>一条结构判据不该被环境判据绑架</strong>。代价：扫不到"运行期才注册的
 * bean"；本仓执行器都是 {@code @Component}，静态可见，可接受。
 *
 * <p>★ <strong>第二条用例是反向断言</strong>（{@code #79} 判据 ③ 的写法）：它钉住"还没实现的那几种仍然是没实现"。补执行器时这条会红， 必须同步改清单 ——
 * 否则「差集」这件事会在无人察觉时重新出现。
 */
class NodeExecutorRegistryTest {

  /**
   * 主代码里所有实现了 {@code NodeExecutor} 的类型。
   *
   * <p>★ `#337` 之后这份扫描搬到了 {@link NodeExecutorSources}，因为 {@code WorkflowNodeTypeTest} 也要读它 ——
   * 两处各写一份正则，就会在"哪份才是准的"这一步重新长出第二个真源。这里只做转发。
   */
  private static Set<String> implementedTypes() throws IOException {
    return NodeExecutorSources.implementedTypes();
  }

  /**
   * 尚未有执行器的节点类型 —— <strong>反向断言的数据源</strong>。
   *
   * <p>每补一个就删一行。这份清单与 {@link #actualGap()} 必须一致，否则说明有人补了实现却没更新清单（下面第二条用例会红）。
   */
  private static final Set<String> KNOWN_UNIMPLEMENTED = Set.of();

  /**
   * 目录声明"能跑"、主代码却没有执行器的类型。
   *
   * <p>★ `#337` 换了声明侧：原来是手写的 {@code SUPPORTED_TYPES}，现在读节点目录的 {@code executable} ——
   * 那才是用户能看见的那份"能跑"的承诺。差集非空 = 「发布放行、点试用时 500」。
   */
  private static Set<String> actualGap() throws IOException {
    Set<String> implemented = implementedTypes();
    Set<String> declared =
        new NodeTypeCatalog(new ObjectMapper())
            .all().stream()
                .filter(NodeTypeCatalog.NodeTypeMeta::executable)
                .map(NodeTypeCatalog.NodeTypeMeta::type)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    declared.removeAll(implemented);
    return declared;
  }

  @Test
  @DisplayName("START_NODE 必须有真执行器（#326 补的第一个）")
  void startNodeHasExecutor() throws IOException {
    assertThat(implementedTypes())
        .as("START_NODE 是线性计划的第一个节点，它没有执行器则整条计划跑不起来；它零依赖，没有理由不做")
        .contains("START_NODE");
  }

  @Test
  @DisplayName("缺口清单必须与实际情况一致：补了实现就要同步删清单（反向断言，防「差集」重新出现）")
  void knownUnimplementedMatchesActualGap() throws IOException {
    assertThat(actualGap())
        .as(
            "目录 executable = %s，主代码实际实现 = %s。若这次失败是因为你刚补了一个执行器，"
                + "请把 KNOWN_UNIMPLEMENTED 里对应那行删掉（#326）",
            new NodeTypeCatalog(new ObjectMapper())
                .all().stream()
                    .filter(NodeTypeCatalog.NodeTypeMeta::executable)
                    .map(NodeTypeCatalog.NodeTypeMeta::type)
                    .collect(Collectors.toCollection(LinkedHashSet::new)),
            implementedTypes())
        .isEqualTo(KNOWN_UNIMPLEMENTED);
  }

  @Test
  @DisplayName("START_NODE 真能把输入写进共享状态（数据从入口流到节点，不是「接口被调用过」）")
  void startNodeWritesInputIntoState() {
    FlowState state = new FlowState();
    new StartNodeExecutor()
        .execute(new WorkflowNodeContext(null, "n-start", "START_NODE", "固定资产归口部门是谁", state));

    assertThat(state.read(StartNodeExecutor.QUERY_KEY))
        .as("START_NODE 的职责就是把用户输入搬运到 state[%s]，下游检索靠它", StartNodeExecutor.QUERY_KEY)
        .isEqualTo("固定资产归口部门是谁");
  }

  @Test
  @DisplayName("输入为 null 时写空串而不是留 null：下游只该有一种「没输入」的表现")
  void startNodeNormalizesNullInputToEmptyString() {
    FlowState state = new FlowState();
    new StartNodeExecutor()
        .execute(new WorkflowNodeContext(null, "n-start", "START_NODE", null, state));

    assertThat(state.read(StartNodeExecutor.QUERY_KEY)).isEmpty();
  }
}
