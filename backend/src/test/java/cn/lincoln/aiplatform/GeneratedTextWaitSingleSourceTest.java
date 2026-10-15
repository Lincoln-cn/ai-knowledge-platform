package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★「等库里正文非空」全仓<strong>只有一把</strong>实现，且<strong>驱动停止的测试必须用它</strong> —— Issue {@code #125} 的收尾。
 *
 * <h3>它拦的是"下一个人再抄一把"这个动作，不是某一份文件</h3>
 *
 * <p>同一个形状已经出过两次事：{@code ChatStreamHttpTest} 与 {@code ConversationStopEndpointTest}
 * 各自写了一把<strong>等价但独立</strong>的等待 （{@link
 * cn.lincoln.aiplatform.conversation.support.AssistantGeneratedTextWait} 出现之前）， 而 {@code #119}
 * 的新用例又把 {@code #125} 的老洞<strong>原样抄了一遍</strong>。⇒ 所以这条判据是<strong>全仓扫描</strong>，且两条规则各守一半：
 *
 * <ol>
 *   <li><strong>恰好一处实现</strong>：扫 {@code src/test/java} 全部源码，"把消息正文与空串比较"这种<strong>等待判据</strong>
 *       只允许出现在那把唯一的等待里（第二处 ⇒ 红）；
 *   <li><strong>停止用例必须引用它</strong>：凡<strong>驱动了一次停止</strong>的测试文件（显式停止信号的调用、或停止端点的路径片段），
 *       必须存在对那把等待的<strong>调用点</strong>（只留一个没用的 import 不算 —— 见 {@link #USES_SINGLE_WAIT}）。
 * </ol>
 *
 * <h3>★★ 为什么它不会假绿</h3>
 *
 * <ul>
 *   <li>扫描根不存在 / 路径写错 ⇒ {@code Files.walk} 直接抛，而不是"零命中 ⇒ 绿"；
 *   <li>规则①的期望是"<strong>命中集合恰好一项，且那一项就是那把等待</strong>"：它同时挡住"多了一处"与"一处都没有了"；
 *   <li>规则②先断言"确实扫到了驱动停止的测试"—— 否则规则②会退化成空转，而"空转的绿"与"没人违反的绿"读数完全一样 （同一般纪律：一条会静默跳过的哨兵等于没有哨兵）。
 * </ul>
 *
 * <h3>★★ 已知边界（写下来，免得它被读成"万能"）</h3>
 *
 * <p>规则②是<strong>按文件</strong>判的：一个文件里只要有一处引用了那把等待，它的其余停止用例就不再被逐条核对 （逐条要按方法切分源码，收益不抵复杂度）。
 * 所以它拦的是"整个文件回到等状态"这种形状，不是"某一条用例偷偷改回去"。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring、不需要 Docker</strong>：读的是测试源码文本，因此它在任何机器上都跑出同一份结论。
 */
class GeneratedTextWaitSingleSourceTest {

  /** 测试树根 —— surefire 的工作目录 = {@code backend/}（与 {@code TestNamingConventionTest} 同源）。 */
  private static final Path TEST_ROOT = Path.of("src", "test", "java");

  /** 那把唯一的等待：类名。 */
  private static final String SINGLE_WAIT = "AssistantGeneratedTextWait";

  /** 它的相对路径 —— 规则①的期望答案（"恰好一处"里的那一处）。 */
  private static final String SINGLE_WAIT_SOURCE =
      "cn/lincoln/aiplatform/conversation/support/" + SINGLE_WAIT + ".java";

  /** 本类自己：判据形状与那个类名都写在常量里，扫进去会自己抓自己（同 TestNamingConventionTest 踩过的"闸门抓自己"）。 */
  private static final String SELF = "cn/lincoln/aiplatform/GeneratedTextWaitSingleSourceTest.java";

  /**
   * 「等正文非空」的实现形状：把 {@code content} 与空串比较。
   *
   * <p>★ 容忍空格、一层收尾括号与 not-equal 写法（外面套一层 {@code trim(…)} 也算同一口径）。
   * 它认的是"<strong>谁在等正文非空</strong>"，不是某一份文件的某一行。
   */
  private static final Pattern CONTENT_NOT_EMPTY =
      Pattern.compile("content\\s*\\)?\\s*(?:<>|!=)\\s*''");

  /** 「这个测试驱动了一次停止」的证据：显式停止信号的调用、或停止端点的路径片段（★ 认的是调用与端点，不是文件名）。 */
  private static final Pattern STOP_TRIGGER = Pattern.compile("requestStop\\(|\"/stop\"");

  /** 「这个文件引用了那把唯一的等待」：★ 认的是<strong>调用点</strong>（类名后跟一个点），留着一个没用到的 import 不算。 */
  private static final Pattern USES_SINGLE_WAIT = Pattern.compile(SINGLE_WAIT + "\\s*\\.");

  @Test
  void theGeneratedTextWaitHasExactlyOneImplementation() {
    List<Hit> hits = new ArrayList<>();
    forEachTestSource(
        (relative, lines) -> {
          for (int i = 0; i < lines.size(); i++) {
            if (CONTENT_NOT_EMPTY.matcher(lines.get(i)).find()) {
              hits.add(new Hit(relative, i + 1, lines.get(i).strip()));
            }
          }
        });

    assertThat(hits)
        .as(
            """
            ★ 「等库里正文非空」的实现必须**恰好一处**：两把等价而不共享的等待并存时，下一个人就会照着抄第三把
            （#119 的新用例正是这么把 #125 的老洞抄了一遍）。要改判据请改那把唯一的等待（%s），
            不要就地再写一份。命中处（相对 src/test/java）：%s\
            """,
            SINGLE_WAIT_SOURCE, render(hits))
        .hasSize(1);
    assertThat(hits.getFirst().relative())
        .as("★ 正控：那唯一一处必须就是那把共用的等待（%s）—— 否则'恰好一处'可能落在某个新写的私有 helper 上", SINGLE_WAIT_SOURCE)
        .isEqualTo(SINGLE_WAIT_SOURCE);
  }

  @Test
  void stopTestsMustReferenceTheSingleWait() {
    List<String> stopTests = new ArrayList<>();
    List<String> offenders = new ArrayList<>();
    forEachTestSource(
        (relative, lines) -> {
          if (lines.stream().noneMatch(line -> STOP_TRIGGER.matcher(line).find())) {
            return;
          }
          stopTests.add(relative);
          if (lines.stream().noneMatch(line -> USES_SINGLE_WAIT.matcher(line).find())) {
            offenders.add(relative);
          }
        });

    assertThat(stopTests)
        .as(
            """
            ★ 正控：扫描确实看到了驱动停止的测试 —— 一处都没有时规则②就是空转，那种绿与"扫描路径写错了"不可区分。
            扫到的文件：%s\
            """,
            stopTests)
        .isNotEmpty();
    assertThat(offenders)
        .as(
            """
            ★ 这些测试驱动了一次停止，却没有引用那把唯一的等待（%s）：在真栈上"等状态 GENERATING"与
            "模型已经吐过第一个字"是两件事 —— 助手行在第一个 chunk 之前就有状态，拿它当观测点会让
            "停止保留已生成内容"这条判据变成偶发红（#125 实测 4 次运行绿/红/红/绿）。
            ★ 本闸门按**文件**判：某个停止用例若原理上等不到"库里正文非空"（例如假模型吐一块就挂住、
            落库只发生在收尾那一刻），它可以用自己的闩，但文件里仍必须保留那一处真实用法。
            违反的文件：%s\
            """,
            SINGLE_WAIT_SOURCE, offenders)
        .isEmpty();
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 一处命中：文件 + 行号 + 那一行（★ 报告里要能直接点出"下一个人该删哪一行"）。 */
  private record Hit(String relative, int lineNumber, String line) {}

  private interface SourceVisitor {
    void accept(String relative, List<String> lines);
  }

  /**
   * 遍历测试树里的每一个 {@code .java}（除本类自己）—— 交给访问者"文件 + 行清单"。
   *
   * <p>★ 读的是<strong>文本</strong>而不是编译产物：判据谈的是"这个形状在源码里出现了几次"， 而它要拦的复制动作（抄一行 SQL）在字节码里读不出来。
   */
  private static void forEachTestSource(SourceVisitor visitor) {
    try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> new Source(slash(TEST_ROOT.relativize(path)), read(path)))
          .filter(source -> !SELF.equals(source.relative()))
          .forEach(source -> visitor.accept(source.relative(), source.lines()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record Source(String relative, List<String> lines) {}

  private static String render(List<Hit> hits) {
    if (hits.isEmpty()) {
      return "（零命中 —— 那把等待的判据可能已被改写，这条闸门的前提要重新核）";
    }
    return hits.stream()
        .map(hit -> hit.relative() + ":" + hit.lineNumber() + " → " + hit.line())
        .collect(Collectors.joining("\n"));
  }

  private static List<String> read(Path path) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }
}
