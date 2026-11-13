package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从主代码源码里读出「到底有哪些执行器、各自处理哪种节点」—— 构建期判据的共用数据源（{@code #337}）。
 *
 * <p>★ 为什么扫源码而不是起 {@code @SpringBootTest}：本类只回答"有没有这类 bean"，而起上下文要连数据库，
 * <strong>一条结构判据不该被环境判据绑架</strong>（这条理由是 {@code NodeExecutorRegistryTest} 当初写下的，这里沿用）。
 * 代价是扫不到"运行期才注册的 bean"；本仓执行器全是无条件的 {@code @Component}，静态可见，可接受。
 *
 * <p>★ 它替代的是原来那份手写的 {@code LinearWorkflowExecutor.SUPPORTED_TYPES}。删掉手写清单之后，"能跑哪些类型"
 * 变成了两个可核对的来源：<strong>运行时</strong>看执行器装配（{@code registry.keySet()}），<strong>构建期</strong>看这里
 * （源码扫描）。两者与节点目录的 {@code executable} 由 {@code WorkflowNodeTypeTest} 钉成完全相等。
 */
public final class NodeExecutorSources {

  private NodeExecutorSources() {}

  /**
   * 主代码里所有 {@code implements NodeExecutor} 的类，以及它们 {@code type()} 返回的字面量。
   *
   * <p>★ 要求 {@code type()} 写成 {@code return "字面量"}：读不出就红，而不是静默漏掉一个执行器 ——
   * 漏掉一个等于这条判据在维护"不完全的名单"，那比没有判据更坏。
   */
  public static Set<String> implementedTypes() throws IOException {
    Path sourceRoot = Path.of("src", "main", "java");
    assertThat(sourceRoot).as("测试工作目录应在 backend/，实际 %s", Path.of("").toAbsolutePath()).exists();

    Pattern typeReturn =
        Pattern.compile(
            "String\\s+type\\s*\\(\\s*\\)\\s*\\{\\s*return\\s*\"([A-Z_]+)\"", Pattern.DOTALL);

    Set<String> types = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
      for (Path file : javaFiles) {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        if (!src.contains("implements NodeExecutor")) {
          continue;
        }
        Matcher m = typeReturn.matcher(src);
        if (!m.find()) {
          throw new AssertionError(
              file
                  + " 实现了 NodeExecutor 但 type() 不是 `return \"字面量\"` 的形式，本判据读不出它处理哪种节点"
                  + " —— 请改成返回字面量，或同步更新本判据");
        }
        types.add(m.group(1));
      }
    }
    return types;
  }
}
