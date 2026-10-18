package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.retrieval.domain.QueryUnderstanding;
import cn.lincoln.aiplatform.retrieval.domain.QueryUnderstandingParser;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 查询理解（第 12 周 · 0.4.0）—— 一次模型调用产出 M 个子问题、每个带 N 条查询（{@code 10 §3.1/§3.3}）。
 *
 * <h3>★ 这个类存在的唯一理由：**把不确定性关在一层里**</h3>
 *
 * <p>{@code 10 §3.1.3} 那条硬纪律是"改写失败绝不能让问答失败"。所以这一层的返回值<strong>永远可用</strong>： 拿不到模型、模型超时、输出解析不了 ——
 * 一律退化成 {@link QueryUnderstanding#single}（原始问题单路）， 并通过 {@link Outcome#degradeLevel()}
 * 把"我降级了、为什么"带出去。 <strong>调用方不需要写 try/catch</strong>，也就不可能出现"某一处忘了兜底"这件事。
 *
 * <h3>★ 为什么解析重试一次，而不是超时重试一次</h3>
 *
 * <p>两者是不同的失败（{@code 10 §3.1.3} 分了两行）：<strong>超时</strong>是上游慢， 再试一次只会把预算再烧一遍（而且首字延迟是用户能直接感受到的）；
 * <strong>解析失败</strong>常是"模型这次多说了一句话"，追加一句"只输出 json"再问一次， 收益远大于成本。所以只有解析失败才重试，且只重试一次。
 */
@Service
public class QueryUnderstandingService {

  private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

  /** 单次调用的默认时间预算（毫秒）。与 {@code 10 §6.2} 第 6 项的检索阶段预算同量级。 */
  public static final long DEFAULT_BUDGET_MS = 5_000;

  /** 重试时追加的提醒 —— 只针对"解析失败"，所以它只教一件事：形状。 */
  private static final String RETRY_REMINDER =
      "\n\n上一次的输出无法解析成上面的 json。请**只**输出那个 json 对象本身：" + "不要解释、不要 markdown 代码块、不要前后的任何文字。";

  private final ChatPort chat;

  /**
   * 预算与低配开关（`app.retrieval.*`）。
   *
   * <p>★ 这里原来是一个写死的 {@code DEFAULT_BUDGET_MS = 5000}：在 GPU 上够用， 而在 <b>CPU + 小模型</b> 上一次改写就要 4–5s ⇒
   * 预算在第 0 路就耗尽。
   */
  private final RetrievalBudgetProperties budget;

  public QueryUnderstandingService(ChatPort chat, RetrievalBudgetProperties budget) {
    this.chat = chat;
    this.budget = budget;
  }

  /**
   * 这次查询理解的产出。
   *
   * @param understanding 一定会有的可用形状（最差 = 原始问题单路）
   * @param degradeLevel {@code 10 §6.3} 的档位：<strong>0</strong>=正常、<strong>3</strong>=只做成了改写、
   *     <strong>4</strong>=退回原始问题单路。★ D1（去多跳）/D2（去分解）属于第 14 / 配置项，不在这里产生
   * @param degradeReason 为什么降级（{@code degradeLevel > 0} 时非空）—— ★ 没有它，"这周的答案为什么变差了"没人答得上来（{@code 10
   *     §6.3} 的原话）
   * @param truncated 子问题数或查询条数被截断过
   * @param parseFailed ★ 解析失败（重试后仍失败）—— 这个数单独计，它是"当前模型能不能胜任这一层"的实测数据
   * @param events 解析器改动了什么（去重 / 截断 / 兜底 / 合并）
   * @param elapsedMs 这一层花掉的时间 —— 直接从首字延迟里扣的就是它
   */
  public record Outcome(
      QueryUnderstanding understanding,
      int degradeLevel,
      String degradeReason,
      boolean truncated,
      boolean parseFailed,
      List<String> events,
      long elapsedMs) {}

  /**
   * 跑一次查询理解。
   *
   * @param question 用户的原始问题（<strong>永远是保底输入</strong>）
   * @param queryCount 每条查询数上限（{@code 0} = 不做这一层）
   * @param decomposeEnabled 是否允许拆子问题
   * @param maxSubQuestions 子问题数上限
   * @param budgetMs 这一层的时间预算
   */
  public Outcome understand(
      String question,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions,
      long budgetMs) {
    long startedAt = System.nanoTime();
    if (queryCount <= 0) {
      // ★ 不是降级，是配置：报表里不许把它算成"降级率"（否则"我们关了这功能"会看起来像"模型总在坏"）
      return new Outcome(
          QueryUnderstanding.single(question),
          0,
          "查询理解未开启（queryCount=0）",
          false,
          false,
          List.of(),
          0);
    }

    Optional<UUID> modelId = chat.pickChatModel();
    if (modelId.isEmpty()) {
      return fallback(question, startedAt, "没有可用的对话模型（CHAT 类型且已加载）", false);
    }

    String prompt = prompt(question, queryCount, maxSubQuestions);
    // ★ 调用方传了预算就用它；没传才用配置（原来是写死的 DEFAULT_BUDGET_MS）
    long timeoutMs = budgetMs > 0 ? budgetMs : budget.queryUnderstandingBudgetMs();

    String raw;
    try {
      raw = chat.complete(modelId.get(), prompt, Duration.ofMillis(timeoutMs));
    } catch (BusinessException e) {
      // 不可达 / 超时 / 类型不对 —— 都是"这一层不可用"，都不是"这次问答失败"
      log.warn("查询理解不可用，退回原始问题单路：{}", e.getMessage());
      return fallback(question, startedAt, "调用对话模型失败：" + e.getMessage(), false);
    }

    QueryUnderstandingParser.Parsed parsed =
        QueryUnderstandingParser.parse(
            raw, question, queryCount, maxSubQuestions, decomposeEnabled);
    // ★★ 重试开关（低配建议关掉）：CPU 上第二次调用再花 4–5s，只救「模型多说了句话」这一类问题。
    //    关掉时**如实**置 parseFailed 并说明「已按配置关闭重试」，不假装尝试过。
    if (!parsed.usable() && !budget.queryUnderstandingRetryOnParseFailure()) {
      log.warn("查询理解解析失败，且已按配置关闭重试：{}", parsed.failure());
      return fallback(question, startedAt, "解析模型输出失败（已按配置关闭重试）：" + parsed.failure(), true);
    }
    if (!parsed.usable()) {
      // ★ 只对解析失败重试一次（理由见类注释）
      log.warn("查询理解解析失败，重试一次：{}", parsed.failure());
      try {
        raw = chat.complete(modelId.get(), prompt + RETRY_REMINDER, Duration.ofMillis(timeoutMs));
      } catch (BusinessException e) {
        return fallback(question, startedAt, "重试时调用对话模型失败：" + e.getMessage(), true);
      }
      parsed =
          QueryUnderstandingParser.parse(
              raw, question, queryCount, maxSubQuestions, decomposeEnabled);
      if (!parsed.usable()) {
        log.warn("查询理解解析连续两次失败，退回原始问题单路：{}", parsed.failure());
        return fallback(question, startedAt, "解析模型输出失败（已重试一次）：" + parsed.failure(), true);
      }
    }

    long elapsed = elapsedMs(startedAt);
    int level = 0;
    String reason = parsed.events().isEmpty() ? null : String.join("；", parsed.events());
    // 解析成功但只产出一条查询 ⇒ 改写做成了、扩展没做成（10 §6.3 的 D3）
    if (parsed.understanding().totalQueries() == 1 && queryCount > 1) {
      level = 3;
      reason = "模型只产出 1 条查询（扩展未生效）" + (reason == null ? "" : "；" + reason);
    }
    if (level == 0) {
      // 正常路径的 events 是"我改动了什么"（截断/去重），不是"降级原因" —— 别把两者混成一个字段
      reason = parsed.events().isEmpty() ? null : String.join("；", parsed.events());
    }
    log.info(
        "查询理解：子问题 {} × 查询 {}（配置 {}/{}），耗时 {}ms，降级 D{}",
        parsed.understanding().subQuestionCount(),
        parsed.understanding().totalQueries(),
        maxSubQuestions,
        queryCount,
        elapsed,
        level);
    return new Outcome(
        parsed.understanding(), level, reason, parsed.truncated(), false, parsed.events(), elapsed);
  }

  // ────────────────────────── 提示词 ──────────────────────────

  /**
   * 提示词（{@code 10 §3.1.2} 的六条要求逐条落进规则里）。
   *
   * <p>★ 最后那段 schema 不是"给模型看的说明"，而是<strong>契约</strong>：解析器就按这个形状读 （{@code
   * QueryUnderstandingParser}），而测试里的桩服务也按它产出。三者用同一个形状， 才谈得上"结构化输出"。
   *
   * <p>★ 为什么把规则写这么细：这六条每一条都对应一种"产出了坏查询"的失败模式 —— 改掉编号（第 2 条）、补充用户没问的东西（第 3 条）、只换标点（第 4 条）、混语言（第 5
   * 条）、 查询过长（第 6 条）。少写一条，症状都是"召回变差"，而**没有任何地方报错**。
   */
  private static String prompt(String question, int queryCount, int maxSubQuestions) {
    return """
    你是检索系统的查询分析器。请把用户的问题改写为更适合检索的形式，必要时拆成多个子问题。

    规则：
    1. 消解指代与省略（"它 / 这个 / 那笔"要按上下文补全成完整的说法）；
    2. 专有名词、编号、日期、金额**原样保留**（编号类查询正是全文检索的主场）；
    3. **不要改写提问意图** —— 不要补充用户没问的内容；
    4. 同一个子问题的多条查询之间必须**语义互补**（同义改写 / 上位下位 / 口语与书面），不是换标点；
    5. 与用户问题保持同一语言；
    6. 每条查询不超过 60 字；
    7. 只有当问题确实包含**多个互不相干的意图**时才拆子问题，最多 %d 个；只有一个意图时只输出一个子问题；
    8. 每个子问题的 queries 最多 %d 条。

    只输出一个 json 对象，不要任何解释、不要 markdown 代码块。json 结构严格照下面这个形状：
    {"properties":{"subQuestions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"queries":{"type":"array","items":{"type":"string"}}}}}}}

    用户问题：%s
    """
        .formatted(maxSubQuestions, queryCount, question);
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Outcome fallback(String question, long startedAt, String reason, boolean parseFailed) {
    return new Outcome(
        QueryUnderstanding.single(question),
        4,
        reason,
        false,
        parseFailed,
        List.of(),
        elapsedMs(startedAt));
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000;
  }
}
