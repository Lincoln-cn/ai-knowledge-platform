package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.stream.EventKind;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 {@link StreamEvent}（领域事件）<strong>逐字翻成 SSE 帧</strong>，并写下契约规定的心跳注释帧。
 *
 * <h3>★★ 这一层只做三件事，每一件都有"不做会怎样"</h3>
 *
 * <ol>
 *   <li><strong>事件名从 {@link EventKind#wireName()} 取</strong>，不在这个方法里手写字符串 ——
 *       手写等于把"事件名有几个、分别叫什么"变成两处口径（契约里那六个名字分布在注释与前端 {@code switch} 两处， 已有 {@code
 *       StreamContractCrossCheckTest} 在守；这里再抄一份就多一处要跟着改的）。
 *   <li><strong>心跳是注释帧</strong>（一整行以 {@code :} 开头、<strong>没有</strong> {@code event:}/{@code data:}
 *       行）： 前端解析器把它跳过并<strong>重置闲置计时</strong>。把心跳写成业务事件（例如 {@code event: heartbeat}）
 *       会让前端多一个不认识的分支——不影响它工作，但"心跳到底发没发"就不再可被同一套断言读到了。
 *   <li><strong>每帧立刻 flush</strong>（不是等缓冲区满）：{@link StreamEvent.Delta} 的价值就是"边生成边到"，
 *       攒在缓冲区里会让首字延迟与逐字效果一起消失，而<strong>没有任何地方会报错</strong>。
 * </ol>
 *
 * <h3>★ 帧里的字段名逐字照契约</h3>
 *
 * <p>{@code meta} = {@code {messageId, traceId, model}} · {@code refs} = {@code SourceRef[]} ·
 * {@code delta} = {@code {text}} · {@code artifact} = {@code {type, ...}} · {@code done} = {@code
 * {status, messageId, tokens:{prompt,completion,total}, durationMs}} · {@code error} = {@code
 * {reason, hint?}}。★ 字段名对不上不会让前端报错（{@code JSON.parse} 出来的字段是 {@code
 * undefined}）——页面照样渲染，只是引用栏空着、终态认不出，这正是本批要防的那类静默失效。
 */
public final class ChatStreamWriter {

  /** 心跳载荷（契约 {@code HEARTBEAT_PAYLOAD = 'keep-alive'}）。 */
  public static final String HEARTBEAT_PAYLOAD = "keep-alive";

  /** SSE 注释帧的行首（契约 {@code HEARTBEAT_PREFIX = ':'}）。 */
  public static final String HEARTBEAT_PREFIX = ":";

  private final ObjectMapper json;

  public ChatStreamWriter(ObjectMapper json) {
    this.json = json;
  }

  /**
   * 把一条领域事件写成 wire 帧并 <strong>flush</strong>。
   *
   * @param event 领域事件
   * @param out 响应输出流（{@code text/event-stream}）
   * @return 写出去的字节数（供调用方刷新"最后一次活动时间"；心跳与业务帧都算活动）
   * @throws IOException 连接已经断了（{@code DISCONNECT} 路径的唯一观测点）
   */
  public int write(StreamEvent event, Writer out) throws IOException {
    if (event instanceof StreamEvent.Heartbeat heartbeat) {
      return writeComment(heartbeat.comment(), out);
    }
    String payload = json.writeValueAsString(payloadOf(event));
    String frame = "event: " + event.kind().wireName() + "\ndata: " + payload + "\n\n";
    return writeRaw(frame, out);
  }

  /**
   * 写一条注释帧（{@code : keep-alive}）。
   *
   * <p>★ 单独一个方法而不是"让调用方拼字符串"：心跳帧的形状（行首冒号 + 空行收尾）是契约①， 它只有一处实现才谈得上"契约里写的那个形状，就是真的发出去的那个形状"。
   *
   * @param comment 载荷（不含行首冒号）
   * @return 写出去的字节数
   * @throws IOException 连接已经断了
   */
  public int writeComment(String comment, Writer out) throws IOException {
    return writeRaw(HEARTBEAT_PREFIX + " " + comment + "\n\n", out);
  }

  /** 真实写 + flush；字节数按 UTF-8 计（中文正文下字符数 != 字节数）。 */
  private int writeRaw(String frame, Writer out) throws IOException {
    out.write(frame);
    out.flush();
    return frame.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * 领域事件 ⇒ wire 上的 {@code data} 对象。
   *
   * <p>用 {@link LinkedHashMap} 而不是把 record 直接交给 Jackson：record 的分量名会成为字段名， 而 {@code StreamEvent}
   * 里那几个 record 的分量名虽然与契约同名，但**这个映射关系是本类的职责** （它是"领域形状 ⇒ 线上形状"唯一的那一步），写成显式映射表，改契约时有一个明确的改动点。
   *
   * @param event 领域事件
   * @return 有序的字段映射（{@code null} 值会被 Jackson 序列化成 {@code null}，与契约的"可选字段"一致）
   */
  private static Map<String, Object> payloadOf(StreamEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    switch (event) {
      case StreamEvent.StreamMetaEvent meta -> {
        payload.put("messageId", meta.messageId());
        payload.put("traceId", meta.traceId());
        payload.put("model", meta.model());
      }
      case StreamEvent.References references -> payload.put("refs", refsOf(references.refs()));
      case StreamEvent.Delta delta -> payload.put("text", delta.text());
      case StreamEvent.StreamDoneEvent done -> {
        payload.put("status", done.status().name());
        payload.put("messageId", done.messageId());
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", done.tokens().prompt());
        tokens.put("completion", done.tokens().completion());
        // ★ total 由两个分量导出（TokenUsage.total()），不单独存 —— 独立取值就等于允许它不等于前两者之和
        tokens.put("total", done.tokens().total());
        payload.put("tokens", tokens);
        payload.put("durationMs", done.durationMs());
      }
      case StreamEvent.StreamErrorEvent error -> {
        payload.put("reason", error.reason());
        payload.put("hint", error.hint());
      }
      case StreamEvent.Heartbeat heartbeat -> payload.put("comment", heartbeat.comment());
    }
    return payload;
  }

  /**
   * 引用列表 ⇒ 契约那 11 个字段。
   *
   * <p>★ 逐字照 {@code stream-types.ts} 的 {@code SourceRef}，一个不多一个不少： 多一个字段前端不读也照常工作，于是它会安静地活很久 ——
   * 而"契约里到底有什么"就变成了两处口径。
   *
   * <p>★★ 反过来更要紧：<strong>少一个字段同样不会报错</strong>。所以这个映射是手写的（不是反射 / 不是自动序列化）， 给 record
   * 加分量时<strong>必须</strong>同批加到这里 —— 否则那个字段在类型上存在、在线上不存在， 而两个测试都可能照样绿（{@code
   * SourceRefSerializationTest} 验的是 jsonb 那一侧、本类验的是 SSE 这一侧）。 {@code classification}（{@code
   * #27}）就是这么加上来的：它今天没有前端消费点，但 <strong>契约成员就必须出现在线上</strong>，否则"契约里到底有什么"又多一处口径。
   */
  private static List<Map<String, Object>> refsOf(List<SourceRef> refs) {
    return refs.stream()
        .map(
            ref -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("index", ref.index());
              item.put("title", ref.title());
              item.put("docName", ref.docName());
              item.put("kbName", ref.kbName());
              item.put("score", ref.score());
              item.put("rank", ref.rank());
              item.put("full", ref.full());
              item.put("segmentId", ref.segmentId());
              item.put("documentId", ref.documentId());
              item.put("kbId", ref.kbId());
              // ★ 枚举 ⇒ 枚举名（Jackson 的默认口径，与库里 varchar(16) 的机器码逐字同值）
              item.put("classification", ref.classification());
              return item;
            })
        .toList();
  }

  /**
   * 把 {@link IOException} 包成 {@link UncheckedIOException}（给 {@code StreamingResponseBody} 的 lambda
   * 用）。
   *
   * <p>★ 存在的理由：{@code StreamingResponseBody#writeTo} 只许抛 {@code IOException}， 而 lambda 里调 {@link
   * #write} 时若要把异常往外传，就得在每一处 {@code try/catch}。包一层之后，"写失败"这一个事实 在链路里只有一个形状 —— 调用方按它判 {@code
   * DISCONNECT}。
   */
  public static UncheckedIOException disconnected(IOException cause) {
    return new UncheckedIOException("SSE 连接已断开（客户端断开 / 半途断流）", cause);
  }
}
