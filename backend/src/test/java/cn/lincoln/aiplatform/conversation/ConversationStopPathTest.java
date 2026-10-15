package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「停止」这条前端路径的<strong>静态断言</strong>（{@code #119} 的判据⑤：一条<strong>能红</strong>的断言）。
 *
 * <h3>★★ 为什么它必须存在（而不是"读代码看着对"）</h3>
 *
 * <p>本批最容易被后人"顺手改回去"的一步是：{@code stop()} 里<strong>先发停止请求、再 {@code abort()}</strong>。
 * 两个方向都是静默失效，而且界面看起来都对：
 *
 * <ul>
 *   <li><strong>只 {@code abort()} 不发请求</strong> ⇒ 服务端分不出"用户停止"与"连接断了" ⇒ 终态变成 {@code
 *       FAILED}（用户看到"出故障了"）—— 这正是 {@code #119}；
 *   <li><strong>先 {@code abort()} 再发请求</strong> ⇒ 服务端那边连接已经没了，停止请求到得太晚 ⇒ 同样是 {@code
 *       FAILED}（顺序反了，形状一样，读数一样坏）；
 *   <li><strong>只发请求不 {@code abort()}</strong> ⇒ 流一直挂着、界面停在"生成中"（{@code sse.ts} 的"恰好一次 onDone"里那条
 *       abort 分支永远走不到）。
 * </ul>
 *
 * <p>三种都<strong>不会让任何测试变红</strong>（前端没有测试框架），所以这里对源码做一次结构断言： <strong>停止路径这一段里，显式停止请求必须出现在 {@code
 * abort()} 之前，且 {@code abort()} 必须在 {@code finally} 里（= 请求失败也要收尾）</strong>。
 *
 * <h3>★★ 这条规则自己也要能被证伪（同 {@code ContractDriftTest} 的写法）</h3>
 *
 * <p>同一条规则跑三组数据：真实源码必须过、两个坏样本必须不过、一个<strong>好样本必须过</strong> （最后那条排掉"规则恒抛异常"这种假绿 ——
 * 只有坏样本时，"永远抛"也能让金丝雀全绿）。
 *
 * <p>★ 它<strong>不</strong>需要 Docker / Spring：读两个文本文件即可，所以它是本批最快的一条读数， 也可以在任何构建机上单独跑。
 */
class ConversationStopPathTest {

  /** 停止路径的实现处（{@code stop} 与它调用的 {@code requestServerStop} 都在这个文件里）。 */
  private static final String STOP_PATH_FILE =
      "frontend/src/pages/conversation/composables/useChatSession.ts";

  /** 契约文件（停止端点的那条声明在它里面）。 */
  private static final String CONTRACT_FILE = "frontend/src/api/conversations.ts";

  private static final String STOP_DECLARATION = "const stop = () => {";

  private static final String REQUEST_DECLARATION = "const requestServerStop = ";

  // ────────────────────────── 主断言 ──────────────────────────

  /**
   * ★★ 停止路径：<strong>先发显式停止请求，再 {@code abort()}</strong>（两件都要做）。
   *
   * <p>★ 可证伪：把 {@code useChatSession.ts} 的 {@code stop} 改回"只 abort"（本批之前的样子） ⇒
   * 这一条立刻红，报"停止路径里没有显式停止请求"。
   */
  @Test
  void theStopPathMustSendTheExplicitStopRequestBeforeAborting() {
    String source = read(stopPathFile());
    assertStopPathSendsRequestBeforeAbort(stopPathRegion(source), "真实停止路径");
  }

  /**
   * ★★ 契约必须声明这条端点（{@code POST /conversations/:conversationId/messages/:messageId/stop}）。
   *
   * <p>它与 {@code ContractDriftTest} 的分工：那边管"契约与后端<strong>双向</strong>对得上"（要 Docker），
   * 这边只管"契约里这一条还在、方法还是 POST"—— 一条不依赖容器的贴身断言。
   */
  @Test
  void theContractMustStillDeclareTheStopEndpoint() {
    String contract = read(contractFile());
    int[] region = functionRegion(contract, "export const stopMessage");
    String declaration = contract.substring(region[0], region[1]);
    assertThat(declaration)
        .as("★ 停止端点必须仍在契约里，且方法与路径逐字如此（删掉它 ⇒ 前端又会退化成只 abort）")
        .contains("method: 'POST'")
        .contains("url: '/conversations/:conversationId/messages/:messageId/stop'");
  }

  // ────────────────────────── 金丝雀（规则必须能被证伪）──────────────────────────

  /** 坏样本 A：只有 {@code abort()}，没有显式停止请求（= 本批之前的样子）⇒ 必须被抓到。 */
  @Test
  void canary_anAbortWithoutTheStopRequestMustBeCaught() {
    assertThatThrownBy(
            () ->
                assertStopPathSendsRequestBeforeAbort(
                    "const stop = () => { handle.abort() }", "坏样本：只 abort"))
        .as("坏样本没被拦下 ⇒ 这条闸门已经失效，真实源码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /** 坏样本 B：先 {@code abort()} 再发请求（顺序反了）⇒ 必须被抓到。 */
  @Test
  void canary_aStopRequestAfterTheAbortMustBeCaught() {
    assertThatThrownBy(
            () ->
                assertStopPathSendsRequestBeforeAbort(
                    "const stop = () => { target.abort() }\n"
                        + "const requestServerStop = async () => {"
                        + " await conversationsApi.stopMessage(c, m) }",
                    "坏样本：先 abort 再请求"))
        .as("顺序反了没被拦下 ⇒ 服务端仍会把停止判成断开，而这条闸门全程绿")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * ★ 好样本必须<strong>通过</strong>（排掉"规则恒抛异常"这种假绿）。
   *
   * <p>★ 没有这一条，上面两个金丝雀是<strong>没有信息量</strong>的：一个"永远抛"的实现能让它们全绿。
   */
  @Test
  void canary_aCorrectStopPathMustPass() {
    assertThatCode(
            () ->
                assertStopPathSendsRequestBeforeAbort(
                    "const requestServerStop = async () => {"
                        + " await conversationsApi.stopMessage(c, m) }\n"
                        + "const stop = () => { void requestServerStop(c, m).finally(() =>"
                        + " target.abort()) }",
                    "好样本"))
        .as("正确形状被误判成违规 ⇒ 这条闸门会挡住正确的改法（误报与漏报一样坏）")
        .doesNotThrowAnyException();
  }

  // ────────────────────────── 规则本体 ──────────────────────────

  /**
   * 规则：停止路径这一段里，显式停止请求必须出现在 {@code abort()} <strong>之前</strong>， 且 {@code abort()} 必须在 {@code
   * finally} 里（请求失败也要收尾）。
   *
   * @param stopPathRegion 停止路径那一整段源码（{@code requestServerStop} + {@code stop}）
   * @param label 出错时告诉调用方"验的是哪一组数据"
   */
  private static void assertStopPathSendsRequestBeforeAbort(String stopPathRegion, String label) {
    int requestAt = stopPathRegion.indexOf("conversationsApi.stopMessage(");
    int abortAt = stopPathRegion.indexOf(".abort()");
    if (requestAt < 0) {
      throw new AssertionError(
          label
              + "：停止路径里没有显式停止请求（conversationsApi.stopMessage）—— 只 abort() 的话，"
              + "服务端只看到「连接没了」，分不出「用户点了停止」与「网线掉了」（#119）");
    }
    if (abortAt < 0) {
      throw new AssertionError(
          label
              + "：停止路径里没有 abort() —— 只发请求不 abort 会让流一直挂着、界面停在「生成中」"
              + "（sse.ts 里那条 abort 收尾分支也就永远走不到）");
    }
    if (requestAt > abortAt) {
      throw new AssertionError(
          label + "：先 abort() 再发停止请求 —— 服务端那边连接已经没了，停止请求到得太晚，" + "终态仍会落到 FAILED（顺序反了，形状一样，读数一样坏）");
    }
    if (!stopPathRegion.contains(".finally(")) {
      throw new AssertionError(
          label
              + "：abort() 不在 finally 里 —— 停止请求失败（网络 / 404 / 超时）时这一轮就没人收尾了，"
              + "而「失败不能把用户卡住」正是这条路径的要求");
    }
  }

  /** 停止路径那一整段：从 {@code requestServerStop} 与 {@code stop} 两段里<strong>较早</strong>的开始到较晚的结束。 */
  private static String stopPathRegion(String source) {
    int[] request = functionRegion(source, REQUEST_DECLARATION);
    int[] stop = functionRegion(source, STOP_DECLARATION);
    return source.substring(Math.min(request[0], stop[0]), Math.max(request[1], stop[1]));
  }

  /**
   * 一个声明在源码里的区间 {@code [开始, 结束)}：从 {@code declaration} 起到与它第一对配对的右花括号。
   *
   * <p>★ 字符串 / 模板串里的花括号必须跳过（否则一个 {@code `${x}`} 就会把区间截断）—— 与 {@code ContractDriftTest#matchBrace}
   * 同一条口径。
   */
  private static int[] functionRegion(String source, String declaration) {
    int at = source.indexOf(declaration);
    if (at < 0) {
      throw new AssertionError(
          "源码里找不到「" + declaration + "」—— 停止路径的形状变了。" + "这条闸门不能静默跳过：找不到就要人来确认改法，而不是让它永远绿");
    }
    int open = source.indexOf('{', at);
    if (open < 0) {
      throw new AssertionError("「" + declaration + "」后面找不到函数体（{）：形状变了");
    }
    int depth = 0;
    char quote = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (quote != 0) {
        if (c == '\\' && i + 1 < source.length()) {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        quote = c;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return new int[] {at, i + 1};
        }
      }
    }
    throw new AssertionError("「" + declaration + "」的花括号不配对 —— 源码被截断了");
  }

  // ────────────────────────── 文件定位 ──────────────────────────

  /**
   * 定位仓库里的一个相对路径。
   *
   * <p>★ 与 {@code ContractDriftTest#resolveFrontendApiDir()} 同一条口径：surefire 的工作目录是 {@code
   * backend/}， 而本地直接跑 / IDE 里可能是项目根。两个候选都试；<strong>找不到必须失败</strong> —— 静默跳过会让这条闸门永远绿而什么都不查。
   */
  private static Path resolve(String relative) {
    Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    List<Path> candidates = new ArrayList<>();
    for (Path base : List.of(userDir, userDir.resolve(".."), userDir.resolve("../.."))) {
      candidates.add(base.resolve(relative).normalize());
    }
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "找不到 "
            + relative
            + "。试过："
            + candidates
            + "；user.dir="
            + userDir
            + "。本类不能静默跳过 —— 它验的正是那个文件里的顺序。");
  }

  private static Path stopPathFile() {
    return resolve(STOP_PATH_FILE);
  }

  private static Path contractFile() {
    return resolve(CONTRACT_FILE);
  }

  private static String read(Path path) {
    try {
      return String.join("\n", Files.readAllLines(path, StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw new UncheckedIOException("读取失败：" + path, failure);
    }
  }
}
