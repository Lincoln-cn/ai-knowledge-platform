package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ★★ <strong>消息契约对读闸门</strong>：把本批的领域形状与前端<strong>冻结契约</strong>逐字比一遍。
 *
 * <p>契约 = {@code frontend/src/api/conversations.ts}（{@code MessageStatus} / {@code ChatRole} /
 * {@code ChatMessage} 的字段形状）。{@code 10 §8.3} 说"SSE 一个字节都不用改"，本类是那句话在<strong>消息这一层</strong> 的读数 ——
 * 引用那一层由 {@code domain/stream/StreamContractCrossCheckTest} 与 {@code
 * domain/SourceRefSerializationTest} 各自钉一遍。
 *
 * <h3>★ 为什么必须有这一条（而不是靠"我逐字看过"）</h3>
 *
 * <p>状态取值写错<strong>不会让任何东西报错</strong>：后端落一个契约里没有的 {@code DONE}，前端 {@code onDone} 按三个终态分派
 * UI，认不出的那个就停在"生成中"—— 页面永远转圈。而这类"两边各自都自洽"的偏差， 只有<strong>把两份文件放进同一个断言里</strong>才抓得住（{@code 02 §5.2}
 * 的注释与契约正好是两套取值， 这个类就是那次冲突的裁决读数）。
 *
 * <h3>★ 三处刻意<b>不</b>断言（写在这里免得被读成"漏了"）</h3>
 *
 * <ul>
 *   <li>契约 {@code ChatMessage} 里有而本批<b>不</b>落库的四个字段（{@code suggestions} / {@code feedback} / {@code
 *       artifacts} 的 Java 类型 / {@code modelId} 的写路径）—— 它们的载体分别是 {@code artifacts} 列（V1.1）、{@code
 *       fb_feedback} 表（第 18 周）、下一批的生成链路。本批的 {@code Message}
 *       <strong>故意不造出对应字段</strong>（那会是"有类型、没人写"的空壳）， 所以这里断言的是一个<strong>有下界的清单</strong>，而不是"必须相等"。
 *   <li>{@code tokens} 的形状差异：契约 {@code ChatMessage.tokens} 是一个 {@code number}， 而库里是 {@code
 *       prompt_tokens} / {@code completion_tokens} <strong>两列</strong> （{@code 02 §5.2}）。本类只断言"总量 =
 *       两分量之和"这条导出关系在<b>Java 侧</b>成立 （{@link TokenUsage#total()}），不假装前端能直接读到两列。
 *   <li>写入路径本身（HTTP / SSE 帧）：本批<b>不加端点、不写控制器</b>，那属 {@code /conversations/:id/messages} 那一批（{@code
 *       ROADMAP §11.2} 第 17 周）。
 * </ul>
 */
class MessageContractCrossCheckTest {

  /**
   * 仓库根到契约的路径（surefire 的工作目录 = {@code backend/}）。
   *
   * <p>★ 消息这一层的契约定在 {@code conversations.ts}（{@code MessageStatus} / {@code ChatRole} / {@code
   * ChatMessage}），而引用那一层的契约定在 {@code stream-types.ts}（{@code SourceRef}）。 ★ 第一版把 {@code
   * MessageStatus} 也往 {@code stream-types.ts} 里找，结果<strong>那条用例直接抛</strong> ——
   * 那恰好说明"读错文件"这件事是会发生的，所以本类对"找不到"一律抛而不是 skip。
   */
  private static final Path CONTRACT_MESSAGES =
      Path.of("..", "frontend", "src", "api", "conversations.ts");

  /** {@code export interface X { … }} 的<b>顶层</b>字段（缩进两空格、非注释）。 */
  private static final Pattern TOP_LEVEL_FIELD =
      Pattern.compile("^ {2}([A-Za-z_][A-Za-z0-9_]*)\\??:");

  /**
   * {@code export type X = 'A' | 'B' | null} 之类的联合类型。★ 类型名与 {@code =} 之间可能夹着注释 / 换行。
   *
   * <p>★★ <strong>两个文件的行尾风格不同，而第一版漏了这件事</strong>：{@code stream-types.ts} 的类型都以 {@code ;} 收尾，而
   * {@code conversations.ts} 的 {@code MessageStatus} / {@code ChatRole}
   * <strong>没有分号</strong>（它俩是按"一个类型一行"的风格写的）。把 {@code ;} 写成必选会让正则<strong>一条都匹配不上</strong> ——
   * 而那两条用例的失败信息恰好是"契约里找不到该类型"，与"契约真的改了"长得一模一样。 ⇒ 这里按"到分号或行尾为止"取，两个文件的两种风格都认。
   */
  private static final Pattern STATUS_UNION =
      Pattern.compile("export type MessageStatus\\s*=\\s*([^;\\n]+);?");

  private static final Pattern ROLE_UNION =
      Pattern.compile("export type ChatRole\\s*=\\s*([^;\\n]+);?");

  private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_]+)'");

  // ────────────────────────── ① 状态取值：逐字 ──────────────────────────

  /**
   * ★★ {@link MessageStatus} 的四个取值 = 契约 {@code MessageStatus} 联合类型的字面量（<strong>同名同序</strong>）。
   *
   * <p>★ 本类的核心读数：{@code 02 §5.2} 的注释写的是 {@code PENDING/STREAMING/DONE/STOPPED/ERROR}（五态），
   * 而契约是四态。本批<strong>按契约实现</strong>并且把这句话钉成断言 —— 若哪天有人照着设计册那一行 把枚举改回五态，这条会红，而不是让前端安静地认不出终态。
   */
  @Test
  void messageStatusValuesMatchTheFrozenContractExactly() {
    List<String> contract = unionValues(read(CONTRACT_MESSAGES), STATUS_UNION, "MessageStatus");
    List<String> java = Arrays.stream(MessageStatus.values()).map(Enum::name).toList();

    assertThat(java)
        .as("★ 消息状态取值必须与契约的 MessageStatus 逐字一致（前端按它分派 UI；02 §5.2 那一行是冻结之前的意图）")
        .containsExactlyElementsOf(contract);
  }

  /** ★ {@code ChatRole} 的两个取值 = 契约（{@code USER} / {@code ASSISTANT}）。 */
  @Test
  void messageRoleValuesMatchTheFrozenContractExactly() {
    List<String> contract = unionValues(read(CONTRACT_MESSAGES), ROLE_UNION, "ChatRole");
    List<String> java =
        Arrays.stream(cn.lincoln.aiplatform.conversation.domain.MessageRole.values())
            .map(Enum::name)
            .toList();

    assertThat(java)
        .as("★ 角色取值必须与契约的 ChatRole 逐字一致（写错会把一条回答渲染成用户气泡）")
        .containsExactlyElementsOf(contract);
  }

  // ────────────────────────── ② 终态集合：由契约推出 ──────────────────────────

  /**
   * ★ {@link MessageStatus#isTerminal()} 的<strong>补集</strong>是契约里那个"生成中"取值。
   *
   * <p>★ 为什么不写死 {@code GENERATING} 这个名字：那会让这条断言变成同义反复（枚举里写的是什么它就认什么）。
   * 正确的形状是"**恰好一个取值不是终态**，且它等于契约里那个非终态的名字" —— 若哪天有人给枚举加了第五个 非终态，这条会红（而"终态不可再转"那条不变量也会同时失去意义）。
   */
  @Test
  void exactlyOneStatusIsNotTerminal() {
    List<String> nonTerminal =
        Arrays.stream(MessageStatus.values()).filter(s -> !s.isTerminal()).map(Enum::name).toList();

    assertThat(nonTerminal)
        .as("★ 三态状态机的前提是'恰好一个非终态' —— 多一个就说明有人把某个终态改成了可继续转移的中间态")
        .containsExactly("GENERATING");
  }

  // ────────────────────────── ③ ChatMessage 的字段：本批覆盖的有下界 ──────────────────────────

  /**
   * ★ {@code ChatMessage} 的字段里，凡是本批有载体的，都能在 {@link Message} 或 {@link MessageRepository} 上找到落点。
   *
   * <p>★ 这是一条<strong>清单式</strong>断言（逐个字段对到落点），而不是反射比对：{@code Message} 的访问器名字
   * 与契约字段名<strong>刻意不完全相同</strong>（库里叫 {@code content}/{@code status}/{@code references}， 契约叫
   * {@code content}/{@code status}/{@code refs}），所以"名字逐字相等"这种断言在这里是错的判据 ——
   * 真正要钉的是"<strong>没有一个字段是没人管的</strong>"。
   */
  @Test
  void everyChatMessageFieldHasAKnownHome() {
    List<String> contract = topLevelFields(read(CONTRACT_MESSAGES), "ChatMessage");

    assertThat(contract)
        .as("契约 ChatMessage 的字段集合变了 ⇒ 先确认本批的落点清单是否还成立（列在 javadoc 里）")
        .containsExactlyInAnyOrder(
            "id",
            "conversationId",
            "role",
            "content",
            "status",
            "refs",
            "suggestions",
            "feedback",
            "tokens",
            "durationMs",
            "failReason",
            "failHint",
            "createdAt",
            "attachments");

    // 本批的落点（逐个字段一行，缺一个都会在这里显形）
    Message message = Message.user(UUID.randomUUID(), UUID.randomUUID(), "在吗");
    assertThat(message.id()).as("id").isNotNull();
    assertThat(message.conversationId()).as("conversationId").isNotNull();
    assertThat(message.role()).as("role").isNotNull();
    assertThat(message.content()).as("content").isNotNull();
    assertThat(message.status()).as("status").isNotNull();
    assertThat(message.references()).as("refs（列 references）").isNull();
    assertThat(message.durationMs()).as("durationMs").isNull();
    assertThat(message.failReason()).as("failReason").isNull();
    assertThat(message.failHint()).as("failHint").isNull();
    assertThat(message.createdAt()).as("createdAt（@CreationTimestamp）").isNull();
    // suggestions / feedback / attachments 本批没有 Java 侧载体：suggestions 进 artifacts 列（V1.1）、
    // feedback 是 fb_feedback 表（第 18 周）、attachments 只落列（见类注释）。
  }

  // ────────────────────────── ④ tokens：两列 + 一条导出关系 ──────────────────────────

  /**
   * ★ {@code total = prompt + completion} —— 契约 {@code done.tokens}
   * 的第三个字段<strong>不由库里单独提供</strong>。
   *
   * <p>★ 这条断言的是"导出关系"而不是"三个列都存在"：独立存 {@code total} 就等于允许它与两个分量不一致， 而那种不一致没有任何一处会报错（{@code 10
   * §8.4}）。
   */
  @Test
  void totalIsDerivedNotStored() {
    TokenUsage usage = new TokenUsage(11, 22);

    assertThat(usage.total()).as("契约 done.tokens.total = prompt + completion").isEqualTo(33);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 取出某个联合类型里的字面量（保持文件里的顺序）。 */
  private static List<String> unionValues(String source, Pattern pattern, String typeName) {
    Matcher union = pattern.matcher(source);
    if (!union.find()) {
      throw new IllegalStateException("契约里找不到 " + typeName + " 联合类型 —— 要么契约改了，要么读错了文件");
    }
    List<String> values = new ArrayList<>();
    Matcher quoted = QUOTED.matcher(union.group(1));
    while (quoted.find()) {
      values.add(quoted.group(1));
    }
    return List.copyOf(values);
  }

  /** 取出某个 {@code interface X { … }} 的<b>顶层</b>字段名（保持文件里的顺序）。 */
  private static List<String> topLevelFields(String source, String interfaceName) {
    int start = source.indexOf("export interface " + interfaceName + " {");
    if (start < 0) {
      throw new IllegalStateException("契约里找不到 interface " + interfaceName);
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

  /**
   * 读契约文件。
   *
   * <p>★ 读不到就<strong>抛</strong>（不用 {@code Assumptions.assumeTrue}）：那样 CI 里会变成"跳过"，
   * 而"跳过"与"通过"在报告上都是绿的。
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

  /**
   * ★ 仓储端口的形状：<strong>一个写入口</strong> + 只读入口（多出来的写路径就是"状态机被绕开"的入口）。
   *
   * <p>★★ 2026-10-17（{@code #143} 同批）：只读口从两个长到四个 —— {@code listByConversation}（整条会话）· {@code
   * slice}（常规分页档）· {@code windowAround}（{@code around} 档） 都是登录面历史消息端点要用的读法。★ 写入口**仍然只有一个** {@code
   * save}， 这条断言守的那件事（状态机判断在领域对象里、不在 SQL 里）一个字都没变。
   *
   * <p>★ 2026-10-21（{@code #182}）：加 {@code findByIdForUpdate} —— 读时收敛陈旧 GENERATING 要对单行加写锁，
   * 它是只读口（行锁不是状态写入），写入口仍只有 {@code save}。
   */
  @Test
  void repositoryPortKeepsASingleWritePath() {
    assertThat(MessageRepository.class.getDeclaredMethods())
        .as("★ 仓储端口的写入口必须唯一（状态机判断在领域对象里，不在 SQL 里）")
        .filteredOn(m -> !m.isSynthetic())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder(
            "save", "findById", "findByIdForUpdate", "listByConversation", "slice", "windowAround");
  }
}
