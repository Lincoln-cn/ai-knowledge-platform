package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一条消息的<strong>线上形状</strong> —— 逐字对齐契约 {@code frontend/src/api/conversations.ts} 的 {@code
 * ChatMessage}（{@code #143} 同批新增：公开面历史消息的读数载体）。
 *
 * <h3>★★ 为什么单独一个 record，而不是直接返回领域对象 {@link Message}</h3>
 *
 * <p>两条都成立，而且各自都能单独造成静默故障：
 *
 * <ol>
 *   <li><strong>字段名</strong>：领域对象是中文注释的实体（{@code content} / {@code status} / …）， 而契约里是 camelCase 的
 *       14 个字段。把实体直接序列化出去，字段名会随实体改名而变， 前端只是拿到 {@code undefined} —— 页面照常渲染、只是那一格空着；
 *   <li><strong>契约里没有的字段不许自创</strong>（{@code SourceRef} 那条纪律的同一条）： 实体上多出来的东西（{@code updatedAt} /
 *       {@code modelId} / 未来的内部列）不该出现在线上。
 * </ol>
 *
 * <h3>★ 三个"今天恒为某个值"的字段，都写清了它们为什么是那个值</h3>
 *
 * <ul>
 *   <li>{@code feedback} —— ★ <strong>真读 {@code cv_message.feedback}</strong>（第 18 周 · {@code
 *       0.5.0} 落地，列见 {@code V35}）：{@code "UP"} / {@code "DOWN"} / {@code null}，{@code null} = 未评价。
 *       ★ 这一格此前<strong>恒为 {@code null}</strong>（那时赞踩还没做）；现在它是真值。 ★
 *       仍然<strong>不许省掉这一格</strong>：契约里它不是可选字段（{@code feedback: MessageFeedback | null}）， 省掉会让前端拿到
 *       {@code undefined}；
 *   <li>{@code suggestions} —— <strong>恒为空列表</strong>：{@code artifact}（推荐问题 / 图表 / 导图）是 V1.1， 契约里
 *       `suggestions: string[]` 必填 ⇒ 给空数组。★ 这一格是"今天没有产物"的读数，不是"忘了填"；
 *   <li>{@code attachments} —— <strong>不出现在线上</strong>：契约里它可缺省（{@code attachments?}）， 而 {@code POST
 *       /files} 今天还没实现（仍在 {@code ContractDriftTest} 的未实现清单里）⇒ 这一条链路上它**不可能**有值。★
 *       可选字段"要么有要么没有"，所以这里整个不输出， 而不是输出一个空数组（那会让前端把"没有附件"读成"有 0 个附件"，两者在类型上是两件事）。
 * </ul>
 *
 * <p>★ {@code tokens} 与 {@code durationMs} 是<strong>数值</strong>而不是可空（与契约一致）： 库里那两个 token
 * 列**可为空**（{@code NULL} = "这一步没有发生模型调用"，与 {@code 0} = "调用了、用量是 0" 是两件事，见 {@code V33} 的注释），而契约这里是
 * {@code number} ⇒ 映射时把 {@code null} 落成 {@code 0}。 <strong>这条转换是有损的</strong>，所以它写在明处：要区分那两者，得看
 * {@code status} 与服务端的成本账， 不能从这个字段反推。
 *
 * @param id 消息 id（{@code cv_message.id}）
 * @param conversationId 所属会话 id
 * @param role {@code USER} / {@code ASSISTANT}（枚举名逐字 = 契约取值）
 * @param content 正文（★ 停止 / 失败也保留已生成那一段，{@code MSG-I2}）
 * @param status 四态（{@code GENERATING} 不是终态，会被"刷新页面"读到）
 * @param refs 引用快照；★ {@code null} = 这一批引用**从来没到过**，{@code []} = 明确的零命中 —— 两者不许糊成一个
 * @param suggestions 推荐问题（今天恒空，见类注释）
 * @param feedback 赞踩（今天恒 {@code null}，见类注释）
 * @param tokens 本次问答全部模型调用的 token 合计（可空 ⇒ {@code 0}）
 * @param durationMs 生成耗时（可空 ⇒ {@code 0}）
 * @param failReason 失败原因（只在 {@code FAILED} 上有意义）
 * @param failHint 失败后的下一步提示（可空有两种来源，见 {@code Message#failHint()} 的注释）
 * @param createdAt 创建时间（ISO-8601；前端的排序与"最近一页"都读它）
 */
public record ChatMessageView(
    UUID id,
    UUID conversationId,
    String role,
    String content,
    MessageStatus status,
    List<Map<String, Object>> refs,
    List<String> suggestions,
    String feedback,
    int tokens,
    long durationMs,
    String failReason,
    String failHint,
    Instant createdAt) {

  /** 一次问答里助手消息的耗时上限没有业务含义，这里只是让"可空 ⇒ 0"这条转换只有一个落点。 */
  public static ChatMessageView of(Message message) {
    return of(message, List.of());
  }

  /**
   * 带推荐追问的版本（2026-10-18 起有值）。
   *
   * <p>★ 追问来自 {@code cv_message.artifacts} 的 JSON，由 {@code SuggestionService} 生成并落库；
   * 这一层只管"把读到的填进视图"，不关心它是怎么来的。
   *
   * @param message 消息
   * @param suggestions 推荐追问（没有 ⇒ 空列表；契约里它是必填数组）
   */
  public static ChatMessageView of(Message message, List<String> suggestions) {
    TokenUsage usage = message.tokens();
    Long duration = message.durationMs();
    return new ChatMessageView(
        message.id(),
        message.conversationId(),
        message.role().name(),
        message.content(),
        message.status(),
        refsOf(message.references()),
        suggestions,
        message.feedback() == null ? null : message.feedback().name(),
        usage == null ? 0 : usage.total(),
        duration == null ? 0L : duration,
        message.failReason(),
        message.failHint(),
        message.createdAt());
  }

  /**
   * 引用快照 ⇒ 契约那 11 个字段（{@code null} 保持 {@code null}）。
   *
   * <p>★ <strong>委托给 {@link ChatStreamWriter#refsOf}</strong>，不在这里再写一份：同一个引用会同时出现在 SSE 的 {@code
   * refs} 帧与历史消息的 {@code refs} 里，两处各写一份映射就会出现"流里带 {@code classification}、历史里不带"这种不报错的分叉（{@code #27}
   * 刚给这个字段加了第 11 个分量）。
   */
  private static List<Map<String, Object>> refsOf(List<SourceRef> references) {
    return references == null ? null : ChatStreamWriter.refsOf(references);
  }
}
