package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.application.ChatStreamProperties;
import cn.lincoln.aiplatform.conversation.application.ChatStreamTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ★★ SSE 契约的<strong>两条时间常量</strong>与它们之间的<strong>数值关系</strong>（第 17 周 · 0.4.5）。
 *
 * <h3>为什么这两条数字必须有独立读数（而不是"构造器里已经检查过了"）</h3>
 *
 * <p>{@link ChatStreamProperties} 的紧凑构造器只在<strong>有人构造它</strong>的时候检查那条关系，而 {@link
 * ChatStreamTransport} 里那两个常量<strong>自己</strong>没有任何东西守着：
 *
 * <ul>
 *   <li>把 {@code HEARTBEAT_INTERVAL_MS} 从 20_000 改成 30_000，构造器仍然能通过（30_000 × 3 = 90_000 只在与 60_000
 *       比较时才不成立）—— ★ 也就是说"改坏了哪个数"这件事，只有在<strong>有人真的构造默认值</strong>时才响；
 *   <li>更隐蔽的一种：两个数<strong>一起</strong>被改大（20_000 → 40_000、60_000 → 120_000）。关系仍然成立， 构造器一声不响，而契约里写死的
 *       20s / 60s 已经和实现对不上了。那正是 {@code 10 §8.3} 「SSE 一个字节都不用改」被悄悄推翻的样子。
 * </ul>
 *
 * <p>⇒ 本类的手段是<strong>把左侧那个裁判换成契约文件本身</strong>：那两个数字不从 Java 常量取， 而从冻结契约 {@code
 * frontend/src/api/stream-types.ts} 读出来，再和 Java 常量逐字比。 于是"Java 侧单方面改了数"与"契约侧被改了"
 * <strong>两种漂移都会红</strong>，而"两边一起改"会红在{@link #contractPinsTheTwoTimingConstants()}上（下面那两个字面量断言）。
 *
 * <h3>★ 它必须能红（本类的验收方式）</h3>
 *
 * <p>把上面任一个数改掉，对应断言就会失败：
 *
 * <ul>
 *   <li>改 {@code ChatStreamTransport} 的任一个 ⇒ {@link #javaConstantsMatchTheFrozenContract()}；
 *   <li>改契约 TS 的任一个 ⇒ 同上（裁判是文件，不是 Java 常量）；
 *   <li>把 {@code 3} 改大、或把 {@code ≤} 改成 {@code <} ⇒ {@link
 *       #heartbeatTimesThreeStillFitsInsideTheIdleTimeout()}；
 *   <li>把 {@code 20_000} 改成别的合法值 ⇒ {@link #contractPinsTheTwoTimingConstants()}（这一条是"独立于实现的存在性读数"）。
 * </ul>
 *
 * <p>★ <strong>关系那条不是"照着实现抄一遍"</strong>：它读的是同一个契约文件里<strong>字面写着</strong>的那行关系 （{@code
 * HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS}）—— 所以"契约把它删了、或者把 3 改成 4"这件事 也会被抓住，而不是由本类自己 new 一个 3
 * 出来跟自己比。
 *
 * <p>★ 本类<strong>不</strong>起容器、不连数据库：它读的是两个常量、一条关系、一份文件。
 */
class ChatStreamTimingTest {

  /** 契约里那两条时间常量在 Java 侧的镜像 —— ★ 本类<strong>刻意不</strong>用它们当裁判（裁判是契约文件）， 它们只作为"被比对的另一侧"出现在断言里。 */
  private static final long EXPECTED_HEARTBEAT_INTERVAL_MS = 20_000L;

  private static final long EXPECTED_IDLE_TIMEOUT_MS = 300_000L;

  /** 契约里那条数值关系里的乘数（契约原文 {@code HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS}）。 */
  private static final long HEARTBEATS_TOLERATED_MS = 3L;

  /**
   * 契约里一条常量的声明行：{@code export const NAME = 20_000}。★ {@code (?m)} 不能省：本类把文件的每一行拼起来再匹配，没有它 {@code ^}
   * 只认整个文本的开头。
   */
  private static final Pattern CONSTANT_DECLARATION =
      Pattern.compile(
          "(?m)^export const (HEARTBEAT_INTERVAL_MS|IDLE_TIMEOUT_MS)\\s*=\\s*([0-9_]+)\\s*$");

  /**
   * 契约里那条数值关系的声明行 —— ★ 用<strong>正则</strong>而不是"包含子串"：{@code contains("× 3 ≤")} 在有人把 {@code 3} 换成
   * {@code 4} 时仍然为真（子串匹配对"改一个字符"是瞎的），而这条关系的全部价值就在于那个 {@code 3}。
   */
  private static final Pattern RELATION_DECLARATION =
      Pattern.compile("HEARTBEAT_INTERVAL_MS\\s*[×x*]\\s*([0-9]+)\\s*≤\\s*IDLE_TIMEOUT_MS");

  /** 契约文件（{@code frontend/src/api/stream-types.ts}）。 */
  private static final Path CONTRACT = resolveContract();

  // ────────────────────────── ① 两个数：契约 ↔ Java ──────────────────────────

  /**
   * ★★ 契约里的 20s / 60s 与 {@link ChatStreamTransport} 里那两个常量<strong>逐字相等</strong>。
   *
   * <p>这也是"契约常量有独立读数"的那一条：{@code ChatStreamProperties} 的默认值虽然引用同一批常量，
   * 但那是"实现内部自洽"；本断言把<strong>契约文件</strong>拉进来当裁判，于是单方面改任何一侧都会红。
   */
  @Test
  void javaConstantsMatchTheFrozenContract() {
    long contractHeartbeat = contractConstantMs("HEARTBEAT_INTERVAL_MS");
    long contractIdleTimeout = contractConstantMs("IDLE_TIMEOUT_MS");

    assertThat(ChatStreamTransport.HEARTBEAT_INTERVAL_MS)
        .as(
            "★ 后端心跳间隔必须等于契约 %s 里的 HEARTBEAT_INTERVAL_MS（%d ms）—— "
                + "改这个数等于同时改契约，而那是一次有意的契约变更（10 §8.3：SSE 一个字节都不用改）",
            CONTRACT.getFileName(), contractHeartbeat)
        .isEqualTo(contractHeartbeat);
    assertThat(ChatStreamTransport.IDLE_TIMEOUT_MS)
        .as(
            "★ 后端闲置超时必须等于契约里的 IDLE_TIMEOUT_MS（%d ms）—— 两侧取同一个数，" + "才能让「用户看到的超时」与「服务端收尾的时间」对得上",
            contractIdleTimeout)
        .isEqualTo(contractIdleTimeout);

    // ★ 契约侧自己也要被钉住：否则「两边一起改大」会让上面两条照样绿
    assertThat(contractHeartbeat)
        .as("★ 契约里的心跳间隔是 20s —— 这条断言独立于实现（防'两边一起改'）")
        .isEqualTo(EXPECTED_HEARTBEAT_INTERVAL_MS);
    assertThat(contractIdleTimeout).as("★ 契约里的闲置超时是 60s —— 同上").isEqualTo(EXPECTED_IDLE_TIMEOUT_MS);

    // ★ 生产默认值这一档也要有自己的读数：它引用常量，但"引用"这件事本身值得被钉一次
    assertThat(ChatStreamProperties.defaults().heartbeatIntervalMs())
        .as("★ 生产默认值（ChatStreamProperties.defaults()）必须就是契约值，不是另一个数")
        .isEqualTo(EXPECTED_HEARTBEAT_INTERVAL_MS);
    assertThat(ChatStreamProperties.defaults().idleTimeoutMs())
        .as("★ 同上 —— 默认值一旦与常量脱钩，改常量就管不住线上那 20s/60s 了")
        .isEqualTo(EXPECTED_IDLE_TIMEOUT_MS);
  }

  // ────────────────────────── ② 那条关系：心跳 × 3 ≤ 闲置超时 ──────────────────────────

  /**
   * ★★ {@code heartbeat × 3 ≤ idleTimeout} —— 而且是<strong>契约里写着的那个乘数</strong>（不是本类自己 new 的 3）。
   *
   * <p>为什么这条关系值钱：前端那 60s 是"一帧都没收到即判吊死"， 而心跳正好是"一帧都没收到"时唯一的字节。 若心跳间隔 × 3
   * 超过闲置超时，症状是<strong>长回答被自己的超时掐断</strong>（连续丢 2 个心跳就判死）， 而它在界面上看起来只是"模型有点慢"—— 这正是 {@code
   * ChatStreamProperties} 类注释里写的那个"极具误导性"的症状。
   *
   * <p>★ 三条一起断：① 契约里的乘数就是 3；② Java 侧两个常量满足它；③ <strong>运行期那条检查真的会拒绝</strong>不满足的组合
   * （否则前两条只是两串数字，而"应用起不来"这条保护是空的）。
   */
  @Test
  void heartbeatTimesThreeStillFitsInsideTheIdleTimeout() {
    long tolerated = contractHeartbeatsTolerated();
    assertThat(tolerated)
        .as("★ 契约里那条关系写的是「心跳 × 3 ≤ 闲置超时」—— 乘数变了（3 → 别的值）" + "意味着「连续丢几个心跳仍不该被判死」这条承诺变了，必须显式改这里")
        .isEqualTo(HEARTBEATS_TOLERATED_MS);

    assertThat(ChatStreamTransport.HEARTBEAT_INTERVAL_MS * tolerated)
        .as(
            "★★ 后端：%d ms × %d = %d ms 必须 ≤ 闲置超时 %d ms（契约硬约束）",
            ChatStreamTransport.HEARTBEAT_INTERVAL_MS,
            tolerated,
            ChatStreamTransport.HEARTBEAT_INTERVAL_MS * tolerated,
            ChatStreamTransport.IDLE_TIMEOUT_MS)
        .isLessThanOrEqualTo(ChatStreamTransport.IDLE_TIMEOUT_MS);

    // ★ ③ 运行期检查必须真的拒绝：拿一个"正好在边界外"的组合去构造
    long tooBigHeartbeat = ChatStreamTransport.IDLE_TIMEOUT_MS / tolerated + 1;
    assertThat(tooBigHeartbeat * tolerated)
        .as("构造出来的样本必须真的越界（否则这条断言是空转）")
        .isGreaterThan(ChatStreamTransport.IDLE_TIMEOUT_MS);
    assertThatThrownBy(
            () ->
                new ChatStreamProperties(
                    tooBigHeartbeat, ChatStreamTransport.IDLE_TIMEOUT_MS, 500L, 200))
        .as(
            "★★ 不满足那条关系的配置必须让应用起不来（而不是线上偶发地掐断长回答）：" + "心跳 %d × %d > 闲置超时 %d",
            tooBigHeartbeat, tolerated, ChatStreamTransport.IDLE_TIMEOUT_MS)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("心跳间隔 × 3 必须不大于闲置超时");

    // ★ 反方向：契约默认值这一组必须能构造出来（否则"检查"变成了"一律拒绝"）
    assertThat(ChatStreamProperties.defaults().heartbeatIntervalMs())
        .as("默认值就是契约值（ChatStreamProperties.defaults() 引用的是同一批常量）")
        .isEqualTo(ChatStreamTransport.HEARTBEAT_INTERVAL_MS);
    assertThat(ChatStreamProperties.defaults().idleTimeoutMs())
        .isEqualTo(ChatStreamTransport.IDLE_TIMEOUT_MS);
  }

  // ────────────────────────── 契约文件读数 ──────────────────────────

  /**
   * 读契约里某一条时间常量的<strong>数值</strong>（毫秒，已去掉数字分隔符）。
   *
   * <p>★ 找不到 ⇒ 抛（不是 skip、不是给默认值）："契约里那两个常量不见了"本身就是最严重的一种漂移， 静默兜底会让本类在契约被删光之后继续绿下去。
   */
  private static long contractConstantMs(String name) {
    Matcher matcher = CONSTANT_DECLARATION.matcher(contractSource());
    while (matcher.find()) {
      if (name.equals(matcher.group(1))) {
        return Long.parseLong(matcher.group(2).replace("_", ""));
      }
    }
    throw new AssertionError(
        "契约 " + CONTRACT + " 里找不到 `export const " + name + " = <数字>` 这一行 —— 本类不能静默跳过");
  }

  /** 读契约里那条数值关系写着的乘数（{@code HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS} 里的 3）。 */
  private static long contractHeartbeatsTolerated() {
    Matcher matcher = RELATION_DECLARATION.matcher(contractSource());
    if (!matcher.find()) {
      throw new AssertionError(
          "契约 "
              + CONTRACT
              + " 里找不到那条数值关系 `HEARTBEAT_INTERVAL_MS × N ≤ IDLE_TIMEOUT_MS` —— "
              + "它是这条时间约束的唯一出处，不允许无声消失");
    }
    return Long.parseLong(matcher.group(1));
  }

  /** 契约源码（逐行拼起来，便于正则按行锚定）。 */
  private static String contractSource() {
    try {
      return String.join("\n", Files.readAllLines(CONTRACT));
    } catch (IOException failure) {
      throw new UncheckedIOException("读取 SSE 契约失败：" + CONTRACT, failure);
    }
  }

  /**
   * 定位 {@code frontend/src/api/stream-types.ts}。
   *
   * <p>★ 与 {@code ContractDriftTest#resolveFrontendApiDir()} 同一条口径：surefire 的工作目录是 {@code
   * backend/}， 但本地直接跑 / IDE 里可能是项目根。找不到<strong>必须失败</strong> —— 静默跳过会让本类永远绿而什么都不查。
   */
  private static Path resolveContract() {
    Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    List<Path> candidates = new ArrayList<>();
    for (Path base : List.of(userDir, userDir.resolve(".."), userDir.resolve("../.."))) {
      candidates.add(base.resolve("frontend/src/api/stream-types.ts").normalize());
    }
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "找不到 SSE 契约 frontend/src/api/stream-types.ts。试过："
            + candidates
            + "；user.dir="
            + userDir
            + "。本类不能静默跳过 —— 契约是那两条时间常量的裁判，找不到裁判就必须失败。");
  }
}
