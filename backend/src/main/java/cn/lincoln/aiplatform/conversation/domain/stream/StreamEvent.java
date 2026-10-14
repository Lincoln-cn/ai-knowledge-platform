package cn.lincoln.aiplatform.conversation.domain.stream;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.util.List;

/**
 * 流式回答的<strong>事件模型</strong>：一条有序事件序列里的元素。
 *
 * <p>取值与字段<strong>逐字对齐</strong> {@code frontend/src/api/stream-types.ts}（{@code 10 §8.3}："SSE
 * 一个字节都不用改"）。 每个实现都带一个 {@link #kind()}，于是"序列的形状"可以在<strong>不看 payload</strong> 的情况下被断言 （{@code
 * kinds = [META, REFS, DELTA, …, DONE]}）。
 *
 * <h3>为什么是 sealed</h3>
 *
 * <p>sealed 让 {@code switch} 可以被编译器穷尽检查：第 17 周接真模型时若新增一种事件（例如 {@code tool}）， 加一个 {@code permits}
 * 成员（或新增一个嵌套 record）就会让所有分派点<strong>编译期</strong>暴露出来，而不是等到线上发现前端多了一个 认不出的事件。这正是 {@code 03 §1.2}
 * 说的"协议本身的可扩展性"落在代码上的那一半。
 *
 * <h3>★ 本批的连接点（谁消费它）</h3>
 *
 * <p>本批<strong>不写</strong> SSE 控制器：事件序列由 {@link StreamAssembler} 产出，测试按 {@link #kind()} 断言顺序。第 17
 * 周把 {@code StreamEvent} 翻成 wire 帧（{@code event:} + {@code data:}） 的那一层是传输层，它按本模型的取值分派。
 *
 * <p>★ 六个实现<strong>全是本接口的嵌套 record</strong>，所以 {@code permits} 子句由编译器<strong>推断</strong>（不手写）：
 * 手写一份清单等于把"有哪几种事件"变成两处口径，而两处口径的第一种坏法就是漏写一个（那会让实现类编译不过 —— 至少这个错是响的；但清单与实现不一致的状态会一直存在）。
 */
public sealed interface StreamEvent {

  /**
   * 这条事件在 wire 上的事件名。
   *
   * @return 事件类型（取值见 {@link EventKind}）
   */
  EventKind kind();

  /**
   * 连接建立（契约的 {@code meta} 事件）—— 字段与 {@code StreamMeta} <strong>逐字一致</strong>。
   *
   * <p>★ 它是序列的<strong>第一条</strong>：先有 {@code messageId}，"生成中停止 / 重生成"才可用（{@code 03 §1.2}）。
   *
   * @param messageId 本条助手消息 id
   * @param traceId 链路追踪 id
   * @param model 本次使用的模型标识
   */
  record StreamMetaEvent(String messageId, String traceId, String model) implements StreamEvent {

    public StreamMetaEvent {
      requireText(messageId, "messageId");
      requireText(traceId, "traceId");
      requireText(model, "model");
    }

    @Override
    public EventKind kind() {
      return EventKind.META;
    }

    private static void requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("meta 的必填字段 " + field + " 不能为空");
      }
    }
  }

  /**
   * 逐字增量（契约的 {@code delta} 事件）。
   *
   * @param text 本次增量文本
   */
  record Delta(String text) implements StreamEvent {

    /**
     * 空增量<strong>拒绝</strong>。
     *
     * <p>★ 理由不是"不可能发生"，而是它发生了也<strong>没有任何信息</strong>：wire 上会多一个 {@code event: delta} + {@code
     * data: {"text":""}} 的帧，前端拿到空串追加一次、什么也不显示。 而"模型返回了空块"这件事如果真发生，它该走的是"生成了 0 个字的答案"（终态照样是 {@code
     * GENERATED}）， 不是一串空帧。拒绝它的收益是：把"上游开始吐空块"这个通常意味着上游出问题的信号，
     * 在第一个空块上就变成一条<strong>有名字的失败</strong>，而不是让它淹在一串空帧里。
     */
    public Delta {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("delta 的文本不能为空 —— 空增量在 wire 上是一个没有信息的帧");
      }
    }

    @Override
    public EventKind kind() {
      return EventKind.DELTA;
    }
  }

  /**
   * 引用来源（契约的 {@code refs} 事件）。
   *
   * <p>★ <strong>一整批一起到</strong>，不是"一条一个事件"：契约里 {@code onRefs} 收到的是 {@code SourceRef[]}，{@code
   * index} 也要求"从 1 连续递增"。逐条发会让"第 3 条到之前正文里已经出现了 {@code [3]}"变成可能。
   *
   * <p>★ <strong>空列表是合法的，而且必须发出去</strong>：它表达的是"检索跑完了、零命中"——
   * 那与"检索根本没跑"是两件不同的事（前者可以解释成"知识库里没有"，后者是链路坏了）。 若把空 {@code refs} 吞掉，前端就再也分不出这两者。
   *
   * @param refs 引用列表（可以为空）
   */
  record References(List<SourceRef> refs) implements StreamEvent {

    public References {
      refs = List.copyOf(refs);
    }

    @Override
    public EventKind kind() {
      return EventKind.REFS;
    }
  }

  /**
   * 终态收尾（契约的 {@code done} 事件）—— 字段与 {@code StreamDone} <strong>逐字一致</strong>。
   *
   * <p>★ 一条流里<strong>恰好一条</strong> {@code done}。它的唯一出口是 {@link StreamAssembler#finish}。
   *
   * @param status 终态（三态之一）
   * @param messageId 本条助手消息 id（与 {@code meta} 同一个值）
   * @param tokens 该次问答<strong>全部</strong>模型调用的 token 总和（{@code 10 §8.4}）
   * @param durationMs 耗时（毫秒）
   */
  record StreamDoneEvent(StreamStatus status, String messageId, TokenUsage tokens, long durationMs)
      implements StreamEvent {

    public StreamDoneEvent {
      if (status == null) {
        throw new IllegalArgumentException("done 的 status 不能为空 —— 契约里它是必填，缺了前端认不出终态");
      }
      if (messageId == null || messageId.isEmpty()) {
        throw new IllegalArgumentException("done 的 messageId 不能为空 —— 前端靠它把终态挂回那一条消息");
      }
      if (tokens == null) {
        throw new IllegalArgumentException(
            "done 的 tokens 不能为空 —— 契约里它是必填；null 到前端会静默变成 undefined / NaN，用 TokenUsage.zero()"
                + " 表示'一次调用都没有'");
      }
      if (durationMs < 0) {
        throw new IllegalArgumentException("done 的 durationMs 不能为负：" + durationMs);
      }
    }

    @Override
    public EventKind kind() {
      return EventKind.DONE;
    }
  }

  /**
   * 失败原因与下一步（契约的 {@code error} 事件）—— 字段与 {@code onError(reason, hint)} <strong>逐字一致</strong>。
   *
   * <p>★ {@code error} <strong>不是终态</strong>：契约原文"服务端 error 帧之后仍必须跟 done 帧收尾"。 所以本类没有 {@code
   * isTerminal} 这类方法 —— 终态只有一个地方能产生，就是 {@code done}。
   *
   * @param reason 可读失败原因（契约的 {@code payload.reason}）
   * @param hint 下一步建议（契约的 {@code payload.hint}，可空）
   */
  record StreamErrorEvent(String reason, String hint) implements StreamEvent {

    public StreamErrorEvent {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("error 的 reason 不能为空 —— 一个没有原因的失败没有办法被处置");
      }
      if (hint != null && hint.isBlank()) {
        hint = null;
      }
    }

    @Override
    public EventKind kind() {
      return EventKind.ERROR;
    }
  }

  /**
   * 心跳（契约①的注释帧 {@code : keep-alive}）。
   *
   * <p>★ 它不是业务事件：wire 上<strong>没有</strong> {@code event:} 行与 {@code data:} 行，前端解析器把它跳过
   * 并<strong>重置闲置计时</strong>。放在事件序列里是为了让"心跳能出现在检索阶段"可被断言（{@code 03 §1.6}）。
   *
   * @param comment 注释帧内容（走 wire 时前面加一个 {@code ':'}，本批只承载载荷）
   */
  record Heartbeat(String comment) implements StreamEvent {

    public Heartbeat {
      if (comment == null || comment.isBlank()) {
        throw new IllegalArgumentException("心跳的载荷不能为空 —— 一个空注释帧在反向代理眼里仍然是'没有字节'");
      }
    }

    @Override
    public EventKind kind() {
      return EventKind.HEARTBEAT;
    }
  }
}
