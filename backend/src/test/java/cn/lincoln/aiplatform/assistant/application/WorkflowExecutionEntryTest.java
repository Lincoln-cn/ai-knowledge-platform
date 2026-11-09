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
 * 判据 · 执行器今天还没有任何调用方，这条事实必须留在账上，不能只写在 issue 里。
 *
 * <p>背景：{@link LinearWorkflowExecutor#execute} 是 {@code public}，看起来随时能用，但主代码里没有 任何一个类把它注入进来 ——
 * 也就是说「发布校验说这四种节点能跑」与「点了试用真的会跑」之间还缺三层 （入口 / 创建执行记录 / 调用 execute），见 {@code
 * docs/技术结论/326-执行引擎缺口与接线路径.md}。
 *
 * <p>这条断言守的是「别把未接线当成已接线」：一旦有人把执行器接进入口，它会红，逼着改的人回来把 清单与文档一起更新 —— 而不是让「能跑」这个结论继续只存在于提交信息里。
 *
 * <p>★ 为什么扫源码而不是起 Spring 上下文：这是一条结构判据，不该被数据库、端口这些环境条件绑架 （{@code NodeExecutorRegistryTest}
 * 第一版就是栽在这里，{@code Errors: 1}）。
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
  @DisplayName("判据 · 执行器在主代码里还没有任何注入点（接了就要回来改这一条）")
  void executorHasNoInjectionPointYet() throws IOException {
    List<String> hits = injectionPoints();

    assertThat(hits)
        .as(
            "执行器被接进入口了。这不是坏事，但必须同步做三件事："
                + "① 删掉 docs/技术结论/326 里「链条是断的」那一节；"
                + "② 把 WorkflowRecordRepository 的 save 补上并真的写一条记录；"
                + "③ 给这条判据换成「接进入口后能产出记录」的正向断言")
        .isEmpty();
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
