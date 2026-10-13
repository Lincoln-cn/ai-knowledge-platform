package cn.lincoln.aiplatform.conversation.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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
 * <h3>★ 本批的边界（读这个类时请连着读）</h3>
 *
 * <ul>
 *   <li><strong>做</strong>：数据层（{@code V32} 的表 + 本实体 + {@link
 *       ConversationRepository}）与<strong>领域不变量</strong>。
 *   <li><strong>不做</strong>：服务层编排（创建会话 / 追加消息 / 落引用集合那一整条链）与 HTTP 端点 —— 它们的载体是第 17 周的 SSE
 *       问答链路（{@code 13 §8.3} 的 M4b 行）。{@link #raiseClassification} 今天<strong>还没有生产调用方</strong>，
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
   * <p>★ 本批<strong>没有任何地方写它</strong> —— 消息的写入路径在第 17 周。列先占住位置，写法口径（谁在何时 +1、怎么对账）见 Issue {@code
   * #44}。
   */
  @Column(name = "message_count", nullable = false)
  private int messageCount;

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
    if (this.maxClassification != null && refsMax.ordinal() <= this.maxClassification.ordinal()) {
      // ★ 只升不降 + 相等幂等：低于或等于现值一律不动（把"降密"这条路径在领域层直接封死）
      return false;
    }
    this.maxClassification = refsMax;
    return true;
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

  /** ★★ 会话累计密级。★ 只读 —— 改它只有 {@link #raiseClassification} 一条路。 */
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
