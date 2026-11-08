package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.ListwiseRerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LLM listwise 重排的实现：把前 {@code topK} 个候选连同问题一起交给对话模型，让它给出"最能回答"的顺序。
 *
 * <h3>★ 离线读数（决定它默认关的那组）</h3>
 *
 * <p>20 条试跑（`.build/listwise_eval.py`，同一批 cross-encoder 候选）：B3 65.00% → <b>70.00%（+5.00 点）</b>，变好 2
 * / 变差 1 / 不变 17，单条 <b>3.12s</b>（p95 11.69s）。
 *
 * <p>⇒ 收益是真的，但代价在低配机器上装不下（叠加 cross-encoder 的 8.5s 后单跳 11.6s，破 B4 的 10s）。 用户 2026-11-08
 * 拍板：<b>不默认开、做成配置</b>，「部署到更高配置的服务器上这些延迟应该可以下压」。
 *
 * <h3>★★ 三条实现纪律</h3>
 *
 * <ol>
 *   <li><b>失败一律降级回原序</b>：没有可用对话模型 / 超时 / 模型输出里一个编号都没有 ⇒ 返回原序。 它是增益件，不能因为模型抽风让检索不可用（与 {@code
 *       RoutingEmbeddingAdapter} 的"向量绝不静默回退" 相反 —— 那条是命脉件）。
 *   <li><b>只喂前 {@code topK} 个，且正文截断到 220 字</b>：5 个候选 × 220 字已经在 1100 字量级， 再长会让 listwise
 *       反而变慢、且把模型注意力拉散（离线评测就用 220）。
 *   <li><b>编号解析是宽松的</b>：模型可能输出 <code>[2] 1,0</code>、<code>2,1,0</code>、<code>顺序：2 1 0</code> ——
 *       只抓数字、去重、补全未出现的编号（补在末尾保持相对原序）。
 * </ol>
 */
@Component
public class LlmListwiseRerankerAdapter implements ListwiseRerankPort {

  private static final Logger log = LoggerFactory.getLogger(LlmListwiseRerankerAdapter.class);

  /** 单个候选正文截断长度（与离线评测口径一致）。 */
  private static final int SNIPPET_CHARS = 220;

  /** 一次 listwise 的预算。20 条试跑的均值 3.12s、p95 11.69s ⇒ 给到 20s 才不会被 p95 打成降级。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final ChatPort chatPort;
  private final RerankProperties properties;

  @Autowired
  public LlmListwiseRerankerAdapter(ChatPort chatPort, RerankProperties properties) {
    this.chatPort = chatPort;
    this.properties = properties;
  }

  @Override
  public List<UUID> rerank(
      String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
    if (candidateIds == null || candidateIds.size() < 2 || !properties.listwiseEnabled()) {
      return candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }
    int window = Math.min(Math.min(topK, properties.listwiseTopK()), candidateIds.size());
    if (window < 2) {
      return List.copyOf(candidateIds);
    }

    List<UUID> head = new ArrayList<>(candidateIds.subList(0, window));
    Optional<UUID> modelId = chatPort.pickChatModel();
    if (modelId.isEmpty()) {
      log.debug("listwise 重排跳过：没有可用的对话模型");
      return List.copyOf(candidateIds);
    }

    try {
      String answer = chatPort.complete(modelId.get(), prompt(query, head, texts), TIMEOUT);
      List<Integer> order = parseOrder(answer, window);
      if (order.isEmpty()) {
        log.debug("listwise 重排降级：模型输出里没有可用编号（原文 {}）", abbreviate(answer));
        return List.copyOf(candidateIds);
      }
      List<UUID> result = new ArrayList<>(candidateIds.size());
      for (int i : order) {
        result.add(head.get(i));
      }
      result.addAll(candidateIds.subList(window, candidateIds.size()));
      return result;
    } catch (RuntimeException e) {
      // ★ 增益件：任何异常都只降级，不往上抛（否则一次模型抖动会让整轮问答失败）
      log.warn("listwise 重排失败，按原序返回：{}", e.toString());
      return List.copyOf(candidateIds);
    }
  }

  /**
   * 拼 listwise 的提示词。
   *
   * @param query 原始问题
   * @param head 窗口内的候选（顺序即当前名次）
   * @param texts segmentId → 正文
   * @return 提示词
   */
  private String prompt(String query, List<UUID> head, Map<UUID, String> texts) {
    StringBuilder sb = new StringBuilder();
    sb.append("下面是一个问题和 ").append(head.size()).append(" 段候选资料。\n");
    sb.append("请判断哪一段最能直接回答问题，按「最能回答」到「最不能回答」给出编号顺序。\n");
    sb.append("只输出编号，用逗号分隔，不要解释，不要多余文字。\n\n");
    sb.append("问题：").append(query).append("\n\n");
    for (int i = 0; i < head.size(); i++) {
      String body = texts.getOrDefault(head.get(i), "");
      body = body.replace('\n', ' ').replace('\r', ' ');
      if (body.length() > SNIPPET_CHARS) {
        body = body.substring(0, SNIPPET_CHARS);
      }
      sb.append('[').append(i).append("] ").append(body).append('\n');
    }
    sb.append("\n顺序：");
    return sb.toString();
  }

  /**
   * 从模型输出里抽出编号顺序（宽松解析：只抓数字、去重、补全）。
   *
   * @param answer 模型原文
   * @param window 候选个数
   * @return 下标顺序；<b>空</b>表示一个有效编号都没抓到（调用方据此降级）
   */
  private List<Integer> parseOrder(String answer, int window) {
    List<Integer> order = new ArrayList<>(window);
    if (answer == null) {
      return order;
    }
    Set<Integer> seen = new LinkedHashSet<>();
    for (int i = 0; i < answer.length(); i++) {
      char c = answer.charAt(i);
      if (c >= '0' && c <= '9') {
        int n = c - '0';
        if (n < window && seen.add(n)) {
          order.add(n);
        }
      }
    }
    if (order.isEmpty()) {
      return order;
    }
    for (int i = 0; i < window; i++) {
      if (!seen.contains(i)) {
        order.add(i);
      }
    }
    return order;
  }

  /**
   * 日志里截断模型原文。
   *
   * @param s 原文
   * @return 最多 120 字
   */
  private String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 120 ? s : s.substring(0, 120) + "…";
  }
}
