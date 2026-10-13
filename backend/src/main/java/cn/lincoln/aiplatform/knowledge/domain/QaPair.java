package cn.lincoln.aiplatform.knowledge.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 问答对（Issue {@code #78} 数据层留缝 · 第 16 周）。
 *
 * <h3>★★ 它是「留缝」，不是「已接入」—— 读这个类时请连着读这一段</h3>
 *
 * <p>本类落地的是<strong>数据层 + 一个最小的服务层入口</strong>（导入 / 列出），<strong>检索侧一行不接</strong>。
 * 这不是"还没写完"，而是<strong>这一批刻意不做</strong>：{@code 10 §7.4} 把问答对定为「B1 不达标时的
 * <strong>补救手段</strong>」（策略层唯一的确定性召回手段，但它是"结果指标不够时回机制二"的那一步， 不是前置条件），而它的数据来源（第 17 周的会话消息、第 18
 * 周的赞踩）现在还不存在。
 *
 * <p>★ 现在动的唯一理由是 {@code 04:235} 那句「晚做要<strong>补迁移</strong>」： 错过的是「表结构与文档 /
 * 分段同批迁移的便利」，<strong>不是数据</strong>。所以：
 *
 * <ul>
 *   <li>凡是"将来接入时要用的东西" ⇒ 落<strong>字段 / 枚举</strong>（{@link #classification} · {@link #origin} ·
 *       {@link #hitCount} · {@link #segmentId}）；
 *   <li>凡是"实现" ⇒ <strong>一律不做</strong>（没有 {@code searchText}、没有向量列、没有全文索引列、 没有任何检索入口读到这张表）。
 * </ul>
 *
 * <h3>★ 归属 = {@link #kbId}（不是 owner_id / visibility）</h3>
 *
 * <p>问答对是<strong>知识库里的一段材料</strong>：谁能看它由所属知识库的共享决定 —— 与 {@link Document} / {@link Segment}
 * 是<strong>同一个表达</strong>（{@code 07 §11.2}：责任部门「这是<strong>归属</strong>，不是可见性」；
 * 文档能不能被看见由所属知识库的共享决定）。文档与分段都<strong>不是</strong> {@code OwnedResource}，问答对也一样。
 *
 * <p>★ 为什么不放 {@code ownerId} / {@code visibility}：那是「<strong>资源本身</strong>能不能被看见」的载体，属于 {@code
 * kb_knowledge_base} 那一层。问答对不单独分享、不单独转让 —— 放一份进去等于允许「问答对的可见性与它的知识库
 * 不一致」这个状态存在，而那种状态<strong>没有人能说清该怎么判</strong>（与 {@code V30} 不放 {@code owner_id} 同一条理由）。
 *
 * <h3>★★ 密级为什么是冗余列（{@link #classification}）</h3>
 *
 * <p>{@code 13 §6.3} 第 3 点 / {@code §6.4} 把「分段密级是冗余列，且<strong>必须能在 segment 表上直接生效</strong>」
 * 定为最贵的两处耦合点之一：后加 ⇒ 回填全部历史行并全量巡检一致性。问答对将来要进检索（第 3 路）， 所以它<strong>现在</strong>就要带这一列 ——
 * 否则将来要么回填，要么让它成为"三条检索路里唯一没有密级过滤的那一条"。
 *
 * <p>★ {@code nullable = false} 且<b>没有默认值</b>：漏传是插入时的一次报错，而不是"静默落一个 {@code PUBLIC}" ——
 * 后者是一条不会报警的越权。
 *
 * <h3>★ {@link #hitCount} / {@link #lastHitAt} 本批恒为 0 / null</h3>
 *
 * <p>{@code 10 §7.4} 要的是「{@code (question, segmentId)} 关联 + <strong>命中计数</strong>」，而计数只能由检索写入 ——
 * 本批检索侧不接，所以列先占住位置，免得将来加列回填。
 */
@Entity
@Table(name = "kb_qa_pair")
public class QaPair {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 归属：所属知识库。★ 可见性继承它（与 {@link Document} / {@link Segment} 同一个表达）。 */
  @Column(name = "kb_id", nullable = false)
  private UUID kbId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "question", nullable = false)
  private String question;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "answer", nullable = false)
  private String answer;

  /**
   * 命中来源：这条问答对指向哪一段原文。
   *
   * <p>★ 可空是<strong>语义的一部分</strong>：人工导入的问答对可以没有出处，而"凑一段假的分段 id"比空更糟 —— 引用展示会指到一篇不相关的文档上，且不报错。
   *
   * <p>★ 有值时由用例层校验「这一段真的属于 {@link #kbId}」：归属错位的问答对将来会<strong>静默</strong>污染检索 （拿 A 库的问题去 B 库的语料里作答）。
   */
  @Column(name = "segment_id")
  private UUID segmentId;

  /** 密级。★ 冗余列 + 谓词，见类注释。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "classification", nullable = false, length = 16)
  private ClassificationLevel classification;

  /**
   * 密级排序（{@code 0 = PUBLIC … 3 = CONFIDENTIAL}）。
   *
   * <p>★ 库层是 {@code generated always as (…) stored}（{@code V31}，与 {@code V21} / {@code V22} 逐字同一个
   * {@code CASE}）⇒ 这里 {@code insertable = false, updatable = false}：它由库算，应用绝不写。
   *
   * <p>★ 为什么留一个 Java 字段而不是不管它：这条生成列是<strong>检索谓词要用的那一列</strong> （{@code JdbcSegmentSearchQuery} 里是
   * {@code s.classification_rank <= ?}，绑定值来自 {@code
   * ClassificationLevel.ordinal()}）。把它映射进来，将来写消费方时<strong>不必再想一次"rank 从哪来"</strong> —— 而 {@code
   * ClassificationRankTest} 已经在钉"库里的 rank == Java 的 ordinal"这条口径。
   */
  @Column(name = "classification_rank", insertable = false, updatable = false)
  private Short classificationRank;

  /** 来源（会话沉淀 / 赞踩沉淀 / 人工导入 / 文档沉淀）。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 32)
  private QaPairOrigin origin;

  /**
   * 来源的定位串（会话消息 id / 反馈 id / 导入批次号）。
   *
   * <p>★ 故意<strong>不加外键</strong>：它是"从哪来的"这条线索，不是归属 —— 那些表（第 17–18 周）还不存在， 而且一行问答对的来源可能在别的模块里。
   */
  @Column(name = "source_ref", length = 128)
  private String sourceRef;

  /** 命中计数（{@code 10 §7.4}）。★ 本批恒 0：只有检索能写它。 */
  @Column(name = "hit_count", nullable = false)
  private long hitCount;

  @Column(name = "last_hit_at")
  private Instant lastHitAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /**
   * 软删。
   *
   * <p>★ 列存在<strong>不等于</strong>已经有删除入口：本批用例层只有导入 / 列出两个方法。这里连 {@code markDeleted}
   * 都<strong>不提供</strong> —— 一个没有调用方的领域方法会被下一个人读成"删除功能已经在"（本项目最贵的一类病）。
   */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected QaPair() {}

  /**
   * 导入一条问答对。
   *
   * <p>★ 三项必填在这里再校验一遍（用例层已经拦过）：领域不变量不能只靠调用方自觉 —— 库层的 {@code NOT NULL} 与 {@code check}
   * 是最后一道闸，这里是给人看的那道。
   *
   * @param id 主键（由用例层生成 —— 应用侧的时间有序 UUID，与其它聚合根同口径）
   * @param kbId 归属（所属知识库）★ 必填
   * @param segmentId 命中来源（可空；有值时调用方必须已校验它属于 {@code kbId}）
   * @param classification 密级 ★ 必填，无默认值
   * @param origin 来源 ★ 必填 —— 缺了它，"这条凭什么可信"就没有答案
   * @param sourceRef 来源定位串（可空）
   * @param createdBy 建档人
   */
  public QaPair(
      UUID id,
      UUID kbId,
      String question,
      String answer,
      UUID segmentId,
      ClassificationLevel classification,
      QaPairOrigin origin,
      String sourceRef,
      UUID createdBy) {
    if (id == null || kbId == null) {
      throw new IllegalArgumentException("问答对 id 与所属知识库 id 不能为空");
    }
    if (question == null || question.isBlank()) {
      // ★ 空问题是一条"看着有、其实没用"的行：它参与不了召回，却会被计数、被列出
      throw new IllegalArgumentException("问题必填");
    }
    if (answer == null || answer.isBlank()) {
      throw new IllegalArgumentException("答案必填");
    }
    if (classification == null) {
      // ★ 与 Document / Segment 同口径：密级没有默认值
      throw new IllegalArgumentException("密级必填");
    }
    if (origin == null) {
      // ★ 来源决定"这条凭什么可信、被质疑时怎么追溯"，同样没有默认值
      throw new IllegalArgumentException("来源必填（会话沉淀 / 赞踩沉淀 / 人工导入）");
    }
    this.id = id;
    this.kbId = kbId;
    this.question = question.trim();
    this.answer = answer.trim();
    this.segmentId = segmentId;
    this.classification = classification;
    this.origin = origin;
    this.sourceRef = sourceRef == null || sourceRef.isBlank() ? null : sourceRef.trim();
    // ★ 新导入的问答对还没被任何检索命中过 —— 0 是事实，不是占位
    this.hitCount = 0L;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID kbId() {
    return kbId;
  }

  public String question() {
    return question;
  }

  public String answer() {
    return answer;
  }

  public UUID segmentId() {
    return segmentId;
  }

  public ClassificationLevel classification() {
    return classification;
  }

  public Short classificationRank() {
    return classificationRank;
  }

  public QaPairOrigin origin() {
    return origin;
  }

  public String sourceRef() {
    return sourceRef;
  }

  public long hitCount() {
    return hitCount;
  }

  public Instant lastHitAt() {
    return lastHitAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public UUID updatedBy() {
    return updatedBy;
  }

  public Instant deletedAt() {
    return deletedAt;
  }
}
