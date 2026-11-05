package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★ <strong>执行引擎「尚未落地」的见证人</strong>（{@code #113}）—— 钉住"设计在册、实现未落"这个状态，防止口径被读成已经做了。
 *
 * <p>{@code 09 §5.3} 要求解释器**现在就写成「图 → 执行计划 → 执行」两段**（将来换引擎只换后者）， {@code §5.3.2} 又定了三处形状（{@code
 * KeyUpdate} / 中断点即拒绝发布 / 检查点 L1+L2）。 这些要求今天**没有代码载体**：{@code workflow} 模块只有一个 {@code
 * package-info.java}，执行引擎在 V1.0。
 *
 * <h3>★ 这条测试为什么必须存在</h3>
 *
 * <p>{@code #113} 的问题不是"没做"，而是"**设计没有变成能拦人的判据**" —— 一条只写在设计册里的要求， 和"有余力的建议"没有区别。执行引擎今天已落地，但**不在
 * {@code workflow} 模块**（落在 {@code assistant/application}，与编排配置面/NodeTypeCatalog 同处；{@code workflow}
 * 的 allowedDependencies 不含 {@code assistant}，搬过去会越界）， 所以 {@link
 * #workflowModuleStillHasOnlyThePackageInfo} 仍绿、它只钉「{@code workflow} 模块别被塞实现类」； ★ {@code #113}
 * 六条判据的**行为断言**已由 {@code WorkflowCompilerTest} / {@code WorkflowInterruptPublishTest} / {@code
 * FlowStateTest} / {@code LinearWorkflowExecutorL1Test} / {@code LinearWorkflowExecutorTest} 接上
 * （逐行对应见 {@code WorkflowEngineAdmissionTest#eachAdmissionCriterionHasItsOwnBehaviouralTest}）。
 */
class WorkflowEngineAbsenceTest {

  private static final Path WORKFLOW_MAIN =
      Path.of("src", "main", "java", "cn", "lincoln", "aiplatform", "workflow");

  private static final Path DESIGN = Path.of("..", "docs", "系统设计方案", "09-中间件与-Spring-模块选型.md");

  @Test
  void workflowModuleStillHasOnlyThePackageInfo() {
    List<String> files;
    try (Stream<Path> walk = Files.walk(WORKFLOW_MAIN)) {
      files =
          walk.filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .filter(name -> name.endsWith(".java"))
              .sorted()
              .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("读不到 workflow 模块：" + WORKFLOW_MAIN.toAbsolutePath(), failure);
    }

    assertThat(files)
        .as(
            "★ workflow 模块保持只有 package-info（执行引擎不在这里，落在 assistant/application 与编排配置面同处）——"
                + " 一旦有人把实现类塞进 workflow，这条会红。#113 六条判据的行为测试见 WorkflowCompilerTest /"
                + " WorkflowInterruptPublishTest / FlowStateTest / LinearWorkflowExecutorL1Test /"
                + " LinearWorkflowExecutorTest")
        .containsExactly("package-info.java");
  }

  @Test
  void theTwoStageRequirementIsStillOnTheBooks() {
    String design = read(DESIGN);

    assertThat(design).as("09 §5.3 的『图 → 执行计划 → 执行』两段式要求：它消失 = #113 的前提被悄悄抹掉").contains("执行计划");
    assertThat(design).as("§5.3.2 的三处形状：KeyUpdate").contains("KeyUpdate");
    assertThat(design).as("§5.3.2 的三处形状：中断点").contains("中断点");
    assertThat(design).as("§5.3.3 的两条链路边界").contains("检索链");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("读不到设计册：" + path.toAbsolutePath(), failure);
    }
  }
}
