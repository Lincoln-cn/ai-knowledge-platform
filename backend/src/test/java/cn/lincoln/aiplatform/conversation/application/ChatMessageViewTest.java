package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ★★ 历史消息的<strong>线上形状</strong>：{@link ChatMessageView} 的 JSON 键名必须<strong>逐字</strong>等于契约 {@code
 * frontend/src/api/conversations.ts} 的 {@code ChatMessage}（{@code listPublicMessages} 的载体）。
 *
 * <h3>★★ 为什么必须是"裸 JSON 键名"断言（而不是把 record 交给 Jackson 就完事）</h3>
 *
 * <p>"字段名变形"是<strong>静默</strong>的：后端写成 {@code conversationID} / 被配置成 {@code SNAKE_CASE}、
 * 或者少写一个分量，序列化<strong>都不会报错</strong> —— 前端只是拿到 {@code undefined}， 页面照常渲染、只是那一格空着。所以这里不看对象、直接看那串
 * JSON 里有没有那些字。
 *
 * <h3>★ 与其它两条断言的分工（三条各管一层，缺一条就有一层没人守）</h3>
 *
 * <ul>
 *   <li>{@code SourceRefSerializationTest} —— 引用快照落 {@code jsonb} 那一侧（Hibernate 的 Jackson 2）；
 *   <li>{@code StreamContractCrossCheckTest} —— SSE 契约的<strong>类型形状</strong>（record 分量名 vs TS 字段名）；
 *   <li>★ <strong>本类</strong> —— 历史消息这一条 <strong>REST</strong> 链路的<strong>线形状</strong> （{@code
 *       ChatMessage} 的 13 个键名 + 三个"今天恒为某个值"的格子的语义）。
 * </ul>
 *
 * <p>★ 它在 {@code conversation.application} 包（与 {@link ChatMessageView} 同包）**不是为了省一行 import**：
 * 引用映射只有一处（{@link ChatStreamWriter#refsOf}，包私有），本类要钉的正是"历史与流用的是同一条映射"。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>（与口径组同一档成本）。
 */
class ChatMessageViewTest {

  /** ★ 与 Web 层同一个序列化器（Jackson 3 / {@code tools.jackson}）—— REST 那一条线上用的就是它。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * ★ 契约 {@code ChatMessage} 的全部字段名（{@code conversations.ts} 的 interface）。
   *
   * <p>★ 写成<strong>全量清单</strong>而不是"不含某个名字"：后者在别人把字段改名之后<strong>照样绿</strong>， 而缺口一点没变（口径组那条纪律，见
   * {@code CaliberShape} 的类注释）。 ★ {@code attachments} <strong>不在</strong>这份清单里：契约里它可缺省（{@code
   * attachments?}）， 而 {@code POST /files} 今天还没实现 ⇒ 这一条链路上它不可能有值，所以**整个不输出** （输出空数组会让前端把"没有附件"读成"有 0
   * 个附件"）。这一条由下面那条用例单独钉住。
   */
  private static final List<String> CONTRACT_FIELDS =
      List.of(
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
          "createdAt");

  @Test
  void jsonKeysAreExactlyTheContractFieldNames() throws Exception {
    ChatMessageView view =
        new ChatMessageView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ASSISTANT",
            "答案",
            MessageStatus.GENERATED,
            List.of(fullRef()),
            List.of(),
            null,
            192,
            4210L,
            null,
            null,
            Instant.parse("2026-10-16T12:00:00Z"));

    Map<String, Object> node = JSON.readValue(JSON.writeValueAsString(view), Map.class);

    assertThat(node.keySet())
        .as(
            "★★ 历史消息的 JSON 键名必须逐字等于契约 ChatMessage 的字段名（写成 SNAKE_CASE / 少一个分量"
                + "都不会报错，只是前端那一格是 undefined）")
        .containsExactlyInAnyOrderElementsOf(CONTRACT_FIELDS);
  }

  /**
   * ★★ {@code refs} 的<strong>三态</strong>必须可分（{@code Message} 与契约共同的口径）。
   *
   * <ul>
   *   <li>{@code null} = 这一批引用<strong>从来没到过</strong>（检索还没回来就收尾）；
   *   <li>{@code []} = 明确的<strong>零命中</strong>（检索跑过了、一条都没中）；
   *   <li>有值 ⇒ 每条都带契约那 <strong>11</strong> 个字段（含 {@code #27} 的 {@code classification}）。
   * </ul>
   *
   * <p>★ 把前两者糊成一个（例如统一兜底成 {@code []}）不会报错，只会让"检索没跑起来"与"库里没有相关内容" 再也分不出来 —— 而它们的排查方向完全不同。
   */
  @Test
  void refsKeepTheirThreeDistinctStates() throws Exception {
    assertThat(JSON.writeValueAsString(viewWithRefs(null)))
        .as("★ 引用从没到过 ⇒ refs 必须是 null，不许被兜底成 []")
        .contains("\"refs\":null");

    assertThat(JSON.writeValueAsString(viewWithRefs(List.of())))
        .as("★ 零命中 ⇒ refs 是空数组")
        .contains("\"refs\":[]");

    assertThat(JSON.writeValueAsString(viewWithRefs(List.of(fullRef()))))
        .as("★ 有引用时，每一条都带契约 SourceRef 的 11 个字段（含 #27 新加的 classification）")
        .contains("\"classification\":\"SECRET\"")
        .contains("\"docName\"")
        .contains("\"kbId\"");
  }

  /**
   * ★★ 三个"今天恒为某个值"的格子各配一条断言 —— 它们是**读数**，不是"忘了填"。
   *
   * <ul>
   *   <li>{@code tokens} / {@code durationMs}：库里那两个列可为 {@code NULL}（= 没发生模型调用）， 而契约里是 {@code
   *       number} ⇒ 映射成 {@code 0}。★ 这条转换<strong>有损</strong>，所以配断言： 谁把 {@code null} 漏出去，前端拿到 {@code
   *       undefined} 一算就是 {@code NaN}；
   *   <li>{@code feedback}：赞踩是第 18 周（{@code 0.5.0}）的能力 ⇒ 恒 {@code null}（= 未评）。 ★ 写 {@code null}
   *       而不是省掉这一格：契约里它不是可选字段；
   *   <li>{@code suggestions}：{@code artifact} 是 V1.1 ⇒ 恒空数组（契约里必填）；
   *   <li>{@code attachments}：契约里可缺省，而 {@code POST /files} 还没有 ⇒ <strong>整个不出现</strong>。
   * </ul>
   */
  @Test
  void theThreeConstantFieldsCarryTheirDocumentedValue() throws Exception {
    Message message = new Message(UUID.randomUUID(), UUID.randomUUID(), "");

    ChatMessageView view = ChatMessageView.of(message);
    String json = JSON.writeValueAsString(view);

    assertThat(view.tokens()).as("★ 没有用量 ⇒ 0（契约是 number，null 会让前端算出 NaN）").isZero();
    assertThat(view.durationMs()).as("★ 没有耗时 ⇒ 0，同上").isZero();
    assertThat(view.feedback()).as("★ 赞踩第 18 周才做 ⇒ 恒 null（= 未评），但不是省掉这一格").isNull();
    assertThat(view.suggestions()).as("★ artifact 属 V1.1 ⇒ 恒空列表（契约里必填）").isEmpty();
    assertThat(json).as("★ 附件这一格整个不出现（契约可缺省，POST /files 还没实现）").doesNotContain("attachments");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static ChatMessageView viewWithRefs(List<Map<String, Object>> refs) {
    return new ChatMessageView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ASSISTANT",
        "答案",
        MessageStatus.GENERATED,
        refs,
        List.of(),
        null,
        0,
        0L,
        null,
        null,
        Instant.parse("2026-10-16T12:00:00Z"));
  }

  /**
   * 一条契约字段齐全的引用 —— ★ 走<strong>生产那条映射</strong>（{@link ChatStreamWriter#refsOf}）而不是
   * 在测试里抄一份：本类要钉的正是"历史与流用的是同一处映射"，抄一份就把这件事验没了。
   */
  private static Map<String, Object> fullRef() {
    SourceRef ref =
        new SourceRef(
            1,
            "第一条",
            "制度.pdf",
            "制度库",
            0.91,
            1,
            "正文",
            "seg-1",
            "doc-1",
            "kb-1",
            ClassificationLevel.SECRET);
    return ChatStreamWriter.refsOf(List.of(ref)).get(0);
  }
}
