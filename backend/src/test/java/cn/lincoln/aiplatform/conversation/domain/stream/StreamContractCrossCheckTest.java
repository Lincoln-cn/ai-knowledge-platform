package cn.lincoln.aiplatform.conversation.domain.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ★★ <strong>契约对读闸门</strong>：把本批的领域形状与前端的<strong>冻结契约</strong>逐字比一遍。
 *
 * <p>契约 = 两个文件：{@code frontend/src/api/stream-types.ts}（类型）与 {@code frontend/src/lib/sse.ts} （wire
 * 上的事件名与 payload 字段）。{@code 10 §8.3} 说"SSE 一个字节都不用改" —— 本类就是那句话的读数。
 *
 * <h3>★ 为什么必须有这一条（而不是靠"我逐字看过"）</h3>
 *
 * <p>字段名写错<strong>不会让任何东西报错</strong>：后端发 {@code {"docName": …}}、前端读 {@code ref.docName}
 * 恰好对上；但谁把后端写成 {@code documentName}（或者照 {@code 03 §1.2} 那张很旧的示例写成 {@code baseName}）， 前端读到的就是 {@code
 * undefined} —— 引用栏照样渲染，只是标题空着、深链跳不动。这类"两边各自都自洽"的偏差 只有<strong>把两份文件放在同一个断言里</strong>才能被抓到。
 *
 * <p>★ 三处刻意<b>不</b>断言（写在这里免得被读成"漏了"）：
 *
 * <ul>
 *   <li><b>不</b>断言"契约文件存在"以外的东西 —— 读不到文件就直接红，不允许 skip（{@code Assumptions} 会让 CI 安静地绿）；
 *   <li><b>不</b>断言 {@code 03 §1.2} 那张 wire 示例（它写的是 {@code baseName} / {@code status:"DONE"} /
 *       {@code references:[]} 包装，与冻结契约<b>不一致</b>）—— 契约是权威，示例只是说明性的，差异登记在报告里；
 *   <li><b>不</b>断言 {@code 03 §4.7.3} 里的第 ⑤ 条"框架假设"（{@code DOCUMENT_CONTEXT} / advisor {@code
 *       order=250} / joiner 被调用）—— 那三条的载体是 Spring AI 的 advisor 链，属第 17 周（本批刻意不引依赖）。
 * </ul>
 */
class StreamContractCrossCheckTest {

  /** 仓库根到契约的路径（surefire 的工作目录 = {@code backend/}）。 */
  private static final Path CONTRACT_TYPES =
      Path.of("..", "frontend", "src", "api", "stream-types.ts");

  private static final Path CONTRACT_WIRE = Path.of("..", "frontend", "src", "lib", "sse.ts");

  /** {@code export interface SourceRef { … }} 的<b>顶层</b>字段（缩进两空格、非注释、非方法）。 */
  private static final Pattern TOP_LEVEL_FIELD =
      Pattern.compile("^ {2}([A-Za-z_][A-Za-z0-9_]*)\\??:");

  /** 前端解析器里的 {@code case 'name':} —— wire 上认得的事件名。 */
  private static final Pattern WIRE_EVENT_CASE = Pattern.compile("case\\s+'([a-z-]+)':");

  private static final Pattern STATUS_UNION =
      Pattern.compile("export type StreamStatus =([^;]+);", Pattern.DOTALL);

  private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_]+)'");

  // ────────────────────────── ① 引用字段：逐字 ──────────────────────────

  /**
   * ★★ {@link cn.lincoln.aiplatform.conversation.domain.SourceRef} 的分量名 = 契约 {@code interface
   * SourceRef} 的字段名（<strong>同名同序</strong>）。
   *
   * <p>同名：{@code docName} 不许写成 {@code documentName}、{@code kbName} 不许写成 {@code baseName}。 同序：record
   * 的分量顺序是它构造器的参数顺序，顺序错了会让"两条引用的字段值互相串位"，而两边类型都是 {@code String} —— 编译器与前端都拦不住。
   *
   * <p>★ 这里刻意用 <strong>{@code containsExactlyElementsOf}</strong>（而不是"契约的字段 ⊆ Java 的字段"）：
   * 后端<strong>多出</strong>契约里没有的字段同样是漂移，而它没有读数 —— 前端不读那几个字段，页面照常渲染， 没有任何一处会红。本批就撞到过一次：{@code 10
   * §8.2} 说 {@code SourceRef} 该有 {@code subQuestion} / {@code round} / {@code
   * matchedQuery}，而<strong>契约里没有</strong>这三个（{@code stream-types.ts:105-120}）。
   * 按"设计册说了就加"写下去这一条会红，而它红得对：要么契约补，要么设计册改 —— 不该由后端单方面多出三个字段。
   */
  @Test
  void sourceRefComponentsMatchTheFrozenContractExactly() {
    List<String> contract = topLevelFields(read(CONTRACT_TYPES), "SourceRef");

    assertThat(componentNames(cn.lincoln.aiplatform.conversation.domain.SourceRef.class))
        .as("★ SourceRef 的分量必须与 frontend/src/api/stream-types.ts 的同名同序（契约冻结，只加可选字段）")
        .containsExactlyElementsOf(contract);
  }

  // ────────────────────────── ② 终态取值：逐字 ──────────────────────────

  /**
   * ★ {@link StreamStatus} 的三个取值 = 契约 {@code StreamStatus} 联合类型的三个字面量（<strong>同名同序</strong>）。
   *
   * <p>★ 这条同时钉住"不许自创第四个"：{@code 03 §1.2} 的示例 payload 里写着 {@code "status":"DONE"}，
   * 但契约的联合类型里<strong>没有</strong> {@code DONE} —— 照那份示例加一个 {@code DONE} 常量， 前端 {@code onDone}
   * 就会收到一个认不出的终态。
   */
  @Test
  void streamStatusValuesMatchTheFrozenContractExactly() {
    List<String> contract = statusUnionValues(read(CONTRACT_TYPES));
    List<String> java = Arrays.stream(StreamStatus.values()).map(Enum::name).toList();

    assertThat(java)
        .as("★ 终态取值必须与契约的 StreamStatus 联合类型逐字一致（前端按它分派 UI）")
        .containsExactlyElementsOf(contract);
  }

  // ────────────────────────── ③ done / refs 的 payload 字段 ──────────────────────────

  /**
   * ★ {@link StreamEvent.StreamDoneEvent} 的字段 ⊇ 契约 {@code interface StreamDone} 的字段。
   *
   * <p>方向是<b>单向</b>的：契约允许"只加可选字段"（{@code 10 §8} 的纪律），所以 Java 侧多出可选字段不算漂移； 但契约里<strong>有</strong>而
   * Java 侧没有 ⇒ 一定会发出一个缺字段的 payload（前端读不到）。
   */
  @Test
  void doneEventCarriesEveryContractField() {
    List<String> contract = topLevelFields(read(CONTRACT_TYPES), "StreamDone");
    List<String> java = componentNames(StreamEvent.StreamDoneEvent.class);

    assertThat(java).as("★ done 事件的字段必须覆盖契约 StreamDone 的每一个字段").containsAll(contract);
  }

  /** 同一条判据给 {@code refs} 事件用的：契约里那一格是 {@code SourceRef[]}，元素字段见用例①。 */
  @Test
  void referencesEventIsTheWholeArrayNotWrapped() {
    List<String> java = componentNames(StreamEvent.References.class);

    assertThat(java)
        .as("★ refs 事件的 payload 就是那个数组本身 —— 不许自己加一个 {references: [...]} 包装（前端按数组解析）")
        .containsExactly("refs");
  }

  /**
   * ★ {@link StreamEvent.StreamErrorEvent} 的字段 = 契约 {@code onError(reason, hint)} 的两个字段。
   *
   * <p>★ 前端解析器读的是 {@code payload.reason} 与 {@code payload.hint}（不是 {@code 03 §1.2} 示例里的 {@code
   * code} / {@code message} / {@code retryable}）—— 又一处"契约与旧示例不一致"。
   */
  @Test
  void errorEventFieldsMatchTheContractCallbackSignature() {
    assertThat(componentNames(StreamEvent.StreamErrorEvent.class))
        .as("★ error 的 payload 字段 = onError(reason, hint)（lib/sse.ts 按这两个名字读）")
        .containsExactly("reason", "hint");
  }

  /** ★ meta 的三个字段 = 契约 {@code interface StreamMeta}。 */
  @Test
  void metaEventFieldsMatchTheContractInterface() {
    List<String> contract = topLevelFields(read(CONTRACT_TYPES), "StreamMeta");

    assertThat(componentNames(StreamEvent.StreamMetaEvent.class))
        .as("★ meta 的字段必须与契约 StreamMeta 一致（messageId 是'生成中停止'的前提）")
        .containsExactlyElementsOf(contract);
  }

  // ────────────────────────── ④ wire 事件名：与解析器逐字 ──────────────────────────

  /**
   * ★★ 后端 emit 的每一个事件名，前端解析器都认（{@code frontend/src/lib/sse.ts} 的 {@code switch}）。
   *
   * <p>反方向不查：解析器还认得我们本批不产出的事件（{@code artifact}，V1.1）—— 那是"契约先冻结"的正常状态。
   *
   * <p>★ {@code HEARTBEAT} 刻意<strong>不在</strong>这一条里：契约①把它定成 SSE <strong>注释帧</strong> （一整行以 {@code
   * ':'} 开头，<strong>没有</strong> {@code event:} 行），所以它<strong>不该</strong> 出现在解析器的 {@code case}
   * 里；它由下一个用例按"注释帧前缀"钉住。
   */
  @Test
  void everyEventKindIsUnderstoodByTheFrozenParser() {
    List<String> parserNames = wireEventNames(read(CONTRACT_WIRE));
    List<String> businessNames =
        Arrays.stream(EventKind.values())
            .filter(kind -> kind != EventKind.HEARTBEAT)
            .map(EventKind::wireName)
            .toList();

    assertThat(businessNames)
        .as("★ 后端可能 emit 的事件名必须逐个出现在前端解析器的 switch 里（否则那个帧被 default: break 安静吃掉）")
        .isSubsetOf(parserNames);
  }

  /**
   * ★ 心跳必须是<strong>注释帧</strong>：解析器按 {@code HEARTBEAT_PREFIX = ':'} 前缀识别它（而不是按事件名），
   * 且它<strong>不是</strong>一个业务事件名。
   *
   * <p>★ 这条钉的是"心跳不是一个业务事件"这条形状：若哪天有人把心跳做成 {@code event: heartbeat}， 前端会在 {@code default: break}
   * 里把它丢掉 —— 而"丢掉了"的表现恰好是<strong>闲置超时把连接判死</strong>， 看起来像网络问题（{@code 03 §1.6} 说的"很难复现定位"）。
   */
  @Test
  void heartbeatIsACommentFrameNotABusinessEvent() {
    String wire = read(CONTRACT_WIRE);

    assertThat(
            firstGroup(
                read(CONTRACT_TYPES), Pattern.compile("export const HEARTBEAT_PREFIX = '([^']+)'")))
        .as("契约①：心跳注释帧的行首")
        .isEqualTo(":");
    assertThat(EventKind.HEARTBEAT.wireName())
        .as("★ 心跳在 wire 上带的是注释帧载荷（契约 HEARTBEAT_PAYLOAD），不是 event: 名")
        .isEqualTo(
            firstGroup(
                read(CONTRACT_TYPES),
                Pattern.compile("export const HEARTBEAT_PAYLOAD = '([^']+)'")));
    assertThat(wire)
        .as("★ 解析器必须按注释帧处理心跳（startsWith(HEARTBEAT_PREFIX)），而不是按事件名")
        .contains("startsWith(HEARTBEAT_PREFIX)");
    assertThat(wireEventNames(wire)).as("★ 心跳不许变成业务事件名 —— 它是注释帧").doesNotContain("heartbeat");
  }

  /**
   * ★ 设计册的 wire 示例不得再出现冻结契约已经否定的旧字段（`#114`）。
   *
   * <p>这条与上面的“契约 ⇄ 实现”对读互补：那里钉实现，这里钉文档。旧示例最容易被照抄， 而它写错时不会让任何代码报错；把四个旧记号写成断言，改回去就会红。
   */
  @Test
  void designDocDoesNotCarryTheStaleSseExample() {
    String design = read(Path.of("..", "docs", "系统设计方案", "03-关键机制设计.md"));

    assertThat(design)
        .as("★ 03 §1.2 的 refs 示例必须用线上的 {\"refs\":[...]}，不许回到 {\"references\":[...]}")
        .doesNotContain("\"references\":");
    assertThat(design).as("★ SourceRef 的字段是 kbName，不是 baseName").doesNotContain("baseName");
    assertThat(design)
        .as("★ done.status 只能是 GENERATED / STOPPED / FAILED，不许写 DONE")
        .doesNotContain("\"status\":\"DONE\"");
    assertThat(design)
        .as("★ error 的字段是 reason / hint，不是 retryable")
        .doesNotContain("\"retryable\"");
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  /**
   * 取出某个 {@code interface X { … }} 的<b>顶层</b>字段名（保持文件里的顺序）。
   *
   * @param source 契约源码
   * @param interfaceName 接口名
   * @return 字段名（含可选字段）
   */
  private static List<String> topLevelFields(String source, String interfaceName) {
    int start = source.indexOf("export interface " + interfaceName + " {");
    if (start < 0) {
      throw new IllegalStateException(
          "契约里找不到 interface " + interfaceName + " —— 要么契约改了（那就先改这一条），要么读错了文件");
    }
    int end = source.indexOf("\n}", start);
    if (end < 0) {
      throw new IllegalStateException("interface " + interfaceName + " 没有正常结束（找不到顶格的 }）");
    }
    List<String> fields = new ArrayList<>();
    for (String line : source.substring(start, end).split("\n")) {
      Matcher matcher = TOP_LEVEL_FIELD.matcher(line);
      if (matcher.find()) {
        fields.add(matcher.group(1));
      }
    }
    return List.copyOf(fields);
  }

  /** 取出 {@code export type StreamStatus = 'A' | 'B';} 里的字面量（保持顺序）。 */
  private static List<String> statusUnionValues(String source) {
    Matcher union = STATUS_UNION.matcher(source);
    if (!union.find()) {
      throw new IllegalStateException("契约里找不到 StreamStatus 联合类型");
    }
    List<String> values = new ArrayList<>();
    Matcher quoted = QUOTED.matcher(union.group(1));
    while (quoted.find()) {
      values.add(quoted.group(1));
    }
    return List.copyOf(values);
  }

  /** 取出解析器 {@code switch} 里认得的全部 wire 事件名。 */
  private static List<String> wireEventNames(String source) {
    List<String> names = new ArrayList<>();
    Matcher matcher = WIRE_EVENT_CASE.matcher(source);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return List.copyOf(names);
  }

  private static String firstGroup(String source, Pattern pattern) {
    Matcher matcher = pattern.matcher(source);
    if (!matcher.find()) {
      throw new IllegalStateException("在契约里找不到 " + pattern.pattern());
    }
    return matcher.group(1);
  }

  /**
   * 读契约文件。
   *
   * <p>★ 读不到就<strong>抛</strong>（不用 {@code Assumptions.assumeTrue}）：那样 CI 里会变成"跳过"，
   * 而"跳过"与"通过"在报告上都是绿的 —— 一条会因环境而整类消失的闸门等于没有闸门。
   */
  private static String read(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isRegularFile(absolute)) {
      throw new IllegalStateException(
          "读不到契约文件：" + absolute + "（surefire 的工作目录应为 backend/；契约是冻结的，本闸门不接受'文件不在'）");
    }
    try {
      return Files.readString(absolute, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
