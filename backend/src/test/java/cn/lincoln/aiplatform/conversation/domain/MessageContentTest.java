package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★★ 消息正文的<strong>累积</strong>，以及"<strong>停止 / 失败时已生成的内容必须保留</strong>" —— 纯领域单测，不起 Spring、不起容器。
 *
 * <h3>这两条为什么放在一起、又为什么必须由单测钉住</h3>
 *
 * <p>它们是同一个动作的两面：{@code 03 §1.4} 的表格逐字写着「用户点停止 → flush → {@code STOPPED}，
 * <strong>内容保留</strong>」，{@code 02 §2.6} 的 MSG-I2 把它写成不变量（"部分答案不能丢"）。
 * 而"保留"这件事<strong>坏掉的样子是安静的</strong>：
 *
 * <ul>
 *   <li>把"停止"实现成"收尾并清空"（看起来更整洁）⇒ 用户点停止后看到一条<strong>空白</strong>回答， 而库里那一行也不再有"已经生成了什么"的痕迹；
 *   <li>把累积实现成"每次覆盖"（{@code content = chunk}）⇒ 只有最后一个 chunk 留下， 而流式界面在用户看来是正常的（他本来就只盯着最后那一行）；
 *   <li>把两者实现成"先 {@code stop} 再 {@code appendText} 兜底"⇒ 终态之后追加被状态机挡住， 于是"保留"变成了"抛异常"，而异常会把它变成一个
 *       500。
 * </ul>
 *
 * <p>★ 这一族是 {@code ROADMAP §二} 的 <strong>A5</strong> 判据（"停止保留已生成内容、重生成用同一问题"） 的领域侧读数。真库那一侧的同一读数在
 * {@code MessagePersistenceTest}。
 *
 * <p>★ 刻意<strong>不</strong>在这里断言 {@code StreamAssembler.text()}：那一层（装配器）与这一层（消息）
 * 各自持有一份文本，把两个类放进同一个断言会掩盖"到底哪一层丢了内容"。
 */
class MessageContentTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0c000000-0000-4000-8000-000000000003");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("0d000000-0000-4000-8000-000000000004");

  // ────────────────────────── ① 增量追加 ──────────────────────────

  /**
   * ★ <strong>增量追加</strong>：逐块喂进去，正文是全部块的拼接，顺序也在。
   *
   * <p>★ 用三个可区分的块（而不是"a"、"b"）并断言<strong>整串相等</strong>：只断言"包含最后一个块"的话， 一个"每次覆盖"的实现会照样绿。
   */
  @Test
  void chunksAccumulateInOrder() {
    Message message = assistant();

    message.appendText("第一段");
    message.appendText("，第二段");
    message.appendText("，第三段。");

    assertThat(message.content())
        .as("★ 增量追加：正文必须是全部 delta 的拼接（'每次覆盖'的实现只会留下最后一段，而它在界面上看不出问题）")
        .isEqualTo("第一段，第二段，第三段。");
  }

  /**
   * ★ 空增量 / {@code null} 是<strong>无操作</strong>，不是异常也不是"清空"。
   *
   * <p>★ 模型流里"这一帧没有内容"是正常帧（{@code StreamEvent.Delta} 的同一条口径）。对它报错会给调用方
   * 增加一条处处要写的判断；而更坏的一种实现是"把空增量当成了新值" ⇒ 整段答案被清成空串。
   */
  @Test
  void blankChunksAreNoOps() {
    Message message = assistant();
    message.appendText("已有内容");

    message.appendText(null);
    message.appendText("");

    assertThat(message.content()).as("空增量必须是无操作 —— 既不能抛，更不能把已有内容覆盖成空串").isEqualTo("已有内容");
  }

  // ────────────────────────── ② 停止保留内容（A5 的领域侧） ──────────────────────────

  /**
   * ★★ <strong>{@code GENERATING → STOPPED} 之后 {@code content()} 仍是已累积的那一段</strong>。
   *
   * <p>判据原文（{@code ROADMAP §11.2} 第 17 周演示行）："停止保留内容"。断言取的是 <strong>逐字相等</strong>而不是 {@code
   * isNotEmpty()} —— 后者会被"保留了一部分 / 保留成了别的东西"骗过去。
   */
  @Test
  void stopKeepsAlreadyGeneratedContent() {
    Message message = assistant();
    message.appendText("根据《报销管理规定》第 3 条，");
    message.appendText("住宿费标准为每人每天 500 元");

    message.stop(2100L);

    assertThat(message.status()).isEqualTo(MessageStatus.STOPPED);
    assertThat(message.content())
        .as("★★ A5：用户点停止之后正文必须还是已生成的那一段 —— 不是空、也不是被截断过")
        .isEqualTo("根据《报销管理规定》第 3 条，住宿费标准为每人每天 500 元");
  }

  /**
   * ★ 失败路径<strong>同样保留</strong>已生成的内容。
   *
   * <p>★ 与停止分开断言：用户点停止是他自己的动作，而失败是一次故障 —— 若失败时清空内容， 用户看到的是"什么都没发生"，会以为是自己的问题（而真相是服务端断了）。
   */
  @Test
  void failureKeepsAlreadyGeneratedContent() {
    Message message = assistant();
    message.appendText("半句没说完的答案");

    message.fail("连接中断", "网络或服务异常，可重试", 900L);

    assertThat(message.content()).as("★ 失败也保留已生成内容：清掉它会让'失败'看起来像'什么都没发生'").isEqualTo("半句没说完的答案");
  }

  /**
   * ★ 正常完成之后正文仍是全部内容（不受收尾影响）。
   *
   * <p>★ 这条看似显然，但它钉的是"收尾动作不许改写正文"：一旦有人把 {@code complete} 实现成
   * "用最终答案替换累积文本"，那么前面所有"累积"的断言都会在真实链路上失去意义 （链路上累积的是增量，而最终答案恰好等于累积值 —— 只有这条断言能区分两者）。
   */
  @Test
  void completeKeepsAccumulatedTextUntouched() {
    Message message = assistant();
    message.appendText("完整答案");

    message.complete(new TokenUsage(3, 7), 50L);

    assertThat(message.content()).isEqualTo("完整答案");
  }

  // ────────────────────────── ③ 引用快照 ──────────────────────────

  /**
   * ★★ 引用快照：<strong>随消息带着、逐字可读</strong>，且 {@code index} 的取值与顺序都不被动过 （{@code A4 引用可展开溯源}的领域侧读数）。
   *
   * <p>★ 断言<strong>顺序与取值</strong>（{@code containsExactly}）而不是"集合相等"：{@code index} 是正文里 {@code [N]}
   * 的编号，重排会让 {@code [1]} 指到另一条来源上 —— 而那种错<strong>不会报错</strong>， 只是用户点开引用看到的是别段原文。
   */
  @Test
  void referencesSnapshotIsKeptVerbatim() {
    Message message = assistant();
    SourceRef first = ref(1, "第一章 总则");
    SourceRef second = ref(2, "第三章 报销标准");

    message.bindReferences(List.of(first, second));

    assertThat(message.references())
        .as("★ 引用快照必须逐字保留（含顺序与 index）—— 重排会让正文里的 [N] 指到别的来源上")
        .containsExactly(first, second);
  }

  /**
   * ★ 引用快照<strong>不可覆盖</strong>。
   *
   * <p>★ 它是"用户当时点开看到的那批来源"，不是"这个问题的当前检索结果"。允许覆盖的话， 一次重跑检索就改掉了历史引用的出处 —— 而"当时到底引了什么"正是溯源要回答的那个问题。
   */
  @Test
  void referencesSnapshotCannotBeOverwritten() {
    Message message = assistant();
    message.bindReferences(List.of(ref(1, "第一次检索的来源")));

    assertThatThrownBy(() -> message.bindReferences(List.of(ref(1, "第二次检索的来源"))))
        .as("★ 引用快照只能绑一次：覆盖会改掉历史引用的出处（重生成请开一条新的助手消息）")
        .isInstanceOf(IllegalStateException.class);
    assertThat(message.references()).as("拒绝之后快照不动").containsExactly(ref(1, "第一次检索的来源"));
  }

  /** ★ 对用户消息绑引用被拒（MSG-I4：引用只存在于助手消息上）。 */
  @Test
  void userMessageCannotCarryReferences() {
    Message question = Message.user(UUID.randomUUID(), CONVERSATION_ID, "问一句");

    assertThatThrownBy(() -> question.bindReferences(List.of(ref(1, "不该出现"))))
        .as("★ MSG-I4：引用只在助手消息上 —— 用户消息带引用会让引用栏渲染出一批'我问出来的来源'")
        .isInstanceOf(IllegalStateException.class);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static Message assistant() {
    return new Message(MESSAGE_ID, CONVERSATION_ID, "");
  }

  /** 造一条契约字段齐全的引用（{@code index} 由调用方给，便于验"顺序与取值都不被动过"）。 */
  static SourceRef ref(int index, String title) {
    return new SourceRef(index, title, "报销管理规定.pdf", "制度库", 0.87, null, null, null, null, null);
  }
}
