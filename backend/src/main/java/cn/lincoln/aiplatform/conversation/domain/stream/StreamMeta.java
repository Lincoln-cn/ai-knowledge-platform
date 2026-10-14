package cn.lincoln.aiplatform.conversation.domain.stream;

/**
 * 连接建立元信息（契约 {@code StreamMeta}：{@code {messageId, traceId, model}}）。
 *
 * <p>★ 三个字段<strong>逐字对齐</strong>契约的 {@code StreamMeta}。它的价值在契约注释里说得很直白： "先有 meta 才谈得上生成中停止" ——
 * 前端一上来就拿到 {@code messageId}，"停止 / 重生成"才可能在生成过程中使用； {@code traceId} 是用户报障时唯一能把界面与后端日志对上号的东西。
 *
 * <p>★ 装配器把它转成 {@link StreamEvent.StreamMetaEvent}（同一个 payload，多了一个事件名）。 本批不生成 {@code traceId}：它由第
 * 17 周的用例层在请求入口处生成（一次问答一个）， 这里只承载 —— 在领域层造一个 trace id 会让"谁负责发号"变成两处口径。
 *
 * @param messageId 本条助手消息 id
 * @param traceId 链路追踪 id
 * @param model 本次使用的模型标识
 */
public record StreamMeta(String messageId, String traceId, String model) {

  public StreamMeta {
    requireText(messageId, "messageId");
    requireText(traceId, "traceId");
    requireText(model, "model");
  }

  /**
   * 转成事件模型里的一条事件。
   *
   * @param assemblerMessageId 装配器持有的 messageId —— ★ 两者必须是同一个值
   * @return {@code meta} 事件
   */
  StreamEvent.StreamMetaEvent toEvent(String assemblerMessageId) {
    if (!messageId.equals(assemblerMessageId)) {
      throw new IllegalArgumentException(
          "meta.messageId 与装配器的 messageId 不一致（"
              + messageId
              + " vs "
              + assemblerMessageId
              + "）—— 终态会挂到另一条消息上，界面永远停在生成中");
    }
    return new StreamEvent.StreamMetaEvent(messageId, traceId, model);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("meta 的必填字段 " + field + " 不能为空");
    }
  }
}
