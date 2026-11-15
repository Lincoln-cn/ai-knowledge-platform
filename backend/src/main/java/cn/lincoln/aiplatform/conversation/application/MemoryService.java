package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import cn.lincoln.aiplatform.conversation.domain.UserProfile;
import cn.lincoln.aiplatform.conversation.infrastructure.JpaUserProfileRepository;
import cn.lincoln.aiplatform.conversation.infrastructure.MemoryRepositoryAdapter;
import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 长期记忆的读写与设置（{@code 0.9.0 · #296}，设计见 {@code docs/系统设计方案/15-长期记忆设计.md}）。
 *
 * <h3>★ 这个类只做四件事</h3>
 *
 * <ol>
 *   <li>把 {@link MemoryDraft} 变成一条 {@link Memory}（含 {@code dedup_key} 原子去重）；
 *   <li>按用户列出 / 修改 / 软删；
 *   <li>读设定、改设定；
 *   <li>重算画像。
 * </ol>
 *
 * <p>★ 它<strong>不做召回</strong>（那是 {@link MemoryRetriever}）、<strong>不调模型</strong>（那是 {@link
 * MemoryExtractor} 与做梦任务）。把这三件事混在一起，就没法单独回答"召回慢了是记忆库太大还是模型慢"。
 */
@Service
public class MemoryService {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryService.class);

  private final MemoryRepository memories;
  private final MemoryRepositoryAdapter embeddings;
  private final JpaUserProfileRepository profiles;
  private final MemorySettingsStore settings;

  public MemoryService(
      MemoryRepository memories,
      MemoryRepositoryAdapter embeddings,
      JpaUserProfileRepository profiles,
      MemorySettingsStore settings) {
    this.memories = memories;
    this.embeddings = embeddings;
    this.profiles = profiles;
    this.settings = settings;
  }

  /**
   * 写入一条记忆（幂等：同一 {@code dedup_key} 只留一条）。
   *
   * <p>★ 总开关关着时<strong>直接返回空</strong>而不是抛：调用方是对话后的异步抽取， 用户关了记忆并不意味着那次对话有问题。
   *
   * @param draft 待写入的内容
   * @return 写入的记忆；已存在同 dedup_key 时返回已有的那条
   */
  @Transactional
  public Optional<Memory> write(MemoryDraft draft) {
    if (!settings.load().enabled()) {
      return Optional.empty();
    }
    Optional<Memory> existing =
        memories.findByDedupKey(draft.ownerId(), draft.level(), draft.kind(), draft.dedupKey());
    if (existing.isPresent()) {
      Memory hit = existing.get();
      hit.touchHit(Instant.now());
      return Optional.of(memories.save(hit));
    }
    Memory memory =
        Memory.create(
            draft.ownerId(),
            draft.level(),
            draft.scopeId(),
            draft.kind(),
            draft.content(),
            draft.sourceMessageIds(),
            draft.importance(),
            draft.confidence(),
            draft.dedupKey(),
            Instant.now());
    return Optional.of(memories.save(memory));
  }

  /**
   * 列出某人的记忆（按层级）。
   *
   * @param ownerId 归属用户
   * @param level 只看某一层；{@code null} 表示全部
   * @param limit 条数上限
   * @return 记忆列表
   */
  @Transactional(readOnly = true)
  public List<Memory> list(UUID ownerId, MemoryLevel level, int limit) {
    MemorySettingsView current = settings.load();
    int capped = Math.max(1, Math.min(limit, current.recallLimit() * 20));
    if (level == null) {
      return memories.findAllForOwner(ownerId, capped);
    }
    /* 指定层级时按该层自己的召回口径取 —— 会话层必须带 scopeId，这里没有会话上下文，跳过。 */
    if (level.conversationScoped()) {
      return List.of();
    }
    return memories.findRecalls(ownerId, level, null, 1, capped);
  }

  /**
   * 手工新增一条记忆（界面上"新增设定"用）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（非会话层须为 {@code null}）
   * @param kind 种类
   * @param content 内容
   * @param importance 重要度
   * @return 新记忆
   */
  @Transactional
  public Memory create(
      UUID ownerId,
      MemoryLevel level,
      UUID scopeId,
      MemoryKind kind,
      String content,
      int importance) {
    Memory memory =
        Memory.create(
            ownerId,
            level,
            scopeId,
            kind,
            content,
            new UUID[0],
            importance,
            1.0f,
            null,
            Instant.now());
    return memories.save(memory);
  }

  /**
   * 改一条记忆的内容与重要度。
   *
   * @param id 记忆 id
   * @param ownerId 归属用户（越权在这里挡掉）
   * @param content 新内容
   * @param importance 新重要度
   * @return 改后的记忆
   */
  @Transactional
  public Memory update(UUID id, UUID ownerId, String content, int importance) {
    Memory memory =
        memories
            .findById(id, ownerId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "这条记忆不存在", "它可能已被删除，或者不属于你"));
    memory.edit(content, importance, Instant.now());
    return memories.save(memory);
  }

  /**
   * 软删一条记忆。
   *
   * <p>★ 软删而不是物理删：{@code source_message_ids} 还指着它当初来自哪几句话，物理删会让"它当初凭什么这么记"永久不可考。
   *
   * @param id 记忆 id
   * @param ownerId 归属用户
   */
  @Transactional
  public void delete(UUID id, UUID ownerId) {
    Memory memory =
        memories
            .findById(id, ownerId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "这条记忆不存在", "它可能已被删除，或者不属于你"));
    memories.softDelete(memory.id(), Instant.now());
  }

  /**
   * 各层条数（界面上的"记忆分布"）。
   *
   * @param ownerId 归属用户
   * @return 四层依次的条数
   */
  @Transactional(readOnly = true)
  public List<LevelCount> counts(UUID ownerId) {
    List<LevelCount> rows = new ArrayList<>();
    for (MemoryLevel level : MemoryLevel.values()) {
      rows.add(new LevelCount(level, memories.countByLevel(ownerId, level)));
    }
    return rows;
  }

  /**
   * 读设定。
   *
   * @return 当前设定
   */
  @Transactional(readOnly = true)
  public MemorySettingsView settingsView() {
    return settings.load();
  }

  /**
   * 改设定。
   *
   * @param view 新设定
   * @param operator 操作人
   * @return 落库后的设定
   */
  @Transactional
  public MemorySettingsView updateSettings(MemorySettingsView view, UUID operator) {
    return settings.save(view, operator);
  }

  /**
   * 取画像（没有就给一个空的，不让调用方自己判 null）。
   *
   * @param userId 用户
   * @return 画像
   */
  @Transactional(readOnly = true)
  public UserProfile profileOf(UUID userId) {
    return profiles.findById(userId).orElseGet(() -> UserProfile.empty(userId, Instant.now()));
  }

  /**
   * 用若干条记忆重算画像。
   *
   * <p>★ 只取 PERSONA 层：其它三层要么是会话级、要么是零散事实，把它们综合成"你是什么样的人"会让画像变成 一次没有依据的概括。
   *
   * @param userId 用户
   * @return 重算后的画像
   */
  @Transactional
  public UserProfile recomputeProfile(UUID userId) {
    List<Memory> persona = memories.findRecalls(userId, MemoryLevel.PERSONA, null, 1, 20);
    UserProfile profile = profileOf(userId);
    if (persona.isEmpty()) {
      profile.recompute(profile.tone(), null, null, null, List.of(), Instant.now());
      return profiles.save(profile);
    }
    String tone = firstOf(persona, MemoryKind.PREFERENCE);
    String summary = summarize(persona);
    profile.recompute(
        tone, null, null, summary, persona.stream().map(Memory::id).toList(), Instant.now());
    return profiles.save(profile);
  }

  /**
   * @param rows 记忆
   * @param kind 种类
   * @return 第一条该种类的内容，没有给 {@code null}
   */
  private static String firstOf(List<Memory> rows, MemoryKind kind) {
    return rows.stream()
        .filter(m -> m.kind() == kind)
        .map(Memory::content)
        .findFirst()
        .orElse(null);
  }

  /**
   * @param rows 记忆
   * @return 前三条拼成的摘要（超出画像是 {@code MAX_SUMMARY_LENGTH} 的活）
   */
  private static String summarize(List<Memory> rows) {
    return rows.stream().limit(3).map(Memory::content).reduce((a, b) -> a + "；" + b).orElse(null);
  }

  /**
   * 一条记忆的写入请求。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（非会话层为 {@code null}）
   * @param kind 种类
   * @param content 内容
   * @param sourceMessageIds 来源消息
   * @param importance 重要度
   * @param confidence 置信度
   * @param dedupKey 去重键（可为 {@code null}）
   */
  public record MemoryDraft(
      UUID ownerId,
      MemoryLevel level,
      UUID scopeId,
      MemoryKind kind,
      String content,
      UUID[] sourceMessageIds,
      int importance,
      float confidence,
      String dedupKey) {

    /** 默认置信度（模型抽出来的事实，不是用户亲口确认的偏好）。 */
    public static final float DEFAULT_CONFIDENCE = 0.7f;

    /**
     * @param ownerId 归属用户
     * @param level 层级
     * @param scopeId 会话 id
     * @param kind 种类
     * @param content 内容
     * @param messageIds 来源消息
     * @return 一条默认置信度的草稿
     */
    public static MemoryDraft of(
        UUID ownerId,
        MemoryLevel level,
        UUID scopeId,
        MemoryKind kind,
        String content,
        UUID[] messageIds) {
      return new MemoryDraft(
          ownerId, level, scopeId, kind, content, messageIds, 3, DEFAULT_CONFIDENCE, null);
    }
  }

  /**
   * 某一层的条数。
   *
   * @param level 层级
   * @param count 条数
   */
  public record LevelCount(MemoryLevel level, long count) {}
}
