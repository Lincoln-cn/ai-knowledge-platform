package cn.lincoln.aiplatform.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 消息（{@code 02 §2.6} 的独立聚合 · 表 {@code cv_message} · 迁移 {@code V33}）。
 *
 * <h3>★★ 这个类值钱的地方是那台<strong>三态状态机</strong>与"停止保留内容"，请连着读</h3>
 *
 * <p>四条约束都必须<strong>由这个类挡住</strong>，而不是靠调用方自觉 —— 每一条都有"不报错但结果错"的形态：
 *
 * <ol>
 *   <li><strong>三态状态机</strong>：{@code GENERATING → GENERATED | STOPPED | FAILED}。四个终态入口 （{@link
 *       #complete} / {@link #stop} / {@link #fail}）与唯一的状态入口 {@link #appendChunk} 都先过 {@link
 *       #requireGenerating}。★ <strong>终态不可再转</strong>：{@code GENERATED} 之后再来一次 {@code complete} 或
 *       {@code appendChunk} 都必须抛。若允许，症状是"已完成的答案在库里又长出一截" —— 落库内容与用户看到的内容分叉，而两边都不报错。
 *   <li><strong>文本累积</strong>：{@link #appendChunk} <strong>只追加</strong>，从不覆盖。★ 它是 {@code private}
 *       的：唯一的公开入口是 {@link #appendText} —— 一处实现，两个名字只是"单块 / 整段"的人称差异。
 *   <li><strong>停止保留已生成内容</strong>：{@link #stop} 把状态置 {@code STOPPED}，
 *       <strong>正文一个字节都不动</strong>（{@code 03 §1.4}：「用户点停止 → flush → STOPPED，内容保留」； {@code 02 §2.6}
 *       的 MSG-I2 原话"部分答案不能丢"）。★ 这一条是 {@code ROADMAP §二} 的 <strong>A5</strong>
 *       判据的领域侧读数：把"停止"实现成清空内容，用户会看到一条空白回答， 而重生成也因此失去"续写"的起点。
 *   <li><strong>tokens 用 {@link TokenUsage}</strong>：{@code total = prompt + completion} 由值对象
 *       <strong>导出</strong>（{@link TokenUsage#total()}），本实体不存第三个分量 —— 允许它独立取值就等于允许 {@code total !=
 *       prompt + completion}，而那种不一致没有任何一处会报错（{@code 10 §8.4}）。
 * </ol>
 *
 * <h3>★ tokens 与 durationMs 是<strong>可空</strong>的，而且"空"有语义</h3>
 *
 * <p>两个 token 分量连同 {@code duration_ms} 都为 {@code null} 时表示"这一步还没有发生过模型调用"， 它<strong>不等于
 * 0</strong>：{@code 10 §8.4} 把 {@code done.tokens} 定成"该次问答<strong>全部</strong>模型调用之和"， 而把"没调用"写成 0
 * 会让成本统计偏低，偏低幅度还随策略开关变化。所以聚合入口是 {@link #addUsage}（{@code null} = 这一路没有报用量 ⇒ 不变），终态入口收口时才把整段用量 一次交给
 * {@link TokenUsage}。
 *
 * <p>★ <strong>失败路径上已经发生的调用仍计入</strong>：先 {@link #addUsage} 若干次、再 {@link #fail}，
 * 库里那两个分量就是"这次失败之前真的花了多少"。这正是"失败也要记成本"的载体。
 *
 * <h3>★ 本批的边界（读这个类时请连着读）</h3>
 *
 * <ul>
 *   <li><strong>做</strong>：{@code V33} 的表 + 本实体（状态机 / 累积 / 引用快照 / tokens）+ {@link
 *       MessageRepository} + 真栈读写。
 *   <li><strong>不做</strong>：HTTP 端点、SSE 控制器、落库 flush 的<strong>定时驱动</strong> （{@code 03 §1.4} 的
 *       500ms / 200 字）、真模型调用、重生成的编排 —— 它们是 {@code /conversations/:id/messages} 那一批（{@code ROADMAP
 *       §11.2} 第 17 周）的事。 ★ 本类提供的是那批需要的<strong>全部领域动作</strong>，不是一条能跑的链路。
 *   <li><strong>不碰</strong>：{@code artifacts} / {@code attachments}（列在，但本批没有 Java 侧类型与写路径）、 {@code
 *       model_id}（同上）、{@code feedback}（它是 {@code fb_feedback} 表，属第 18 周）。
 * </ul>
 *
 * <h3>★ 引用快照（{@link #bindReferences}）</h3>
 *
 * <p>引用列表<strong>随消息落库</strong>，元素类型是 <strong>契约那一份</strong> {@link SourceRef} （{@code A4
 * 引用可展开溯源}的载体）。★ {@code 10 §8.2} 已于 2026-10-14 裁定：归因类字段 （{@code subQuestion} / {@code round} /
 * {@code matchedQuery}）<strong>走埋点、不进契约</strong>， 所以这里落库的就是契约那 10 个字段，一个不多、一个不少。
 *
 * <p>★ 快照是<strong>一次性的</strong>（{@link #bindReferences} 只能调用一次）：它是"用户在那一刻点开
 * 看到的那批来源"，不是"这个问题的当前检索结果"。允许覆盖的话，一次重跑检索就会把历史引用的出处改掉 ——
 * 而"当时到底引了什么"正是溯源要回答的那个问题。重生成是一条<strong>新的</strong>助手消息。
 */
@Entity
@Table(name = "cv_message")
public class Message {

  /** 正文上限<strong>不设</strong>（{@code text} 列）：Markdown 长答案没有合理上界，设一个反而会截断真答案。 */
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属会话。★ 不可变 —— 消息不搬家（与 {@code Conversation.userId} 同一条纪律）。 */
  @Column(name = "conversation_id", nullable = false, updatable = false)
  private UUID conversationId;

  /** {@code USER} / {@code ASSISTANT}。★ 不可变：改了它，这条消息在界面上会换到另一边。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, updatable = false, length = 16)
  private MessageRole role;

  /** 正文。★ 生成过程中<strong>增量追加</strong>；终态之后不再改变（见类注释）。 */
  @Column(name = "content", nullable = false)
  private String content;

  /** 状态。★ 只有 {@link #complete} / {@link #stop} / {@link #fail} 能改它，且都要求当前是 {@code GENERATING}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private MessageStatus status;

  /**
   * 引用快照（{@code jsonb}）。★ 可空 = "这一批引用从来没到过"（检索还没回来就收尾）； {@code []} = 明确的"零命中" —— 两者语义不同，不该被糊成同一个值。
   *
   * <p>★ 列名是 {@code reference_snapshot} 而<strong>不是</strong> {@code 02 §5.2} 写的 {@code references}：
   * {@code references} 是 PostgreSQL 的<strong>保留关键字</strong>，裸写会让整条迁移报 {@code syntax error at or
   * near "references"}（本批实测踩到过）。三处偏差的来龙去脉写在 {@code V33} 的文件头。
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "reference_snapshot")
  private List<SourceRef> references;

  /** 图表 / 导图 / PPT / 推荐问题（V1.1）。★ 本批只落列，值恒为 {@code null}，也没有 Java 侧类型。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "artifacts")
  private String artifacts;

  /** 附件引用。★ 本批只落列，值恒为 {@code null}。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attachments")
  private String attachments;

  /**
   * 提示词 token 数。★ 可空，且"空"有语义（见类注释）：{@code null} = 还没有发生模型调用。
   *
   * <p>★ 为什么不是 {@code int}：基元类型的默认 0 会把"没调用"与"调用了但用量为 0"糊成一个值， 而那正是成本账偏低的起点。
   */
  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  /** 生成 token 数。★ 同 {@link #promptTokens}。 */
  @Column(name = "completion_tokens")
  private Integer completionTokens;

  /** 生成耗时（毫秒）。★ 可空 = 还没有终态（耗时是终态才定下来的量）。 */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** 实际使用的模型。★ 本批只落列：写入它需要"这一轮用的是哪个模型"这个上下文，属下一批。 */
  @Column(name = "model_id")
  private UUID modelId;

  /** 失败原因。★ {@code FAILED} 时必填（库层 {@code chk_msg_failed_has_reason} 兜一道）。 */
  @Column(name = "fail_reason")
  private String failReason;

  /**
   * 失败后的"下一步怎么办"。
   *
   * <p>★ {@code 02 §5.2} 的 DDL 里<strong>没有</strong>这一列，而冻结契约的 {@code ChatMessage} 同时有 {@code
   * failReason} 与 {@code failHint}，{@code stream-types.ts} 也把 error 帧定成
   * "失败原因<strong>与下一步</strong>"。⇒ 只落 {@code fail_reason} 会让后半句无处可存（写进 reason
   * 就等于把两件事缝成一个字段，读端再想分开只能拆字符串）。这一处偏差与 {@code status} 的取值域同源 （契约优先），已在交付报告中点名请 Lead 裁。
   */
  @Column(name = "fail_hint")
  private String failHint;

  /**
   * 消息级赞踩（契约 {@code ChatMessage.feedback}）。{@code null} = 未评价。
   *
   * <p>★ 取值域由库层 {@code chk_msg_feedback}（{@code V35}）兜住； 而"<strong>只有助手消息可被评价</strong>"这条只能在应用层
   * （{@link #rate}）—— PG 的 {@code CHECK} 不能跨行引用 {@code role}。
   */
  // ★ EnumType.STRING 是必须的：Hibernate 6 对枚举默认 ORDINAL（smallint），
  //   而契约要 'UP'/'DOWN' 字面量、库里也是 varchar(8)。少这一行会在起容器时炸 schema validation
  //   （本批实测：found [varchar (Types#VARCHAR)], but expecting [smallint (Types#TINYINT)]），
  //   而那种红看起来像"迁移写错了"。
  @Enumerated(EnumType.STRING)
  @Column(name = "feedback", length = 8)
  private MessageFeedback feedback;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Message() {}

  /**
   * 建一条<strong>助手</strong>消息 —— 状态机从此开始（{@code GENERATING}）。
   *
   * <p>★ 为什么"助手消息的初值是 {@code GENERATING}"而不是"先 PENDING、开始生成时再转"：契约里 <strong>没有</strong> PENDING
   * 这个取值（只有三个终态 + 一个生成中）。而一行"已经开始生成、还没有第一个字"的助手消息 本来就该是"生成中" —— 界面上它显示的是光标在闪，不是空白。
   *
   * @param id 主键（由用例层生成 —— 应用侧的时间有序 UUID，与其它聚合根同口径）
   * @param conversationId 所属会话 ★ 必填
   * @param content 初始正文（正常是 {@code ''}；★ 只用在"从库里重建一条历史消息"的读写路径上）
   */
  public Message(UUID id, UUID conversationId, String content) {
    requireId(id, conversationId);
    this.id = id;
    this.conversationId = conversationId;
    this.role = MessageRole.ASSISTANT;
    this.content = content == null ? "" : content;
    this.status = MessageStatus.GENERATING;
  }

  /**
   * 建一条<strong>用户</strong>消息 —— 状态直接是 {@code GENERATED}。
   *
   * <p>★ MSG-I3（{@code 02 §2.6}）："用户消息的状态只能是 {@code DONE}"。落到契约那四个取值上就是 {@code
   * GENERATED}：用户消息在落库那一刻就已经"完成"了，它<strong>从来没有</strong>"生成中"这个阶段。
   *
   * <p>★ 为什么用一个私有构造路径而不是"公开构造器 + 让调用方传 role"：那样 {@code Message(id, conv, USER, GENERATING)}
   * 这种<strong>非法组合</strong>会成为一个可编译的表达式，而它的症状是界面上一条永远转圈的用户消息。 两个工厂方法把合法组合<strong>穷举</strong>掉了。
   *
   * @param id 主键
   * @param conversationId 所属会话 ★ 必填
   * @param question 提问正文（★ 空提问立刻抛 —— 一条没有内容的用户消息在界面上是一个空气泡， 而它不会被任何校验拦住）
   * @return 一条 {@code GENERATED} 的用户消息
   */
  public static Message user(UUID id, UUID conversationId, String question) {
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("用户消息的正文不能为空 —— 一条空气泡在界面上看着像卡住了，没有任何地方会报错");
    }
    Message message = new Message(id, conversationId, question);
    message.role = MessageRole.USER;
    message.status = MessageStatus.GENERATED;
    return message;
  }

  // ────────────────────────── 行为：生成过程 ──────────────────────────

  /**
   * 追加一段增量文本（{@code 03 §1.2} 的 {@code delta}）。
   *
   * <p>★ <strong>只追加</strong>：本方法（以及它调用的私有 {@link #appendChunk}）是正文唯一的写路径， 里面没有任何"替换 / 清空"的分支 ——
   * 那两种动作在生成链路里没有语义位置。
   *
   * <p>★ <strong>终态之后不许再追加</strong>：{@link #requireGenerating} 会抛。允许的话，一次迟到的
   * delta（模型流收尾与连接断开竞态）就会把"已经停止 / 已经失败"的内容继续加长，而库里那一段 与用户看到的那一段就此分叉。
   *
   * <p>★ <strong>空增量不抛</strong>（只做一次无操作）：模型流里"这一帧没有内容"是正常帧 （{@code StreamEvent.Delta} 的同一条口径）——
   * 对它报错会给调用方增加一条处处要写的判断。
   *
   * @param chunk 增量文本（可空 / 空串 = 无操作）
   */
  public void appendText(String chunk) {
    requireGenerating("appendText");
    appendChunk(chunk);
  }

  /**
   * 汇总一次模型调用的用量（{@code 10 §8.4}：查询理解 / 多跳判定 / 生成<strong>各算一次</strong>）。
   *
   * <p>★ 与其它转移方法不同，它<strong>允许在终态之后</strong>调用：失败与停止路径上"已经发生"的调用 仍然要计入成本（{@code 10
   * §8.4}），而报告那一刻往往就在 {@code fail} 前后。
   *
   * <p>★ {@code null} = 这一路没有报用量（例如纯本地判定），语义是<strong>不变</strong>，不是"加 0 后又归零"。
   *
   * @param usage 本次调用的用量（可空）
   */
  public void addUsage(TokenUsage usage) {
    if (usage == null) {
      return;
    }
    TokenUsage current = currentUsage();
    TokenUsage sum = current == null ? usage : current.plus(usage);
    this.promptTokens = sum.prompt();
    this.completionTokens = sum.completion();
  }

  // ────────────────────────── 行为：三个终态入口 ──────────────────────────

  /**
   * 正常生成完成 ⇒ {@code GENERATED}（终态）。
   *
   * <p>★ 语义三条：① 只在 {@code GENERATING} 上合法；② 正文<strong>不动</strong>（已完成的内容就是它）； ③
   * 记录本次问答的<strong>全部</strong>模型调用用量与耗时。
   *
   * <p>★ 关于已用量的合并：若这之前已经 {@link #addUsage} 过若干次（查询理解 / 多跳判定）， 而这里又传进来一份，两者<strong>相加</strong> ——
   * 那不是重复计数：{@code 10 §8.4} 的口径就是"全部调用之和"， 调用方要么逐次 {@code addUsage}，要么在收尾时一次给出，两种写法都必须得到同一个数。
   *
   * @param usage 本次问答的全部用量（可空 = 沿用之前 {@link #addUsage} 累计的那些）
   * @param durationMs 生成耗时（毫秒，非负）
   */
  public void complete(TokenUsage usage, long durationMs) {
    requireGenerating("complete");
    // ★ 先检查耗时能不能记（负值 / 已经记过都是输入或状态非法）⇒ 抛出去时**什么都没改**。
    //   ★ 顺序有讲究：`addUsage` 是**会改状态**的（把用量累加上去），所以它必须排在检查之后 ——
    //     反过来的话，一次被拒绝的收尾会留下"用量已经加了、状态还是生成中"的半改状态。
    //     （这一条是金丝雀逼出来的：临时让终态可再转之后，第二次 complete 在耗时的前置检查上抛，
    //      而那时 addUsage 已经生效 —— 断言能红，但坏在"抛了却改了"。）
    requireDurationRecordable(durationMs);
    addUsage(usage);
    this.durationMs = durationMs;
    this.status = MessageStatus.GENERATED;
  }

  /**
   * ★★ 用户主动停止 ⇒ {@code STOPPED}（终态）—— <strong>已生成的内容必须保留</strong>。
   *
   * <p>★ 本方法<strong>一个字节都不碰 {@link #content}</strong>：它只做两件事 —— 记耗时、置状态。之所以把这句话
   * 写在这里，是因为"停止"最容易被顺手实现成"收尾并清空"（看起来更整洁），而那样 A5 判据当场就假了：
   * 用户点停止后看到的是一条<strong>空白</strong>回答，而库里那一行也再没有任何"已经生成了什么"的痕迹。 {@code 03 §1.4} 的表格逐字写着「用户点停止 →
   * flush → {@code STOPPED}，<strong>内容保留</strong>」。
   *
   * <p>★ 为什么 {@code STOPPED} 是<strong>一等终态</strong>而不是"失败的另一种"：{@code 02 §2.6} 写着
   * "把停止设计为一个<strong>有内容的一等状态</strong>，前端才能正确处理（显示'已停止'并提供'继续/重新生成'）"。 混进 {@code FAILED}
   * 会让界面对一次正常的用户操作弹错误提示。
   *
   * @param durationMs 从开始生成到停止的耗时（毫秒，非负）
   */
  public void stop(long durationMs) {
    requireGenerating("stop");
    requireDurationRecordable(durationMs);
    // ★ 刻意不动 content：停止保留已生成内容（03 §1.4 / MSG-I2）。这一行是注释，也是本方法的全部语义。
    this.durationMs = durationMs;
    this.status = MessageStatus.STOPPED;
  }

  /**
   * 失败 ⇒ {@code FAILED}（终态）。
   *
   * <p>★ 与 {@link #stop} 一样<strong>保留已生成内容</strong>：失败路径上用户往往已经看到半段答案，
   * 把它清掉等于让"失败"看起来像"什么都没发生"（而用户会以为是自己没问到）。
   *
   * <p>★ <strong>原因必填</strong>（{@code 02 §5.2} 的 {@code chk_failure} 是同一个口径）：
   * 一条没有原因的失败在界面上只能显示一个空白提示，而"下一步怎么办"恰好是用户唯一需要的东西。 所以 {@code reason} 空白直接抛 ——
   * 那是<strong>输入非法</strong>，不是"状态没变"。
   *
   * @param reason 失败原因（必填，可读文案）
   * @param hint 下一步提示（可空；契约里 {@code failHint} 是 {@code string | null}）
   * @param durationMs 到失败为止的耗时（毫秒，非负）
   */
  public void fail(String reason, String hint, long durationMs) {
    requireGenerating("fail");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("失败原因不能为空 —— 界面上会显示一个空白失败提示，而'为什么失败'是用户唯一能据此行动的信息");
    }
    requireDurationRecordable(durationMs);
    this.failReason = reason;
    this.failHint = blankToNull(hint);
    this.durationMs = durationMs;
    this.status = MessageStatus.FAILED;
  }

  // ────────────────────────── 行为：引用快照与模型 ──────────────────────────

  /**
   * ★★ 把这一轮的引用列表<strong>快照</strong>到消息上（{@code A4 引用可展开溯源}的载体）。
   *
   * <p>语义三条：
   *
   * <ol>
   *   <li><strong>只能绑一次</strong>（重复调用抛）：快照是"用户当时点开看到的那批来源"，
   *       不是"这个问题的当前检索结果"。允许覆盖的话，一次重跑检索就改掉了历史引用的出处 —— 而"当时到底引了什么"正是溯源要回答的问题。★
   *       重生成是一条<strong>新的</strong>助手消息， 它有自己的一次绑定机会。
   *   <li><strong>非空列表逐字保留</strong>（含顺序与 {@code index} 的取值）： {@code index} 是正文里 {@code [N]}
   *       的编号，由上游（检索与上下文组装）决定， 在这里再排一次序就会多出第二个真相源。
   *   <li><strong>引用只属于助手消息</strong>（MSG-I4）：对用户消息绑定直接抛。
   * </ol>
   *
   * <p>★ 为什么允许在终态之后绑定：{@code refs} 与 {@code done} 是两条竞态的事件（检索慢时 {@code refs} 可能晚于最后一个 delta
   * 到达，{@code StreamAssembler} 的收尾就是把 refs 补出去）。 绑定的是"这一条消息的引用集合"，与它当时是不是已经收尾无关。
   *
   * @param refs 引用列表（{@code null} = 不绑；空列表 = 明确的零命中）
   */
  public void bindReferences(List<SourceRef> refs) {
    if (refs == null) {
      return;
    }
    requireAssistant("bindReferences");
    if (this.references != null) {
      throw new IllegalStateException("引用快照只能绑一次 —— 它是'当时看到的那批来源'，覆盖会改掉历史引用的出处（重生成请开一条新的助手消息）");
    }
    for (SourceRef ref : refs) {
      if (ref == null) {
        throw new IllegalArgumentException("引用列表里出现了 null 元素 —— 落到库里会是一个空洞，读回来时炸在别处");
      }
    }
    this.references = List.copyOf(refs);
  }

  /**
   * 记录实际使用的模型。
   *
   * <p>★ 与其它写入方法一样要求"还没有终态"：模型是在开始生成那一刻定下来的， 一次迟到的写入会把"这条答案是哪个模型答的"改成另一个值 —— 而它正是排查答案质量的入口。
   *
   * @param modelId 模型目录里的 id（可空 = 这一路没有模型，例如本地兜底）
   */
  public void bindModel(UUID modelId) {
    requireGenerating("bindModel");
    this.modelId = modelId;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID conversationId() {
    return conversationId;
  }

  public MessageRole role() {
    return role;
  }

  /** 正文。★ 终态之后它<strong>保留</strong>停止 / 失败那一刻已经生成的内容（MSG-I2）。 */
  public String content() {
    return content;
  }

  public MessageStatus status() {
    return status;
  }

  /**
   * 引用快照。
   *
   * <p>★ {@code null} 与空列表是<strong>两个不同的答案</strong>：{@code null} = 这一批引用从来没到过 （检索还没回来就收尾），空列表 =
   * 明确的"零命中"。所以这里<strong>不做</strong> {@code null → List.of()} 的兜底 —— 那会把两种情形糊成一个，而它们的排查方向完全不同。
   *
   * @return 引用列表（可能为 {@code null}）；不可变快照
   */
  public List<SourceRef> references() {
    return references == null ? null : List.copyOf(references);
  }

  /**
   * token 用量。
   *
   * <p>★ {@code null} = "这一步没有发生过模型调用"，不是 0（见类注释）。★ 有值时 {@code total} 由 {@link TokenUsage#total()}
   * 导出，库里<strong>没有</strong>第三个分量。
   *
   * @return 用量（可能为 {@code null}）
   */
  public TokenUsage tokens() {
    return currentUsage();
  }

  /** 生成耗时（毫秒）。★ 可空 = 还没有终态。 */
  public Long durationMs() {
    return durationMs;
  }

  /** 实际使用的模型 id（本批没有写路径，恒为 {@code null}）。 */
  public UUID modelId() {
    return modelId;
  }

  public String failReason() {
    return failReason;
  }

  /**
   * 失败后的下一步提示（可空）。
   *
   * <p>★ {@code null} 有两种来源且都是正常的：① 这次失败没有"下一步"（例如上游返回了不可重试的错误）； ② 这条消息根本没失败。区分它们要看 {@link
   * #status()}，不要从本字段猜。
   */
  public String failHint() {
    return failHint;
  }

  /** 消息级赞踩；{@code null} = 未评价。 */
  public MessageFeedback feedback() {
    return feedback;
  }

  /**
   * ★★ 重新生成：把这一条<strong>覆盖</strong>成一次新的生成（S3「重新生成」的领域侧入口）。
   *
   * <h3>契约已经定死的那一条</h3>
   *
   * <p>{@code api/stream-types.ts:172} 逐字：「{@code REGENERATE} <strong>覆盖</strong> {@code
   * targetMessageId} 对应的原答案（<strong>不新增消息对</strong>）」 ⇒ 旧答案<strong>被覆盖、不保留</strong>。 ★
   * 想"保留历史答案"是<strong>改契约</strong>（新增版本概念），不是实现时顺手保留。
   *
   * <h3>★★ 四样东西必须一起清（少清一样就是"数据自相矛盾"）</h3>
   *
   * <ol>
   *   <li><b>正文</b> —— 新答案还没写，留着旧正文会让"生成中"显示上一轮的答案；
   *   <li><b>引用快照</b> —— 引用是"<b>这条答案</b>引了哪些分段"，答案换了它必然重算；
   *   <li><b>失败原因 / 提示 / 耗时</b> —— 属于上一次生成；不清就会出现"新答案还在生成、 却带着上次的失败原因"；
   *   <li>★★ <b>赞踩（{@code feedback}）</b> —— 本批（{@code V35}）<strong>新引入</strong>的一列，
   *       也是最容易漏的一处：赞踩的语义是"用户对<b>这条回答</b>的评价"，回答被覆盖之后， 那条被评价的回答<strong>已经不存在了</strong>。★
   *       不清的症状是「<b>新答案带着旧评价</b>」—— 用户赞过、重新生成、界面上仍是"已赞"，而那条新答案他根本没看过。
   *       这比"少一个功能"严重：它是<strong>数据自相矛盾</strong>。
   * </ol>
   *
   * <h3>★ 用量为什么清零</h3>
   *
   * <p>保留累加会让这条消息的用量<strong>同时算上两次生成</strong> ⇒ 「这条答案花了多少」是错的，而它正是成本读数的来源。 ★
   * <strong>代价如实写</strong>：上一次的用量会<strong>丢</strong> （要留就得另存一列，{@code ROADMAP} 现在没有那个位）。这是有意的取舍，不是
   * bug。
   *
   * @throws IllegalArgumentException 不是助手消息，或它<strong>还在生成中</strong>
   */
  public void restart() {
    if (role != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("只有助手的回答可以重新生成（这条消息是 " + role + "）");
    }
    if (status == MessageStatus.GENERATING) {
      // ★ 与 rate 同一条守卫：还在跑的那一次先结束（或先停止），否则两个生成线程会抢同一行
      throw new IllegalArgumentException("这一条还在生成中，等它结束或先停止再重新生成");
    }
    this.content = "";
    this.references = null;
    this.failReason = null;
    this.failHint = null;
    this.durationMs = null;
    // ★★ 见 javadoc 第 4 条：赞踩指向的是**被覆盖掉的那条回答**，必须一起清
    this.feedback = null;
    // ★ 用量清零（有意的取舍，见 javadoc）
    this.promptTokens = null;
    this.completionTokens = null;
    this.status = MessageStatus.GENERATING;
  }

  /**
   * 赞 / 踩 / 取消评价（S3 的「赞/取消赞刷新后仍在」）。
   *
   * <h3>★ 三条守卫，各对应一种"能写进去但语义不成立"的形状</h3>
   *
   * <ol>
   *   <li><b>只有助手消息可被评价</b> —— 用户自己那条消息上的"赞"没有任何含义； ★ 库层 {@code CHECK} 做不到（不能跨行引用 {@code
   *       role}），所以必须在这里挡；
   *   <li><b>生成中不许评价</b> —— 一条还在 {@code GENERATING} 的消息，用户评的是"没写完的东西"； 而且它随后可能变成 {@code
   *       FAILED}，那时"赞"就与 {@code fail_reason} 自相矛盾；
   *   <li><b>可撤销</b> —— 传 {@code null} 表示取消，回到"未评价"。★ 这是契约明写的语义 （"赞踩互斥且<b>可撤销</b>：type 传 null
   *       表示取消"），不是容错。
   * </ol>
   *
   * <p>★ 幂等：重复提交同一个评价不报错、不改 {@code updated_at} 的语义（它本来就会被更新， 但"赞两次"不该变成一种错误 —— 用户的动作就是"赞"，不是"切换"）。
   *
   * @param next 目标评价；{@code null} = 取消
   * @throws IllegalArgumentException 违反上面第 1 或第 2 条
   */
  public void rate(MessageFeedback next) {
    if (role != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("只有助手的回答可以被赞/踩（这条消息是 " + role + "）");
    }
    if (status == MessageStatus.GENERATING) {
      throw new IllegalArgumentException("这一条还在生成中，等它结束再评价");
    }
    this.feedback = next;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  // ────────────────────────── 内部：状态机与累积的唯一出口 ──────────────────────────

  /**
   * 唯一的正文写路径 —— {@link #appendText} 与将来的"整段重放"都走这里。
   *
   * <p>★ 刻意 {@code private}：正文的写入必须只有一个出口，否则"某一条路径忘了先过状态检查"就会悄悄绕开 状态机（那一处的症状正是"已完成的消息又长出一截"）。
   *
   * @param chunk 增量文本（可空 / 空串 = 无操作）
   */
  private void appendChunk(String chunk) {
    if (chunk == null || chunk.isEmpty()) {
      return;
    }
    this.content = this.content + chunk;
  }

  /**
   * 状态机的<strong>唯一入口</strong>：任何会改生成过程状态的动作都先过这里。
   *
   * <p>★ 两条都要检查，因为它们的症状不同：
   *
   * <ul>
   *   <li><strong>只有助手消息参与生成</strong> —— 一条"生成中"的用户消息没有任何东西会去收尾它
   *       （它不是由模型生成的），界面上<strong>永远转圈</strong>；
   *   <li><strong>终态不可再转</strong> —— 允许的话，"已完成的答案又长出一截 / 被改成另一个终态" 不会有任何一处报错，而库里那一段与用户看到的那一段分叉。
   * </ul>
   *
   * <p>★ 抛 {@code IllegalStateException}（而不是 {@code IllegalArgumentException}）：这是
   * <strong>状态不对</strong>，不是参数不对 —— 与 {@code StreamAssembler} 的 {@code requireNotSettled}
   * 是同一条口径（同一个包里，两种错法由不同的异常区分开，调用方才能分别处置）。
   *
   * @param action 被拒绝的动作名（进错误信息，便于定位调用点）
   */
  private void requireGenerating(String action) {
    requireAssistant(action);
    if (status != MessageStatus.GENERATING) {
      throw new IllegalStateException(
          action
              + " 发生在终态之后：当前 status="
              + status
              + "。三态状态机是 GENERATING → GENERATED | STOPPED | FAILED，终态不可再转"
              + "（否则已生成的内容会在库里继续变化，而它不会报错）");
    }
  }

  /** 引用只属于助手消息（MSG-I4）；用户消息没有生成过程，也谈不上引用。 */
  private void requireAssistant(String action) {
    if (role != MessageRole.ASSISTANT) {
      throw new IllegalStateException(action + " 只对助手消息合法：当前 role=" + role + "（MSG-I3 / MSG-I4）");
    }
  }

  /**
   * 检查耗时能不能记（<strong>只读检查，不改任何状态</strong>）。
   *
   * <p>★ 与 {@link #appendChunk} 一样，它是三个终态入口的共同前置：耗时是"从开始生成到收尾"的一个量， 只允许记一次。被记第二次就说明有两条收尾路径都在写它 ——
   * 那种情况下先到的那条才是真相， 后到的那条应当走 {@link MessageStatus#isTerminal()} 那条检查被挡在外面 （本检查是它之后第二道网）。
   *
   * <p>★★ <strong>为什么把"检查"与"赋值"拆成两步</strong>：三个入口都要"先全部检查、再一起改"，
   * 否则一次被拒绝的收尾会留下<strong>半改状态</strong>（例如用量已经累加、状态还是生成中）—— 那是比"抛得晚一点"更坏的形态：调用方以为这次转移被拒了，而对象其实已经变了。
   */
  private void requireDurationRecordable(long durationMs) {
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs 不能为负：" + durationMs);
    }
    if (this.durationMs != null) {
      throw new IllegalStateException("耗时已经记过了（" + this.durationMs + "ms）—— 一条消息只有一次收尾");
    }
  }

  /** 当前累计用量；两个分量都没有时返回 {@code null}（= "还没有发生过模型调用"，不是 0）。 */
  private TokenUsage currentUsage() {
    if (promptTokens == null && completionTokens == null) {
      return null;
    }
    return new TokenUsage(orZero(promptTokens), orZero(completionTokens));
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static void requireId(UUID id, UUID conversationId) {
    if (id == null || conversationId == null) {
      throw new IllegalArgumentException("消息 id 与所属会话 id 不能为空");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
