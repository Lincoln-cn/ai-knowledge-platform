package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆的"做梦"任务（{@code 0.9.0 · #296}，五个动作见 {@code 15-长期记忆设计.md §7}）。
 *
 * <h3>★ 为什么叫做梦</h3>
 *
 * <p>它做的是同一件事：<strong>离线读一堆杂乱状态，写回更干净的记忆</strong>。和人类做梦一样， 这个过程不产生"新经历"，只是整理已有经历 —— 所以它<strong>只改
 * {@code cv_memory}，绝不改 {@code cv_message}</strong>。 否则一周后没人能回答"它当初凭什么这么记"。
 *
 * <h3>★ 五个动作里，本版真做三个</h3>
 *
 * <table>
 *   <tr><th>动作</th><th>本版</th><th>说明</th></tr>
 *   <tr><td>去重合并</td><td>✅</td><td>同 owner+level+kind 且内容完全相同 ⇒ 留重要度最高的一条，其余标 {@code superseded_by}</td></tr>
 *   <tr><td>遗忘</td><td>✅</td><td>{@code importance ≤ 2} 且<strong>零命中</strong>且超期 ⇒ 软删</td></tr>
 *   <tr><td>重算画像</td><td>✅</td><td>按人重跑 {@link MemoryService#recomputeProfile}</td></tr>
 *   <tr><td>冲突消解</td><td>⏸ 跳过</td><td>需要模型判断"两条记忆哪条还成立"；本版只在 note 里记"跳过"，不假装做了</td></tr>
 *   <tr><td>提炼</td><td>⏸ 跳过</td><td>同上：多条具体 ⇒ 一条抽象，产出质量要人工看，桩模型下无法验证</td></tr>
 * </table>
 *
 * <p>★ 三个"真做的"都有一个共同点：<strong>判据是确定的</strong>（内容相同 / 零命中 / 有条数）， 不需要模型判断对错。这正好是"能自动跑的部分先自动跑"的边界。
 *
 * <h3>★ 调度时刻跟着库里的 {@code dream_cron}，由 {@link MemoryDreamScheduler} 驱动</h3>
 *
 * <p>这个类<strong>不自己挂 {@code @Scheduled}</strong>：{@code @Scheduled(cron = …)} 在注册 bean 时求值，
 * 运行期改库不会改调度（见 {@link MemoryDreamScheduler} 的类注释）。注册与重排都在调度器里，本类只回答 "跑一轮做什么" ——
 * 这样"什么时候跑"和"跑什么"可以各自被验证。
 */
@Component
public class MemoryDreamJob {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryDreamJob.class);

  /** 一次遗忘最多处理几条（防一次清光）。 */
  private static final int FORGET_LIMIT = 500;

  /** 一次重算最多处理几个人。 */
  private static final int OWNER_LIMIT = 500;

  /** 一次去重最多看几条候选。 */
  private static final int MERGE_LIMIT = 200;

  private final MemoryRepository memories;
  private final MemoryService memoryService;
  private final MemorySettingsStore settings;

  public MemoryDreamJob(
      MemoryRepository memories, MemoryService memoryService, MemorySettingsStore settings) {
    this.memories = memories;
    this.memoryService = memoryService;
    this.settings = settings;
  }

  /** 跑一轮（由 {@link MemoryDreamScheduler} 在库里的 {@code dream_cron} 时刻调用）。 */
  public void dream() {
    runOnce(Instant.now());
  }

  /**
   * 跑一轮（可测入口）。
   *
   * @param now 现在
   * @return 本轮结果
   */
  public DreamResult runOnce(Instant now) {
    MemorySettingsView current = settings.load();
    if (!current.enabled()) {
      settings.recordDream(now, "记忆总开关是关的，本轮跳过");
      return new DreamResult(0, 0, 0, List.of(), "记忆总开关是关的");
    }
    List<String> notes = new ArrayList<>();
    int merged = merge(notes);
    int forgotten = forget(current, now, notes);
    int profiled = recomputeProfiles(notes);
    notes.add("冲突消解与提炼：本轮跳过（需要模型判断，判据不确定）");
    String note =
        "去重 " + merged + " 条 · 遗忘 " + forgotten + " 条 · 重算画像 " + profiled + " 人（冲突消解与提炼跳过）";
    settings.recordDream(now, note);
    LOG.info("记忆做梦完成：{}", String.join("；", notes));
    return new DreamResult(merged, forgotten, profiled, notes, note);
  }

  /**
   * 去重合并：同 owner + level + kind 且<strong>内容完全相同</strong>的，留重要度最高的一条。
   *
   * @param notes 过程记录
   * @return 被合并掉的条数
   */
  private int merge(List<String> notes) {
    Map<String, Memory> keep = new HashMap<>();
    int merged = 0;
    for (MemoryLevel level : MemoryLevel.values()) {
      if (level.conversationScoped()) {
        /* 会话层去重要带上 scopeId，本版不处理：会话记忆本来就少，且删错代价高。 */
        continue;
      }
      for (MemoryKind kind : MemoryKind.values()) {
        List<Memory> candidates = new ArrayList<>();
        for (UUID owner : memories.findOwnersWithLevel(level, OWNER_LIMIT)) {
          candidates.addAll(memories.findMergeCandidates(owner, level, kind, MERGE_LIMIT));
        }
        keep.clear();
        for (Memory memory : candidates) {
          String key =
              memory.ownerId()
                  + "|"
                  + memory.level()
                  + "|"
                  + memory.kind()
                  + "|"
                  + memory.content();
          Memory existing = keep.get(key);
          if (existing == null) {
            keep.put(key, memory);
            continue;
          }
          Memory keepIt = existing.importance() >= memory.importance() ? existing : memory;
          Memory dropIt = keepIt == existing ? memory : existing;
          dropIt.supersedeBy(keepIt.id(), Instant.now());
          memories.save(dropIt);
          merged++;
          notes.add("合并：" + dropIt.id() + " 被 " + keepIt.id() + " 取代");
        }
      }
    }
    return merged;
  }

  /**
   * 遗忘：重要度低 + 零命中 + 超期 ⇒ 软删。
   *
   * @param current 设定
   * @param now 现在
   * @param notes 过程记录
   * @return 被遗忘条数
   */
  private int forget(MemorySettingsView current, Instant now, List<String> notes) {
    Instant before = now.minus(current.forgetAfterDays(), ChronoUnit.DAYS);
    List<Memory> stale = memories.findForgettable(before, 2, FORGET_LIMIT);
    int count = 0;
    for (Memory memory : stale) {
      memories.softDelete(memory.id(), now);
      count++;
    }
    if (count > 0) {
      notes.add("遗忘 " + count + " 条（重要度 ≤2 且 " + current.forgetAfterDays() + " 天零命中）");
    }
    return count;
  }

  /**
   * 重算画像：给每个有 PERSONA 记忆的人重跑一次。
   *
   * @param notes 过程记录
   * @return 处理人数
   */
  private int recomputeProfiles(List<String> notes) {
    List<UUID> owners = memories.findOwnersWithLevel(MemoryLevel.PERSONA, OWNER_LIMIT);
    for (UUID owner : owners) {
      memoryService.recomputeProfile(owner);
    }
    if (!owners.isEmpty()) {
      notes.add("重算画像 " + owners.size() + " 人");
    }
    return owners.size();
  }

  /**
   * 一轮做梦的结果。
   *
   * @param merged 合并掉的条数
   * @param forgotten 软删的条数
   * @param profiled 重算画像的人数
   * @param notes 过程记录（每一句都能在日志里对上）
   * @param note 一句话摘要（落到 {@code cv_memory_setting.last_dream_note}）
   */
  public record DreamResult(
      int merged, int forgotten, int profiled, List<String> notes, String note) {}
}
