package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.domain.HopDecision;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 多跳的"判定"层（第 14 周 · 0.4.2，{@code 10 §3.4.3}）—— **开启多跳后唯一新增的模型调用**。
 *
 * <h3>★ 与 {@link QueryUnderstandingService} 同一条纪律：这一层的返回值永远可用</h3>
 *
 * <p>{@code 10 §3.4.6}：判定调用失败 ⇒ <strong>停止迭代、用已有结果生成</strong>，绝不报错。 所以这里只有两种产出：可用（{@code
 * decision.usable()}）或不可用（带原因），**调用方不需要写 try/catch**。
 *
 * <h3>★ 输入只给"标题 + 前 100 字"</h3>
 *
 * <p>{@code 10 §3.4.3} 的原话：<em>不是全文</em>。两个理由：省 token；而且<strong>标题已经足够暴露"缺什么"</strong> ——
 * 判定要回答的是"这些线索够不够回答原问题"，不是"从材料里找出答案"。把全文灌进去会让这一次调用与生成调用一样贵， 而它只是决定"要不要再查一轮"。
 */
@Service
public class MultiHopService {

  private static final Logger log = LoggerFactory.getLogger(MultiHopService.class);

  /** 每个已命中分段喂给判定的正文长度（{@code 10 §3.4.3}：前 100 字）。 */
  public static final int SNIPPET_LENGTH = 100;

  private final ChatPort chat;

  public MultiHopService(ChatPort chat) {
    this.chat = chat;
  }

  /**
   * 这次判定的产出。
   *
   * @param decision 判定结果；不可用时 {@code decision.usable()} 为假，{@code decision.failure()} 是原因
   * @param elapsedMs 这一次花掉的时间（直接进"检索阶段"的预算账）
   */
  public record Outcome(HopDecision decision, long elapsedMs) {

    /** 判定可用吗（不可用 ⇒ 调用方停止迭代，并按 {@code 10 §6.3} 记 D1）。 */
    public boolean usable() {
      return decision != null && decision.usable();
    }
  }

  /**
   * 判定"已有命中够不够"，不够则给出下一轮查询。
   *
   * @param question 用户的**原始问题**（不是改写后的查询 —— 判定要回答的是"原问题答得出来吗"）
   * @param hits 本轮已命中的分段（只用标题与前 {@value #SNIPPET_LENGTH} 字）
   * @param timeoutMs 这一次调用的超时；≤0 时用 {@code DEFAULT_BUDGET_MS}
   */
  public Outcome decide(String question, List<SegmentSearchPort.Card> hits, long timeoutMs) {
    long startedAt = System.nanoTime();

    Optional<UUID> modelId = chat.pickChatModel();
    if (modelId.isEmpty()) {
      return new Outcome(HopDecision.failure("没有可用的对话模型"), elapsedMs(startedAt));
    }

    String prompt = prompt(question, hits);
    long timeout = timeoutMs > 0 ? timeoutMs : QueryUnderstandingService.DEFAULT_BUDGET_MS;
    HopDecision decision;
    try {
      // 格式说明与 JSON 解析都在适配器的官方转换器里；这里只讲"怎么判"
      HopDecision.Payload payload =
          chat.completeAs(
              modelId.get(), prompt, HopDecision.Payload.class, Duration.ofMillis(timeout));
      decision = HopDecision.from(payload);
    } catch (BusinessException e) {
      // 不可达 / 超时 / 输出不符合 schema —— 都算"这一层不可用"，不是"这次检索失败"
      decision = HopDecision.failure(e.getMessage());
    }
    if (!decision.usable()) {
      log.warn("多跳判定不可用，用已有结果继续：{}", decision.failure());
    }
    return new Outcome(decision, elapsedMs(startedAt));
  }

  // ────────────────────────── 提示词 ──────────────────────────

  /**
   * 提示词：只问两件事，并把"要不要再查"的判断标准说清。
   *
   * <p>★ <strong>这里不写"请返回 json"</strong>：格式说明由适配器的官方转换器追加（{@code BeanOutputConverter.getFormat()}，含
   * schema），所以这段只讲<strong>怎么判</strong>，不讲怎么排版 —— 手写格式说明与转换器的说明同时出现，是"两处契约"的经典开端。
   */
  private static String prompt(String question, List<SegmentSearchPort.Card> hits) {
    StringBuilder snippets = new StringBuilder();
    int index = 1;
    for (SegmentSearchPort.Card card : hits) {
      snippets
          .append(index++)
          .append(". ")
          .append(card.title() == null ? "（无标题）" : card.title())
          .append("：")
          .append(snippet(card.content()))
          .append('\n');
    }
    return """
    你在判断"已有的检索结果够不够回答用户的问题"。

    判断标准：
    1. 只有当材料里**已经能直接回答**问题时，sufficient 才为 true；
    2. 如果材料只给出了**线索**（例如一个编号、一个名称），而答案要用它再查一次才拿得到，sufficient 为 false，
       并在 nextQuery 里写出**那一次要查的查询**（不要写成"再查一下"这种空话，要写出具体要查什么）；
    3. nextQuery 不超过 60 字，与用户问题同一语言，专有名词与编号原样保留；
    4. 如果材料确实不够、而你也想不出更好的查询，就把 sufficient 置 false、nextQuery 留空 —— 不要硬编。

    用户问题：%s

    已有结果（标题：正文前 100 字）：
    %s
    """
        .formatted(question, snippets);
  }

  private static String snippet(String content) {
    if (content == null) {
      return "";
    }
    String flat = content.replaceAll("\\s+", " ").strip();
    return flat.length() <= SNIPPET_LENGTH ? flat : flat.substring(0, SNIPPET_LENGTH);
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000;
  }
}
