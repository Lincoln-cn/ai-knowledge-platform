package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.EvalSide;
import cn.lincoln.aiplatform.assistant.domain.EvalVerdict;
import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.assistant.domain.PromptEvalAnswer;
import cn.lincoln.aiplatform.assistant.domain.PromptEvalItem;
import cn.lincoln.aiplatform.assistant.domain.PromptEvalRun;
import cn.lincoln.aiplatform.assistant.domain.PromptEvalSet;
import cn.lincoln.aiplatform.assistant.domain.PromptEvalStatus;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptEvalAnswerRepository;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptEvalItemRepository;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptEvalRunRepository;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptEvalSetRepository;
import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词评测（ROADMAP §11.63.3 的最小形态：第 1 层机械断言 + 第 2 层人工盲评）。
 *
 * <p>★ 这个服务只做两件事：**把同一组问题用两版角色设定各跑一遍**，以及**记住人怎么评的**。 它不做"哪个更好"的判断 —— 那是 §11.63.2 第 2 层人工评的事，第 3
 * 层模型裁判明确不做。
 *
 * <p>★ 一次评测需要"执行环境"（一个应用）：问题要经过真实检索才有意义，而检索范围、 知识库、密级都由应用决定。所以 {@code startRun} 必须带 {@code appId}
 * —— 这一点 §11.63.3 没写，是实施时补的：提示词本身不绑应用，但**测它必须借一个应用**。
 *
 * <p>★ 评分记在 {@link EvalSide#DRAFT} 那一行，语义是"草稿相对基线"。所以同一道题只有一条判定， 不会出现"两侧各评一次、然后互相比"的荒谬形状。
 */
@Service
public class PromptEvalService {

  private static final Logger log = LoggerFactory.getLogger(PromptEvalService.class);

  /** 题数上限：一次评测 = 2 × 题数 次模型调用，30 题（60 次）已经要跑几分钟。 */
  private static final int MAX_ITEMS = 30;

  private final JpaPromptEvalSetRepository sets;
  private final JpaPromptEvalItemRepository items;
  private final JpaPromptEvalRunRepository runs;
  private final JpaPromptEvalAnswerRepository answers;
  private final PromptCatalogService catalog;
  private final ChatService chat;

  /**
   * 评测线程池：固定 2 条。
   *
   * <p>★ 不复用别的池、也不和请求线程共命运：一次评测要跑几分钟，占着请求线程会让 HTTP 超时； 而共用 {@code model-load} 那种池会把加载模型排在评测后面。
   */
  private final ExecutorService evalExecutor =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "prompt-eval");
            t.setDaemon(true);
            return t;
          });

  public PromptEvalService(
      JpaPromptEvalSetRepository sets,
      JpaPromptEvalItemRepository items,
      JpaPromptEvalRunRepository runs,
      JpaPromptEvalAnswerRepository answers,
      PromptCatalogService catalog,
      ChatService chat) {
    this.sets = sets;
    this.items = items;
    this.runs = runs;
    this.answers = answers;
    this.catalog = catalog;
    this.chat = chat;
  }

  @PreDestroy
  void shutdown() {
    evalExecutor.shutdown();
    try {
      if (!evalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        evalExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      evalExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  // ── 评测集与题目 ───────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<SetView> listSets(UUID promptConfigId) {
    requireConfig(promptConfigId);
    return sets.findByPromptConfigIdOrderByCreatedAtDesc(promptConfigId).stream()
        .map(s -> new SetView(s.id(), s.name(), s.createdAt(), items.countBySetId(s.id())))
        .toList();
  }

  @Transactional
  public UUID createSet(UUID promptConfigId, String name, UUID actorId) {
    requireConfig(promptConfigId);
    String trimmed = name == null ? "" : name.strip();
    if (trimmed.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "评测集要有一个名字", "比如「常见口径 20 题」—— 以后你会有好几套");
    }
    UUID id = UUID.randomUUID();
    sets.save(new PromptEvalSet(id, promptConfigId, trimmed, Instant.now(), actorId));
    return id;
  }

  /**
   * 删评测集。
   *
   * <p>★ 级联到题目、运行、答案（迁移里的 {@code on delete cascade}）：判据 §11.63.5 第 5 条 —— 不留孤儿。
   */
  @Transactional
  public void deleteSet(UUID setId) {
    sets.findById(setId).orElseThrow(() -> notFound("评测集"));
    sets.deleteById(setId);
  }

  @Transactional(readOnly = true)
  public List<ItemView> listItems(UUID setId) {
    sets.findById(setId).orElseThrow(() -> notFound("评测集"));
    return items.findBySetIdOrderByOrdinalAsc(setId).stream()
        .map(i -> new ItemView(i.id(), i.ordinal(), i.question()))
        .toList();
  }

  @Transactional
  public ItemView addItem(UUID setId, String question) {
    sets.findById(setId).orElseThrow(() -> notFound("评测集"));
    String trimmed = question == null ? "" : question.strip();
    if (trimmed.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目不能为空", "写一句你会真的拿它去问的话");
    }
    int existing = items.countBySetId(setId);
    if (existing >= MAX_ITEMS) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "一个评测集最多 " + MAX_ITEMS + " 题", "一次评测要跑 2 × 题数 次模型调用，再多就是分批的事了");
    }
    PromptEvalItem item = new PromptEvalItem(UUID.randomUUID(), setId, existing + 1, trimmed);
    items.save(item);
    return new ItemView(item.id(), item.ordinal(), item.question());
  }

  @Transactional
  public void deleteItem(UUID itemId) {
    items.findById(itemId).orElseThrow(() -> notFound("题目"));
    items.deleteById(itemId);
  }

  // ── 运行 ──────────────────────────────────────────────────────

  /**
   * 起一次评测：立刻建 RUNNING 行返回 id，真正跑在后台线程上。
   *
   * <p>★ 为什么不同步跑：30 题 = 60 次模型调用，几分钟。同步会让这个 HTTP 请求超时， 用户还会以为"点了没反应"。
   *
   * @param draftText 输入框里那版正文（整段落库 —— 它可能永远不会被保存）
   */
  @Transactional
  public UUID startRun(UUID setId, UUID appId, String draftText, UUID actorId) {
    PromptEvalSet set = sets.findById(setId).orElseThrow(() -> notFound("评测集"));
    PromptConfig config = requireConfig(set.promptConfigId());
    if (appId == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "评测要选一个应用作为执行环境", "问题要经过真实检索才有意义 —— 检索范围由应用决定");
    }
    String draft = draftText == null ? "" : draftText.strip();
    if (draft.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿正文是空的", "评测比的是「你现在这版」与「已保存那版」");
    }
    if (draft.equals(config.callWord().strip())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "草稿与已保存的逐字相同，没有可比的东西", "先改点什么再测 —— 否则两侧答案只会一字不差");
    }
    List<PromptEvalItem> questions = items.findBySetIdOrderByOrdinalAsc(setId);
    if (questions.isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "这个评测集还没有题目", "先加几道题（最多 " + MAX_ITEMS + " 道）");
    }
    boolean alreadyRunning =
        runs.findBySetIdOrderByStartedAtDesc(setId).stream()
            .anyMatch(r -> r.status() == PromptEvalStatus.RUNNING);
    if (alreadyRunning) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "这套评测集上一次还在跑", "等它跑完或看它的结果 —— 同时跑两次会互相盖住进度");
    }

    PromptEvalRun run =
        new PromptEvalRun(
            UUID.randomUUID(), setId, null, draft, questions.size(), Instant.now(), actorId);
    runs.save(run);
    UUID runId = run.id();
    evalExecutor.submit(() -> runInBackground(runId, appId, questions, draft));
    return runId;
  }

  /**
   * 后台跑完一次评测。
   *
   * <p>★ 这个方法**不是**事务方法：它跑几分钟，占着事务会一直握着连接。 每一步各自落库（{@code save}），失败时把 {@code status} 改成 FAILED
   * 并留一句人话。
   */
  void runInBackground(UUID runId, UUID appId, List<PromptEvalItem> questions, String draft) {
    int done = 0;
    try {
      for (PromptEvalItem item : questions) {
        ChatService.DebugComparison cmp;
        try {
          cmp = chat.debugCompare(appId, item.question(), draft);
        } catch (RuntimeException e) {
          log.info("评测 {} 第 {} 题失败：{}", runId, item.ordinal(), e.toString());
          throw new BusinessException(
              ErrorCode.BAD_REQUEST,
              "第 " + item.ordinal() + " 题跑失败：" + readable(e),
              "检查应用绑定的模型与知识库是否可用");
        }
        Instant now = Instant.now();
        saveAnswer(runId, item.id(), EvalSide.SAVED, cmp.saved());
        saveAnswer(runId, item.id(), EvalSide.DRAFT, cmp.draftRan() ? cmp.draft() : cmp.saved());
        done++;
        advance(runId, done);
      }
      finish(runId);
    } catch (RuntimeException e) {
      fail(runId, readable(e));
    }
  }

  private void saveAnswer(UUID runId, UUID itemId, EvalSide side, ChatService.DebugAnswer answer) {
    PromptEvalAnswer row = new PromptEvalAnswer(UUID.randomUUID(), runId, itemId, side);
    if (answer != null) {
      row.recordAnswer(answer.answer(), (int) Math.min(answer.elapsedMs(), Integer.MAX_VALUE));
    }
    answers.save(row);
  }

  @Transactional
  void advance(UUID runId, int done) {
    runs.findById(runId)
        .ifPresent(
            r -> {
              r.advance(done);
              runs.save(r);
            });
  }

  @Transactional
  void finish(UUID runId) {
    runs.findById(runId)
        .ifPresent(
            r -> {
              r.finish(Instant.now());
              runs.save(r);
            });
  }

  @Transactional
  void fail(UUID runId, String reason) {
    runs.findById(runId)
        .ifPresent(
            r -> {
              r.fail(reason, Instant.now());
              runs.save(r);
            });
  }

  @Transactional(readOnly = true)
  public RunView getRun(UUID runId) {
    PromptEvalRun run = runs.findById(runId).orElseThrow(() -> notFound("评测运行"));
    List<PromptEvalItem> questions = items.findBySetIdOrderByOrdinalAsc(run.setId());
    var byItem = new java.util.HashMap<UUID, PromptEvalAnswer>();
    var drafts = new java.util.HashMap<UUID, PromptEvalAnswer>();
    for (PromptEvalAnswer a : answers.findByRunId(runId)) {
      if (a.side() == EvalSide.DRAFT) {
        drafts.put(a.itemId(), a);
      } else {
        byItem.put(a.itemId(), a);
      }
    }
    List<AnswerView> views = new ArrayList<>();
    for (PromptEvalItem item : questions) {
      PromptEvalAnswer saved = byItem.get(item.id());
      PromptEvalAnswer draft = drafts.get(item.id());
      if (saved != null) {
        views.add(view(item, saved));
      }
      if (draft != null) {
        views.add(view(item, draft));
      }
    }
    return new RunView(
        run.id(),
        run.setId(),
        run.baseVersionNo(),
        run.status(),
        run.itemTotal(),
        run.itemDone(),
        run.failReason(),
        run.startedAt(),
        run.finishedAt(),
        views,
        summarize(questions, drafts.values()));
  }

  /**
   * 汇总：胜率按**题**算，不按答案算。
   *
   * <p>★ 未评的题不参与计算，但 {@code totalItems} 与 {@code ratedItems} 都要给出来 —— 20 题里评了 3 题得出的 100% 不能当结论看。
   */
  private Summary summarize(List<PromptEvalItem> questions, Iterable<PromptEvalAnswer> drafts) {
    int better = 0;
    int same = 0;
    int worse = 0;
    for (PromptEvalAnswer a : drafts) {
      if (a.verdict() == null) {
        continue;
      }
      switch (a.verdict()) {
        case BETTER -> better++;
        case SAME -> same++;
        case WORSE -> worse++;
      }
    }
    int rated = better + same + worse;
    return new Summary(winRate(better, same, worse), rated, questions.size(), better, same, worse);
  }

  /**
   * 胜率：{@code (better + same × 0.5) / 已评题数}。
   *
   * <p>★ 三件事要说清：① 分母是**已评题数**不是总题数（未评的题不该被当成"没有改善"）； ② 一题没评时返回 {@code null} 而不是 0 —— {@code 0}
   * 会被读成"改坏了"，而真相是"还没评"； ③ {@code SAME} 算半分：把它当 0 会让"改了但没影响"看起来像退步。
   *
   * <p>包可见、静态：这个算法要能单测，不需要容器与数据库。
   */
  static Double winRate(int better, int same, int worse) {
    int rated = better + same + worse;
    if (rated == 0) {
      return null;
    }
    return Math.round((better + same * 0.5) * 1000.0 / rated) / 1000.0;
  }

  // ── 评分 ──────────────────────────────────────────────────────

  /**
   * 人工评一档。评分写在 DRAFT 行上（语义是"草稿相对基线"）。
   *
   * <p>★ 用 {@code runId + itemId} 定位而不是 {@code answerId}：人评的是"这道题两版之间"，不是"某一行"。
   */
  @Transactional
  public void rate(UUID runId, UUID itemId, EvalVerdict verdict, String note, UUID actorId) {
    if (verdict == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "要选一档", "更好 / 差不多 / 更差");
    }
    PromptEvalAnswer draft =
        answers
            .findByRunIdAndItemIdAndSide(runId, itemId, EvalSide.DRAFT)
            .orElseThrow(() -> notFound("这一题的草稿侧答案"));
    draft.rate(verdict, note == null ? null : note.strip(), actorId, Instant.now());
    answers.save(draft);
  }

  @Transactional
  public void clearRating(UUID runId, UUID itemId) {
    answers
        .findByRunIdAndItemIdAndSide(runId, itemId, EvalSide.DRAFT)
        .ifPresent(
            a -> {
              a.clearVerdict();
              answers.save(a);
            });
  }

  // ── 内部 ──────────────────────────────────────────────────────

  private PromptConfig requireConfig(UUID promptConfigId) {
    return catalog.findConfig(promptConfigId).orElseThrow(() -> notFound("提示词配置"));
  }

  private static BusinessException notFound(String what) {
    return new BusinessException(ErrorCode.NOT_FOUND, what + "不存在", "可能已被删除，刷新列表看看");
  }

  private static String readable(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    String msg = t.getMessage();
    if (msg == null || msg.isBlank()) {
      return t.getClass().getSimpleName();
    }
    String one = msg.replaceAll("\\s+", " ").strip();
    return one.length() > 200 ? one.substring(0, 200) + "…" : one;
  }

  private static AnswerView view(PromptEvalItem item, PromptEvalAnswer a) {
    return new AnswerView(
        a.id(),
        item.id(),
        item.ordinal(),
        item.question(),
        a.side(),
        a.answer(),
        a.elapsedMs(),
        a.passCount(),
        a.failCount(),
        a.verdict(),
        a.note());
  }

  /** 评测集摘要。{@code itemCount} 实时算，不落库（题目随时可增删）。 */
  public record SetView(UUID id, String name, Instant createdAt, int itemCount) {}

  public record ItemView(UUID id, int ordinal, String question) {}

  /** 一条答案。{@code verdict} / {@code note} 只在 DRAFT 侧有值。 */
  public record AnswerView(
      UUID id,
      UUID itemId,
      int ordinal,
      String question,
      EvalSide side,
      String answer,
      Integer elapsedMs,
      int passCount,
      int failCount,
      EvalVerdict verdict,
      String note) {}

  /**
   * 汇总。
   *
   * @param winRate {@code (better + same × 0.5) / ratedItems}，未评时 {@code null}（不是 0）
   * @param ratedItems 已评的题数
   * @param totalItems 题总数
   */
  public record Summary(
      Double winRate, int ratedItems, int totalItems, int better, int same, int worse) {}

  public record RunView(
      UUID id,
      UUID setId,
      Integer baseVersionNo,
      PromptEvalStatus status,
      int itemTotal,
      int itemDone,
      String failReason,
      Instant startedAt,
      Instant finishedAt,
      List<AnswerView> answers,
      Summary summary) {}
}
