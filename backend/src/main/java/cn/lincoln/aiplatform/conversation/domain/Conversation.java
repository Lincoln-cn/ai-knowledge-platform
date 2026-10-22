package cn.lincoln.aiplatform.conversation.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 会话（{@code 02 §2.6} 的"轻量聚合" · 表 {@code cv_conversation} · 迁移 {@code V32}）。
 *
 * <h3>★★ 这个类唯一值钱的地方是 {@link #raiseClassification} —— 请连着读这一段</h3>
 *
 * <p>会话密级 = {@code max(该会话历史里所有引用过的 chunk)}（{@code 13 §2.2}）： <strong>一旦引用过机密，整个会话就是机密的</strong> ——
 * 后续每一轮回答都按会话密级处理。它是<strong>跨轮累计</strong>的， 所以它的形状是"一个只增不减的水位"，而不是"每轮算一次的值"。
 *
 * <p>★ <strong>为什么落在领域层，而不是"写入方算好再传进来"</strong>： {@code ROADMAP §11.7} 与 {@code 13 §2.2}
 * 把载体定成<strong>写侧维护 O(1)</strong>（{@code max_classification = max(现值, max(本轮 refs))}）， 而那个 {@code
 * max} 一旦由调用方各写一遍，就必然出现"某一处写成了 {@code refs} 而不是 {@code max(现值, refs)}"——
 * 症状是<strong>静默降密</strong>（会话从机密掉回内部，且没有任何地方报错）。 所以"只升不降"这条不变量<strong>在领域层挡住</strong>： {@link
 * #raiseClassification} 是唯一能改这个字段的方法，它对下调<strong>不做任何事</strong>（见该方法）。
 *
 * <p>★★ <strong>2026-10-16 补（{@code #108}）—— 落库那一步不在这个对象上，而在库侧一条 UPDATE</strong>：
 * 上面那条"唯一写入口"只管<strong>内存里的这个对象</strong>；而 {@code save()} 是"读 → 改 → 更新"，
 * <strong>同事务之外</strong>的两次并发抬升会<strong>丢更新</strong>（各自读到同一个现值、各自提交）⇒ 会话密级<strong>滞后于真实
 * max</strong>（不报错、不留痕，且它是后续每一轮处理内容的依据）。 {@code #108} 的择一是 **(a) 库侧单调**：落库走 {@link
 * ConversationRepository#raiseClassification}（一条 {@code update … where id = ? and
 * max_classification in (严格低于本轮 max 的那些档)}）， 它<strong>不读实体</strong> ⇒ "拿陈旧实体写回"这条路径在签名上就不存在。
 *
 * <p>★★ <strong>"会不会被抬升"这条规则只有一处</strong>：{@link #strictlyLowerThan}。 内存里的 {@link
 * #raiseClassification} 与库侧那条 UPDATE 的 {@code where} <strong>同取它</strong> —— 于是"口径在 SQL
 * 里再写一遍"这个代价被压到"只有集合成员判定，没有第二份密级次序" （两侧一致由 {@code ConversationClassificationWriteTest} 的等价用例逐对钉住）。
 *
 * <h3>★ 本批的边界（读这个类时请连着读）</h3>
 *
 * <ul>
 *   <li><strong>做</strong>：数据层（{@code V32} 的表 + 本实体 + {@link
 *       ConversationRepository}）与<strong>领域不变量</strong>。
 *   <li><strong>不做</strong>：服务层编排（创建会话 / 追加消息 / 落引用集合那一整条链）与 HTTP 端点 —— 它们的载体是第 17 周的 SSE
 *       问答链路（{@code 13 §8.3} 的 M4b 行）。★ <strong>写侧的"落库机制"已随 {@code #108} 落</strong>（见上一段： {@link
 *       ConversationRepository#raiseClassification}），但它的<strong>生产调用方仍未出现</strong>：
 *       "本轮引用集合的最高密级"这个值今天到不了落库那一刻（契约的 {@code SourceRef} 十个字段里<strong>没有</strong>
 *       classification，{@code 10 §8.2} 已裁定归因类字段走埋点），所以那一步要连带改检索侧的装配 —— 属第 17 周。
 *       这一点是<strong>刻意</strong>的：{@code #27} 判的是"表与领域口径现在落，消费者第 17 周接"， 而不是"先造一个空壳服务假装会话密级在工作"（那正是该
 *       Issue 点名的 A 类病）。
 *   <li><strong>不碰</strong>：{@code cv_message}（消息、引用快照、附件）—— 那是第 17 周的下一张表。
 * </ul>
 *
 * <h3>★ 字段口径（{@code 02 §2.6}）</h3>
 *
 * <p>{@link #assistantId} 可空 = <strong>通用问答</strong>（不属于任何一个应用）—— 可空是语义的一部分，不是"还没填"。 {@link
 * #deletedAt} 列存在<strong>不等于</strong>已经有删除入口：本批连 {@code markDeleted} 都<strong>不提供</strong>，
 * 免得一个没有调用方的领域方法被读成"删除功能已经在"。
 *
 * <p>★ 密级列<strong>没有</strong> {@code classification_rank} 生成列（与 {@code kb_segment} / {@code
 * kb_qa_pair} 刻意不同）：那一列是<strong>检索 pre-filter</strong> 的绑定列，而会话密级<strong>不参与</strong>
 * pre-filter（过滤仍然发生在 chunk 那一级）—— 多一列没人读的 rank 只会多一处漂移点。
 */
@Entity
@Table(name = "cv_conversation")
public class Conversation {

  /** 标题列宽（{@code V32} 的 {@code varchar(256)}）。 */
  private static final int MAX_TITLE_LENGTH = 256;

  /**
   * 新建会话时的占位标题。
   *
   * <p>★ 它从用例层搬到这里，理由是「谁改它、谁读它」：
   *
   * <ul>
   *   <li>创建时写入它（{@code ConversationListService.create}）；
   *   <li><b>首个问题到来时判断「标题还是不是占位」并替换它</b>（{@code ChatTurnWriter}）。
   * </ul>
   *
   * 两处各写一份字面量，改了一处就会变成「判断永远不成立、标题永远是占位值」—— 那正是本改动之前真实存在的症状（会话有 2 条消息，标题却还是「新会话」）。
   */
  public static final String PLACEHOLDER_TITLE = "新会话";

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 归属：会话的所有者。★ 不可变 —— 会话不转让（与"密级只升不降"同一条纪律：越少可变的东西，越少静默越权的路径）。 */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** 源自应用。★ 可空 = 通用问答（{@code 02 §2.6}）。 */
  @Column(name = "assistant_id")
  private UUID assistantId;

  @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
  private String title;

  /**
   * 展示用的冗余计数（列表页不必为每一行数一次消息）。
   *
   * <p>★ <strong>写入方（2026-10-16 补）</strong>：{@link ConversationRepository#recordMessage} —— 每落库一条消息
   * <strong>+1</strong>，由消息落库那一处（{@code ChatTurnWriter#prepare}）在<strong>同一个事务</strong>里调用。 在这一批之前它
   * <strong>恒为 0</strong>（列在、没人写），症状是"列表页的消息数永远是 0"。
   *
   * <p>★ 它是<strong>增量</strong>而不是"重算 count(*)"：重算在并发下读到的是<strong>语句快照</strong> （另一个事务的消息还没提交）⇒
   * 少记；增量在行锁上重评估 ⇒ 不漏。 ★ 写法口径（谁在何时 +1）见 Issue {@code #44}；
   * <strong>存量回填与巡检仍缺</strong>（本批只做写入侧，见交付报告"明确没做的"）。
   */
  @Column(name = "message_count", nullable = false)
  private int messageCount;

  /**
   * 最后一条消息的时间 = {@code max(该会话消息的 created_at)} —— ★ 与 {@link #maxClassification} 同族：
   * 它是一个<strong>只增不减的水位</strong>，不是"最近一次写它是什么时候"。
   *
   * <p>★ <strong>写入方</strong>：同 {@link #messageCount}，且它取自 <strong>消息表自己的 {@code
   * created_at}</strong>（{@code greatest(现值, max(cv_message.created_at))}）—— 于是它与 {@code
   * max(cv_message.created_at)} <strong>逐微秒相等</strong>，不会出现"两个时钟各写一个时间"的分叉（那会让 {@code
   * idx_conv_user} 排出来的"最近发言"与消息列表对不上）。可空 = 这个会话还没有任何消息。
   */
  @Column(name = "last_message_at")
  private Instant lastMessageAt;

  /**
   * ★★ 会话累计密级（{@code 13 §2.2}）—— <strong>只升不降</strong>，见 {@link #raiseClassification}。
   *
   * <p>★ {@code nullable = false} + 默认 {@code PUBLIC}（库层 {@code default 'PUBLIC'}，实体侧由构造器保证）：
   * 新会话还没有引用过任何 chunk ⇒ 累计值就是最低档。这不是"宽松的默认值"，而是<strong>空引用集合的 max</strong>。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "max_classification", nullable = false, length = 16)
  private ClassificationLevel maxClassification;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** 软删。★ 列存在 ≠ 有删除入口（本批没有服务层，也没有任何写它的路径）。 */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected Conversation() {}

  /**
   * 建一个会话（新会话的累计密级 = {@link ClassificationLevel#PUBLIC}）。
   *
   * <p>★ 默认取 {@code PUBLIC} 是<strong>事实</strong>而不是占位：还没有引用过任何 chunk ⇒ "历史引用集合的 max" 就是最低档。
   * 换一个默认（例如跟着创建者的 clearance 走）会让"这个会话到底看过什么"这件事 从第一秒起就是假的——而它正是后续所有越权判定的输入。
   *
   * @param id 主键（由用例层生成 —— 应用侧的时间有序 UUID，与其它聚合根同口径）
   * @param userId 归属（会话所有者）★ 必填
   * @param assistantId 源自应用（可空 = 通用问答）
   * @param title 标题（可空 / 空白 ⇒ {@code ''}；默认取首个问题前若干字是<strong>用例层</strong>的事）
   */
  public Conversation(UUID id, UUID userId, UUID assistantId, String title) {
    if (id == null || userId == null) {
      throw new IllegalArgumentException("会话 id 与归属用户 id 不能为空");
    }
    this.id = id;
    this.userId = userId;
    this.assistantId = assistantId;
    this.title = title == null ? "" : title.trim();
    if (this.title.length() > MAX_TITLE_LENGTH) {
      throw new IllegalArgumentException("会话标题不能超过 " + MAX_TITLE_LENGTH + " 字");
    }
    this.messageCount = 0;
    // ★ 新会话没有引用过任何 chunk ⇒ 累计密级就是最低档（不是"忘了填"）
    this.maxClassification = ClassificationLevel.PUBLIC;
  }

  // ────────────────────────── 行为 ──────────────────────────

  /**
   * ★★ 按本轮回答的引用集合<strong>抬升</strong>会话密级（{@code 13 §2.2} 的写侧维护）。
   *
   * <p>★★ <strong>它管的是<em>内存里的这个对象</em>；落库那一步不在这里</strong>（{@code #108} 的择一 (a)）： 把它改完再 {@code
   * save()} 是"读 → 改 → 更新"，同事务之外的并发抬升会丢更新。 生产写入点是 {@link
   * ConversationRepository#raiseClassification}（库侧一条 UPDATE，<strong>不经过这个对象</strong>）；
   * 本方法仍然值钱，因为它是<strong>{@link #strictlyLowerThan} 那句规则的领域侧表达</strong>， 而那两处必须逐对一致（等价用例钉住）。
   *
   * <p>语义三条，缺一条都不算做到：
   *
   * <ol>
   *   <li><strong>只升</strong>：{@code refsMax} 高于现值 ⇒ 抬到 {@code refsMax}；
   *   <li><strong>不降</strong>：{@code refsMax} 低于现值 ⇒ <strong>保持原值不动</strong>（这是本方法存在的全部理由）；
   *   <li><strong>相等幂等</strong>：{@code refsMax} 等于现值 ⇒ 也是"不动"（重复引用同一份机密不会把它抬成别的什么）。
   * </ol>
   *
   * <p>★ <strong>为什么"不降"要在这里挡住而不是靠调用方</strong>：{@code 13 §2.2} 的口径是
   * "一旦引用过机密，整个会话就是机密的"。若允许写侧传什么就落什么，那么"本轮只引用了公开 chunk" 这一个<strong>完全正常</strong>的输入
   * 就会把会话<strong>静默降回公开</strong> —— 而它不会报错、不会留下任何痕迹， 症状要到"下一轮回答按低密处理"时才显形。
   * 更糟的是：会话密级是<strong>后续每一轮</strong>的输入，一次降密会污染它之后的一切。
   *
   * <p>★ 关于 {@code refsMax} 为 {@code null}：那是"本轮一个引用都没有"（检索无命中 / 拒答 / 用户消息）， 语义是<strong>不变</strong>
   * —— 空集合的 max 是单位元，不是 0。这里<strong>不抛异常</strong>：无引用是正常路径， 对它报错只会逼调用方在每一处判断一次，而那正是"口径散到各读点"的开始。
   * 与之相对，显式的 {@code IllegalArgumentException} 见 {@link #rename}（那是输入非法，不是状态没变）。
   *
   * @param refsMax 本轮回答引用的 chunk 里最高的那一档（{@code null} = 本轮没有引用）
   * @return 密级<strong>是否真的被抬升了</strong> —— ★ 返回布尔而不是 {@code void}：写入方要据此决定 "这一行值不值得写 /
   *     要不要发一条密级抬升的领域事件"。让调用方自己去比较"写前写后"， 等于把同一个口径再抄一遍（抄错的那一次就是上面说的静默降密）。
   */
  public boolean raiseClassification(ClassificationLevel refsMax) {
    if (refsMax == null) {
      // 本轮没有任何引用 ⇒ 空集合的 max 是单位元，会话密级不变
      return false;
    }
    if (this.maxClassification != null
        && !strictlyLowerThan(refsMax).contains(this.maxClassification)) {
      // ★ 只升不降 + 相等幂等：现值不在"严格低于本轮 max"那一档里 ⇒ 一律不动（把"降密"这条路径直接封死）
      return false;
    }
    this.maxClassification = refsMax;
    return true;
  }

  /**
   * ★★ <strong>"这一次抬升会不会发生"的<em>唯一</em>规则</strong>：现值在这个集合里 ⟺ 抬升发生。
   *
   * <p>它有两处消费者，而这两处<strong>必须逐对一致</strong>：
   *
   * <ol>
   *   <li>内存里的 {@link #raiseClassification}（上面那个 {@code contains} 就是它）；
   *   <li>库侧那条单调 UPDATE 的 {@code where}（{@link ConversationRepository#raiseClassification}
   *       的实现把同一个集合当 {@code in (…)} 的参数传下去）。
   * </ol>
   *
   * <p>★★ <strong>为什么收成一个方法而不是各写一遍</strong>：{@code #108} 的择一 (a)（库侧单调）把落库那一步 <strong>挪到了 SQL
   * 里</strong>，代价正是"口径要写第二遍"。把它压成"<strong>一个集合</strong>（次序仍只由 {@link ClassificationLevel} 的声明顺序给出）+
   * 成员判定"之后，SQL 里<strong>不出现任何密级次序字面量</strong>—— 两处不一致只剩下一种可能（有人改了这个方法却没改另一处），而那种可能被等价用例逐对抓红。
   *
   * <p>★ 返回值口径：{@code refsMax} 为 {@code null}（本轮没有引用）⇒ <strong>空集合</strong> ⇒ 任何现值都不在里面 ⇒ 抬升不发生。这与
   * {@link #raiseClassification} 对 {@code null} 的处理同源（空集合的 max 是单位元）。
   *
   * @param refsMax 本轮回答引用的 chunk 里最高的那一档（可空）
   * @return 严格低于 {@code refsMax} 的那几档（按声明顺序；{@code refsMax} 为 {@code PUBLIC} 或 {@code null} 时为空）
   */
  public static List<ClassificationLevel> strictlyLowerThan(ClassificationLevel refsMax) {
    if (refsMax == null) {
      return List.of();
    }
    return Arrays.stream(ClassificationLevel.values())
        .filter(level -> level.ordinal() < refsMax.ordinal())
        .toList();
  }

  /**
   * 重命名（{@code 02 §2.6}：默认取首个问题前若干字，可重命名）。
   *
   * <p>★ 长度上限与库层的 {@code varchar(256)} 同值：应用层给<strong>可读原因</strong>（"标题过长"），库层保证"任何写入路径都逃不掉"。
   * 两处都要有，而不是"库里反正会拦"。
   */
  public void rename(String newTitle) {
    String trimmed = newTitle == null ? "" : newTitle.trim();
    if (trimmed.length() > MAX_TITLE_LENGTH) {
      throw new IllegalArgumentException("会话标题不能超过 " + MAX_TITLE_LENGTH + " 字");
    }
    this.title = trimmed;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public String title() {
    return title;
  }

  public int messageCount() {
    return messageCount;
  }

  public Instant lastMessageAt() {
    return lastMessageAt;
  }

  /**
   * ★★ 会话累计密级。★ 只读 —— 改它只有两条路，而它们是同一句规则的两侧：内存里的 {@link #raiseClassification} 与库侧的 {@link
   * ConversationRepository#raiseClassification}（{@code #108}）。
   */
  public ClassificationLevel maxClassification() {
    return maxClassification;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }
}
