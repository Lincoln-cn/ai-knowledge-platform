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

/**
 * 一条长期记忆（{@code #296}，迁移 {@code V101}）。
 *
 * <h3>★ 记忆不是消息的副本</h3>
 *
 * <p>它存的是<strong>提炼过的结论</strong>，不是原文片段。所以：
 *
 * <ul>
 *   <li>{@link #create} 把 {@code content} 截到 {@link #MAX_CONTENT_LENGTH} 字 —— 抄整段对话会让记忆变成第二份 {@code
 *       cv_message}，还多一份要维护的一致性；
 *   <li>它<strong>可被合并、可被取代、可被软删</strong>，而 {@code cv_message} 永不改写（{@code 15 §一}）。
 * </ul>
 *
 * <h3>★ 向量列不映射到实体</h3>
 *
 * <p>{@code embedding vector(384)} 没有 JPA 标准类型。这里<strong>故意不映射</strong>：写入与召回时的相似度排序由 {@code
 * JpaMemoryRepository} 用 JDBC 完成，实体只承载"这是个什么东西"的语义。 ★ 判据：实体只表达领域状态；向量是<strong>索引物</strong>，与 {@code
 * embedding_status} 同性质，不该进聚合。
 */
@Entity
@Table(name = "cv_memory")
public class Memory {

  /** 一条记忆的字数上限（超出截断）。 */
  public static final int MAX_CONTENT_LENGTH = 2000;

  /** 重要度的取值范围（与迁移里的 {@code chk_cv_memory_importance} 一致）。 */
  public static final int MIN_IMPORTANCE = 1;

  /** 重要度上限。 */
  public static final int MAX_IMPORTANCE = 5;

  @Id private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "level", nullable = false, length = 16)
  private MemoryLevel level;

  /** ★ 只有 {@link MemoryLevel#CONVERSATION} 层有值（迁移里的 {@code chk_cv_memory_scope} 守着）。 */
  @Column(name = "scope_id")
  private UUID scopeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private MemoryKind kind;

  @Column(name = "content", nullable = false)
  private String content;

  /** 从哪几条消息抽出来的（可回溯：记忆错了要能查回原文）。 */
  @Column(name = "source_message_ids")
  private UUID[] sourceMessageIds;

  @Column(name = "importance", nullable = false)
  private short importance;

  @Column(name = "confidence", nullable = false)
  private float confidence;

  /** 去重键：由代码生成，模型不参与（模型给的键会因措辞差异永远撞不上）。 */
  @Column(name = "dedup_key", length = 128)
  private String dedupKey;

  @Column(name = "hit_count", nullable = false)
  private int hitCount;

  @Column(name = "last_hit_at")
  private Instant lastHitAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  /** ★ 被哪条取代（不物理删：冲突消解要留痕迹）。 */
  @Column(name = "superseded_by")
  private UUID supersededBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 用。 */
  protected Memory() {}

  /**
   * 造一条新记忆。
   *
   * @param ownerId 归属用户（只有本人可见）
   * @param level 层级
   * @param scopeId 会话 id（仅 {@link MemoryLevel#CONVERSATION} 层，其余必须为 {@code null}）
   * @param kind 种类
   * @param content 提炼后的结论（超长截断）
   * @param sourceMessageIds 来源消息 id（可为空数组）
   * @param importance 重要度 1–5
   * @param confidence 置信度 0–1
   * @param dedupKey 去重键（可为 {@code null} = 不参与去重）
   * @param now 当前时间
   * @return 新记忆（未持久化）
   * @throws IllegalArgumentException 层与 {@code scopeId} 不一致、或重要度越界
   */
  public static Memory create(
      UUID ownerId,
      MemoryLevel level,
      UUID scopeId,
      MemoryKind kind,
      String content,
      UUID[] sourceMessageIds,
      int importance,
      float confidence,
      String dedupKey,
      Instant now) {
    if (ownerId == null) {
      throw new IllegalArgumentException("记忆必须归属某个用户");
    }
    if (level == null || kind == null) {
      throw new IllegalArgumentException("记忆必须有层级与种类");
    }
    if (level.conversationScoped() != (scopeId != null)) {
      throw new IllegalArgumentException(
          "层级与 scope 不匹配：" + level.code() + (scopeId == null ? " 必须带会话 id" : " 不该带会话 id"));
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    if (importance < MIN_IMPORTANCE || importance > MAX_IMPORTANCE) {
      throw new IllegalArgumentException(
          "重要度要在 " + MIN_IMPORTANCE + "～" + MAX_IMPORTANCE + " 之间，现在写的是 " + importance);
    }
    if (confidence < 0f || confidence > 1f) {
      throw new IllegalArgumentException("置信度要在 0～1 之间，现在写的是 " + confidence);
    }
    Memory memory = new Memory();
    memory.id = UUID.randomUUID();
    memory.ownerId = ownerId;
    memory.level = level;
    memory.scopeId = scopeId;
    memory.kind = kind;
    memory.content = truncate(content.trim());
    memory.sourceMessageIds = sourceMessageIds == null ? new UUID[0] : sourceMessageIds.clone();
    memory.importance = (short) importance;
    memory.confidence = confidence;
    memory.dedupKey = dedupKey == null || dedupKey.isBlank() ? null : dedupKey.trim();
    memory.hitCount = 0;
    memory.createdAt = now;
    memory.updatedAt = now;
    return memory;
  }

  /**
   * 截到上限。
   *
   * <p>★ 截断而不是拒绝：抽取器的输出长度不可控，因为长度丢掉一条有用的事实不值得；而"悄悄存下 8000 字"会让一次召回把预算吃光。
   *
   * @param text 原内容
   * @return 截断后的内容
   */
  private static String truncate(String text) {
    return text.length() <= MAX_CONTENT_LENGTH ? text : text.substring(0, MAX_CONTENT_LENGTH) + "…";
  }

  /**
   * 记一次命中（召回时调用）。
   *
   * <p>★ 它是"做梦遗忘"的唯一依据：<strong>从没被用过</strong>的记忆才是噪音。
   *
   * @param now 当前时间
   */
  public void touchHit(Instant now) {
    this.hitCount = this.hitCount + 1;
    this.lastHitAt = now;
    this.updatedAt = now;
  }

  /**
   * 标记被另一条取代（冲突消解）。
   *
   * @param replacementId 取代者的 id
   * @param now 当前时间
   */
  public void supersedeBy(UUID replacementId, Instant now) {
    if (replacementId == null) {
      throw new IllegalArgumentException("取代者不能为空");
    }
    if (replacementId.equals(this.id)) {
      throw new IllegalArgumentException("不能自己取代自己");
    }
    this.supersededBy = replacementId;
    this.updatedAt = now;
  }

  /**
   * 设置过期时间（会话级记忆用）。
   *
   * @param expiresAt 到期时间
   * @param now 当前时间
   */
  public void expireAt(Instant expiresAt, Instant now) {
    this.expiresAt = expiresAt;
    this.updatedAt = now;
  }

  /**
   * 软删。
   *
   * <p>★ 不物理删：用户删掉的记忆仍要能被"为什么它当时影响了回答"这件事查到。
   *
   * @param now 当前时间
   */
  public void softDelete(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }

  /**
   * 用户手动编辑内容（页面上的"改一条"）。
   *
   * @param newContent 新内容
   * @param newImportance 新重要度
   * @param now 当前时间
   */
  public void edit(String newContent, int newImportance, Instant now) {
    if (newContent == null || newContent.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    if (newImportance < MIN_IMPORTANCE || newImportance > MAX_IMPORTANCE) {
      throw new IllegalArgumentException(
          "重要度要在 " + MIN_IMPORTANCE + "～" + MAX_IMPORTANCE + " 之间，现在写的是 " + newImportance);
    }
    this.content = truncate(newContent.trim());
    this.importance = (short) newImportance;
    this.updatedAt = now;
  }

  /**
   * @return 是否还能被召回（未删、未被取代）
   */
  public boolean recalleable() {
    return deletedAt == null && supersededBy == null;
  }

  /**
   * @return id
   */
  public UUID id() {
    return id;
  }

  /**
   * @return 归属用户
   */
  public UUID ownerId() {
    return ownerId;
  }

  /**
   * @return 层级
   */
  public MemoryLevel level() {
    return level;
  }

  /**
   * @return 会话 id（仅会话层）
   */
  public UUID scopeId() {
    return scopeId;
  }

  /**
   * @return 种类
   */
  public MemoryKind kind() {
    return kind;
  }

  /**
   * @return 内容
   */
  public String content() {
    return content;
  }

  /**
   * @return 来源消息 id
   */
  public UUID[] sourceMessageIds() {
    return sourceMessageIds == null ? new UUID[0] : sourceMessageIds.clone();
  }

  /**
   * @return 重要度
   */
  public int importance() {
    return importance;
  }

  /**
   * @return 置信度
   */
  public float confidence() {
    return confidence;
  }

  /**
   * @return 去重键
   */
  public String dedupKey() {
    return dedupKey;
  }

  /**
   * @return 命中次数
   */
  public int hitCount() {
    return hitCount;
  }

  /**
   * @return 最后命中时间
   */
  public Instant lastHitAt() {
    return lastHitAt;
  }

  /**
   * @return 到期时间
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * @return 取代者 id
   */
  public UUID supersededBy() {
    return supersededBy;
  }

  /**
   * @return 删除时间
   */
  public Instant deletedAt() {
    return deletedAt;
  }

  /**
   * @return 创建时间
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * @return 更新时间
   */
  public Instant updatedAt() {
    return updatedAt;
  }

  /**
   * 来源消息的可读列表（页面展示用）。
   *
   * @return 消息 id 列表
   */
  public List<UUID> sourceMessageList() {
    return List.of(sourceMessageIds());
  }
}
