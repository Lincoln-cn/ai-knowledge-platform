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
 *   <li>{@code workflow} 模块里**只有** {@code package-info.java}；
 *   <li>{@code src/main/java} 里没有 {@code NodeExecutor} 一类执行引擎符号；
 *   <li>{@code 09 §5.3.4} 那张准入表**还在**（被删/被搬走 ⇒ 红）。
 * </ol>
 *
 * <p>引擎真正落地时这三条会红 —— ★ 那时正确的动作**不是删断言**，而是把准入表逐行换成 "谁在哪条测试里验"，再回来改这里。
 */
@DisplayName("执行引擎：准入判据仍在册，而实现尚未开始")
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
  @DisplayName("src/main/java 里没有 NodeExecutor / 执行计划一类符号")
  void thereIsNoExecutionEngineSymbolYet() throws IOException {
    List<String> offenders = new java.util.ArrayList<>();
    try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.contains("NodeExecutor")
            || source.contains("ExecutionPlan")
            || source.contains("WorkflowInterpreter")) {
          offenders.add(MAIN_ROOT.relativize(file).toString());
        }
      }
    }

    assertThat(offenders).as("★ 执行引擎的符号出现了 ⇒ 先补 `09 §5.3.4`，尤其是「图 → 执行计划 → 执行」的只编译不跑测试").isEmpty();
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
