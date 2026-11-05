package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编排执行引擎的准入判据（`09 §5.3.4` · `#113`）。
 *
 * <p>这一条的方向与其他口径测试**相反**：别处钉的是"已经做了什么"，这里钉的是"**还没做**"。 原因是那张准入表（两段式 / 中断点即拒发布 / `KeyUpdate` / 检查点
 * L1 / 注册表边界）一旦被读成 "已经具备"，下一个实现者就会按自己方便的形状把解释器写掉 —— 而 `09 §5.3` 说得很清楚： 两段式是唯一决定"将来换引擎只换后者"的那一句。
 *
 * <p>三条断言：
 *
 * <ol>
 *   <li>{@code workflow} 模块里**只有** {@code package-info.java}（执行引擎不在这里，落在 {@code
 *       assistant/application}，与编排配置面同处）；
 *   <li>★ {@code #113} 六条判据**各有会红的行为测试在验**（准入表逐行换成 "谁在哪条测试里验"）；
 *   <li>{@code 09 §5.3.4} 那张准入表**还在**（被删/被搬走 ⇒ 红）。
 * </ol>
 *
 * <p>执行引擎落地后，原「{@code src/main/java} 里没有 {@code NodeExecutor} 符号」那条已按注释**换成对六条判据的行为测试断言**（而不是删掉）。
 */
@DisplayName("执行引擎：#113 六条判据各有行为测试（准入表逐行有读数）")
class WorkflowEngineAdmissionTest {

  private static final Path WORKFLOW_ROOT =
      Path.of("src", "main", "java", "cn", "lincoln", "aiplatform", "workflow");
  private static final Path MAIN_ROOT = Path.of("src", "main", "java");
  private static final Path DESIGN_DOC = Path.of("..", "docs", "系统设计方案", "09-中间件与-Spring-模块选型.md");

  @Test
  @DisplayName("workflow 模块里只有 package-info.java（没有执行引擎）")
  void theWorkflowModuleHoldsNothingButPackageInfo() throws IOException {
    assertThat(Files.isDirectory(WORKFLOW_ROOT))
        .as("★ `workflow` 模块目录不存在了 —— 目录被搬走时这条断言会变成永远为真")
        .isTrue();

    List<String> files;
    try (Stream<Path> walk = Files.walk(WORKFLOW_ROOT)) {
      files =
          walk.filter(Files::isRegularFile)
              .map(p -> WORKFLOW_ROOT.relativize(p).toString().replace('\\', '/'))
              .sorted()
              .toList();
    }

    assertThat(files)
        .as(
            "★ workflow 模块里出现了实现类 ⇒ 先补 `09 §5.3.4` 的准入表（两段式 / 中断点即拒发布 /"
                + " KeyUpdate / 检查点 L1），再改这条断言。今天这里应当只有一个 package-info")
        .containsExactly("package-info.java");
  }

  @Test
  @DisplayName("执行引擎已落地：#113 六条判据各有行为测试在验（准入表逐行有读数）")
  void eachAdmissionCriterionHasItsOwnBehaviouralTest() throws IOException {
    Path testRoot = Path.of("src", "test", "java");
    // ★ 准入表（09 §5.3.4）逐行 → 谁在哪条测试里验。执行引擎符号今天落在
    //   assistant/application（WorkflowCompiler / NodeExecutor / LinearWorkflowExecutor /
    //   WorkflowNodeContext），所以这里不再是「符号缺席」，而是「六条判据各有会红的行为测试」。
    assertCovered(testRoot, "WorkflowCompilerTest.java", "判据1 · 只编译不跑（图 → 执行计划）");
    assertCovered(
        testRoot, "WorkflowInterruptPublishTest.java", "判据2/6 · 含中断点（人工介入）的图必须拒绝发布（先红后绿）");
    assertCovered(
        testRoot, "WorkflowValidationServiceTest.java", "判据2 · PLANNED_TYPE 升 ERROR + 中断点 + 环检测");
    assertCovered(testRoot, "FlowStateTest.java", "判据3 · KeyUpdate 状态语义（REPLACE/APPEND）");
    assertCovered(testRoot, "LinearWorkflowExecutorL1Test.java", "判据4 · L1 检查点：每节点执行记录与业务同事务");
    assertCovered(
        testRoot, "LinearWorkflowExecutorTest.java", "判据5 · NodeExecutor 注册表按 type 分发 + 只跑线性 4 节点");
  }

  private static void assertCovered(Path testRoot, String fileName, String criterion)
      throws IOException {
    assertThat(testExists(testRoot, fileName))
        .as("★ 准入判据「%s」必须有行为测试 %s 在验 —— 删掉它 = 这条判据失去读数（#113 的根因就是判据没有可核对的读数）", criterion, fileName)
        .isTrue();
  }

  private static boolean testExists(Path testRoot, String fileName) throws IOException {
    try (Stream<Path> walk = Files.walk(testRoot)) {
      return walk.anyMatch(p -> p.getFileName().toString().equals(fileName));
    }
  }

  @Test
  @DisplayName("09 §5.3.4 的准入判据仍在册")
  void theAdmissionTableIsStillInTheDesignDoc() throws IOException {
    assertThat(Files.exists(DESIGN_DOC))
        .as("★ 设计册路径变了：`%s` —— 改名之后这条断言会变成永远为真", DESIGN_DOC)
        .isTrue();
    String doc = Files.readString(DESIGN_DOC, StandardCharsets.UTF_8);

    assertThat(doc)
        .as("★ 准入判据从设计册里消失了 ⇒ 它又变回一句口头建议（`#113` 的根因正是这个）")
        .contains("5.3.4 ★ 执行引擎的准入判据")
        .contains("只编译不跑")
        .contains("KeyUpdate");
  }
}
