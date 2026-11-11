package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判据 · 执行器文件本身仍然存在，且它声明的两个公开入口没被悄悄改掉。
 *
 * <p>★ 2026-11-12 订正：这个类原来是「执行器在主代码里还没有任何调用方」的结构判据， 它按自己写下的三件事在 0.7.0 之后变红了 —— 因为执行器被 {@code
 * WorkflowDebugService} 接进入了口。那条判据已删除， 「接进入口后能产出记录」的正向断言在 {@code WorkflowDebugServiceTest}
 * （successfulRunWritesSucceededRecord / failedRunStillWritesFailedRecord）。
 *
 * <p>留下的这条守的是另一件事：{@code LinearWorkflowExecutor} 的 {@code public void execute(} 与 {@code public
 * static final Set&lt;String&gt; SUPPORTED_TYPES} 是外部依赖的两个锚点（前者被调试服务调用、后者被发布校验读），
 * 它们被改名或降级访问权限时，编译未必报错，运行时才发现没人能调。
 */
class WorkflowExecutionEntryTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");

  /** 执行器自身所在文件 —— 它当然会提到自己的类名，不算调用方。 */
  private static final String DEFINITION_FILE = "LinearWorkflowExecutor.java";

  private static List<Path> javaFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
      return walk.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  /**
   * 找出把执行器当字段类型或构造参数类型用的文件。
   *
   * <p>用「作为类型出现」而不是「调用 .execute(」来判定：要调用它，必然先注入它；反过来， 只出现类名的 javadoc 不算注入（{@code NodeExecutor} /
   * {@code WorkflowNodeContext} 里都有这种提及）。
   */
  private static List<String> injectionPoints() throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path file : javaFiles()) {
      String name = file.getFileName().toString();
      if (name.equals(DEFINITION_FILE)) {
        continue;
      }
      String text = Files.readString(file, StandardCharsets.UTF_8);
      // 只认声明位置：`LinearWorkflowExecutor <变量名>` 或 `LinearWorkflowExecutor,`
      if (text.contains("LinearWorkflowExecutor ") || text.contains("LinearWorkflowExecutor,")) {
        hits.add(name);
      }
    }
    return hits;
  }

  @Test
  @DisplayName("判据 · 名单不是空转：执行器文件确实存在，且它自己是 public 的")
  void definitionIsStillThere() throws IOException {
    Path definition =
        MAIN_JAVA.resolve("cn/lincoln/aiplatform/assistant/application/" + DEFINITION_FILE);
    assertThat(definition).exists();

    String text = Files.readString(definition, StandardCharsets.UTF_8);
    assertThat(text).contains("public void execute(");
    assertThat(text).contains("public static final Set<String> SUPPORTED_TYPES");
  }
}
