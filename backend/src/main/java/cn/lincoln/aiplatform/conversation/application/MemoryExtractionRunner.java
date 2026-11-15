package cn.lincoln.aiplatform.conversation.application;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆抽取与写入的<strong>异步执行器</strong>（{@code 0.9.0 · #296}）。
 *
 * <h3>为什么必须异步</h3>
 *
 * <p>抽取要调一次模型（{@link MemoryExtractor#extract} 里 {@code completeAs(..., Duration.ofSeconds(20))}），
 * 之后最多写三条记忆（{@link MemoryService#write} 各开一个事务）。这一串放在聊天线程上跑，代价不是"用户 多等一会儿"——回答早已通过 {@code
 * awaitTerminal} 落库并推给前端——而是<strong>这条线程被占住</strong>： 一次 20 秒超时的抽取就能把一个请求线程按在那里，并发一上来就是排队。
 *
 * <p>★★ 所以本类只做一件事：把"抽取 + 写入"整段搬到虚拟线程上，调用方拿到一个 {@link CompletableFuture} 立刻返回。
 *
 * <h3>为什么返回 {@code CompletableFuture} 而不是 {@code void}</h3>
 *
 * <p>与 {@code EntityExtractionService.extractAndStoreAsync} 同一理由：测试要能等它跑完再断言，关停时
 * 也要能等它们收尾。<strong>没有返回值就等于"提交了但不知道跑没跑"</strong> —— 这正是本项目反复记账的 silent-failure 形状。
 *
 * <h3>★ 交给异步的必须是不可变快照</h3>
 *
 * <p>{@link Turn} 是 record：{@code question} / {@code answer} 在提交那一刻就拷成字符串，之后主线程
 * 怎么改都不影响。<strong>不要</strong>把 {@code ChatTurnWriter.PreparedTurn} 传进来 —— 那是可变的
 * 会话状态对象，异步读它就是在读别人的中间状态。
 *
 * <h3>★ 抽取失败不许让"这一轮问答"变失败</h3>
 *
 * <p>抽取是旁路：模型抖动、解析失败、库瞬间不可用，正确结局都是"这次没记下来"。所以内部 catch 掉 所有 {@code RuntimeException} 并 WARN，返回值给 0。
 */
@Component
public class MemoryExtractionRunner {

  private static final Logger log = LoggerFactory.getLogger(MemoryExtractionRunner.class);

  /** 关停时等多久收尾；超时后放弃并说清还剩几个（与实体抽取一致）。 */
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  private final MemoryExtractor extractor;
  private final MemoryService memoryService;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger inFlight = new AtomicInteger();

  public MemoryExtractionRunner(MemoryExtractor extractor, MemoryService memoryService) {
    this.extractor = extractor;
    this.memoryService = memoryService;
  }

  /**
   * 提交一轮抽取。
   *
   * @param turn 这一轮问答的快照
   * @return 完成时给出真正写入的记忆条数（失败为 0，不抛）
   */
  public CompletableFuture<Integer> submit(Turn turn) {
    if (turn == null || turn.userId() == null || turn.answer() == null || turn.answer().isBlank()) {
      return CompletableFuture.completedFuture(0);
    }
    inFlight.incrementAndGet();
    return CompletableFuture.supplyAsync(() -> extractAndWrite(turn), executor)
        .whenComplete((ignored, error) -> inFlight.decrementAndGet());
  }

  /** 当前在跑的抽取数（测试与关停日志用）。 */
  public int inFlight() {
    return inFlight.get();
  }

  /**
   * 关停时等正在跑的抽取收尾。
   *
   * <p>★ 不等就退的后果：写到一半的记忆停在事务中间，库里有半条 `source_message_ids` 指不到消息， 而下一次启动不会有任何提示。
   *
   * <p>★ 上限 30 秒而不是无限等：抽取再慢也不该拖住关停；超时就记日志说清还有多少没跑完 —— 「等了一会儿没等到」和「已经全写完」必须能分开。
   */
  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        log.warn("#记忆抽取 关停时仍有 {} 个任务未完成，已放弃等待", inFlight.get());
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
      log.warn("#记忆抽取 关停被中断，未完成的任务已放弃（{} 个）", inFlight.get(), e);
    }
  }

  /** 抽取 + 逐条写入；全段容错。 */
  private int extractAndWrite(Turn turn) {
    try {
      List<MemoryService.MemoryDraft> drafts =
          extractor.extract(
              turn.userId(),
              turn.conversationId(),
              turn.userMessageId(),
              turn.question(),
              turn.answer());
      if (drafts.isEmpty()) {
        return 0;
      }
      int saved = 0;
      for (MemoryService.MemoryDraft draft : drafts) {
        // ★ 单条失败不影响同批其它条目：一条写不进去（比如撞了 dedup_key 的竞态）不该把三条都丢掉
        try {
          if (memoryService.write(draft).isPresent()) {
            saved++;
          }
        } catch (RuntimeException failure) {
          log.warn("#记忆抽取 写入一条失败，跳过它：{}", failure.getMessage());
        }
      }
      log.info("#记忆抽取 本轮抽到 {} 条，写入 {} 条（会话 {}）", drafts.size(), saved, turn.conversationId());
      return saved;
    } catch (RuntimeException failure) {
      log.warn("#记忆抽取 抽取失败，本轮不记记忆（会话 {}）：{}", turn.conversationId(), failure.getMessage());
      return 0;
    }
  }

  /**
   * 一轮问答的快照。
   *
   * @param userId 这一轮属于谁（记忆按 owner 隔离）
   * @param conversationId 会话 id（会话层记忆的 scope）
   * @param userMessageId 用户那条消息的 id（写进 {@code source_message_ids}，用来回答"凭什么这么记"）
   * @param question 用户原话
   * @param answer 模型输出
   */
  public record Turn(
      UUID userId, UUID conversationId, UUID userMessageId, String question, String answer) {}
}
