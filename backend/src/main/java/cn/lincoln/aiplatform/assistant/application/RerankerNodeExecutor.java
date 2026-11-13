package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.WorkflowRerankPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 重排节点（{@code RERANKER_NODE}）的执行器（{@code #333}）。
 *
 * <h3>它做什么</h3>
 *
 * <p>读上游检索节点写的结构化命中（{@code retrieval_hits}，每项含 {@code segmentId} / {@code docName} / {@code
 * content}），调重排端口按"与问题的相关性"重新排序，写出两份结果：
 *
 * <ul>
 *   <li>{@link #RESULT_KEY}（{@code rerank_result}）：按新顺序拼好的文本，形状与 {@code retrieval_result} 一致 —— 下游
 *       {@code AI_CHAT_NODE} 读的是它，所以下游不用为"接不接重排"改一个字；
 *   <li>{@link #HITS_KEY}（{@code rerank_hits}）：带 {@code rank} 的结构化命中，给"引用编号"这类需要知道顺序的地方用。
 * </ul>
 *
 * <h3>★ 为什么没有上游命中时直接失败，而不是自己再检索一次</h3>
 *
 * <p>"重排"这个词里没有"检索"。让它自己去查库，等于同一个节点同时承担两件事，而且配置里必须再放一份 {@code kbIds} —— 两份 kbIds 就会漂（{@code #335}
 * 的教训：同一个事实写两处，必有一处先漂）。
 *
 * <h3>★ 为什么失败时抛异常，而端口自己降级返回原序</h3>
 *
 * <p>两件事不同：<b>重排模型坏了</b>（端口内部降级、给原序）与<b>这张图根本没接检索节点</b>（配置错）。 前者不该让流程失败（重排是增益件，见 {@code RerankPort}
 * 的类注释），后者必须明确失败 —— 否则用户看到的是 "重排节点跑了、结果和检索一模一样"，会以为重排不生效，实际是压根没接上。
 */
@Component
public class RerankerNodeExecutor implements NodeExecutor {

  /** 重排后文本写进 state 的键。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.RERANK_RESULT;

  /** 重排后结构化命中写进 state 的键。 */
  public static final String HITS_KEY = WorkflowNodeKeys.Output.RERANK_HITS;

  /** 配置里只重排前几名的键；与前端契约 {@code RerankerNodeConfig.topK} 同名。 */
  static final String CONFIG_TOP_K = "topK";

  /** 配置里覆盖问题的键；不给就用上游 {@code query}。 */
  static final String CONFIG_QUESTION = "question";

  /** 默认窗口 5 —— 与 {@code RerankPort} 的离线读数一致（前 5 个 +10.3 点、全量 20 个只 +9.8 点却慢 5 倍）。 */
  private static final int DEFAULT_TOP_K = 5;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ObjectProvider<WorkflowRerankPort> rerankPort;

  public RerankerNodeExecutor(ObjectProvider<WorkflowRerankPort> rerankPort) {
    this.rerankPort = rerankPort;
  }

  @Override
  public String type() {
    return "RERANKER_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    List<Map<String, Object>> hits = readHits(context);
    if (hits.isEmpty()) {
      throw new IllegalStateException("重排节点没有收到候选：它要接在检索节点后面（读取流程变量 retrieval_hits）");
    }
    WorkflowRerankPort port = rerankPort.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("重排能力还没装配：没有 WorkflowRerankPort 的实现");
    }
    String question = questionOf(context);
    int topK = topK(context.nodeConfig());

    // 正文给重排模型看：文档名 + 正文（与 RetrievalService 里同一个做法 —— B3 判的是"第 1 条命中且同文档"）。
    Map<UUID, String> texts = new LinkedHashMap<>();
    List<UUID> ordered = new ArrayList<>(hits.size());
    for (Map<String, Object> hit : hits) {
      UUID id = asUuid(hit.get("segmentId"));
      if (id == null) {
        // ★ 缺 id 的候选不能悄悄丢掉：丢几个，重排就是在另一批候选上做排序，读数看起来正常。
        throw new IllegalStateException("检索命中里有一条没有 segmentId，重排无法按 id 排序");
      }
      ordered.add(id);
      String docName = asText(hit.get("docName"));
      String content = asText(hit.get("content"));
      texts.put(id, docName.isEmpty() ? content : docName + "\n" + content);
    }

    List<UUID> ranked = port.rerank(question, ordered, texts, topK);
    if (ranked == null) {
      throw new IllegalStateException("重排端口返回了 null，契约要求返回入参的一个排列");
    }
    // ★ 端口契约要求返回排列；实现违约（丢 id）时按"缺的追加到末尾"补齐，而不是让下游少几条 ——
    //   少几条最难发现：模型照样答，只是没用到那几条资料。
    List<UUID> complete = new ArrayList<>(new LinkedHashSet<>(ranked));
    complete.addAll(ordered.stream().filter(id -> !complete.contains(id)).toList());

    Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
    for (Map<String, Object> hit : hits) {
      byId.put(asUuid(hit.get("segmentId")), hit);
    }

    StringBuilder merged = new StringBuilder();
    List<Map<String, Object>> reranked = new ArrayList<>(complete.size());
    int rank = 1;
    for (UUID id : complete) {
      Map<String, Object> hit = byId.get(id);
      if (hit == null) {
        continue;
      }
      String docName = asText(hit.get("docName"));
      String content = asText(hit.get("content"));
      if (!merged.isEmpty()) {
        merged.append("\n\n");
      }
      merged.append('[').append(docName).append("] ").append(content);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("rank", rank++);
      item.put("segmentId", id.toString());
      item.put("docName", docName);
      item.put("content", content);
      reranked.add(item);
    }
    context.state().write(RESULT_KEY, merged.toString(), KeyUpdate.REPLACE);
    context.state().write(HITS_KEY, MAPPER.writeValueAsString(reranked), KeyUpdate.REPLACE);
  }

  /** 读上游的结构化命中；缺失、空串、形状不对都给空列表（由调用方决定报错文案）。 */
  private static List<Map<String, Object>> readHits(WorkflowNodeContext context) {
    String raw = context.state().read(SearchDatasetNodeExecutor.HITS_KEY);
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    JsonNode node = MAPPER.readTree(raw);
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("流程变量 retrieval_hits 不是 JSON 数组，重排节点读不懂");
    }
    List<Map<String, Object>> hits = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      Map<String, Object> hit = new LinkedHashMap<>();
      hit.put("segmentId", textOrNull(item, "segmentId"));
      hit.put("docName", textOrNull(item, "docName"));
      hit.put("content", textOrNull(item, "content"));
      hits.add(hit);
    }
    return hits;
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /**
   * 问题原文：配置里的 {@code question} 优先，否则用上游 {@code query}。
   *
   * <p>★ 为什么允许覆盖：重排该比对的是"用户真正问的那句"。上游若接的是问题改写节点，{@code query} 已经是改写后的 独立查询 ——
   * 多数情况下那正是要的；但用户明确想按原话重排时，得有个地方写。
   */
  private static String questionOf(WorkflowNodeContext context) {
    Object raw = context.nodeConfig().get(CONFIG_QUESTION);
    if (raw instanceof String text && !text.isBlank()) {
      return text;
    }
    String query = context.state().read(WorkflowNodeKeys.Output.QUERY);
    if (query != null && !query.isBlank()) {
      return query;
    }
    String input = context.input();
    return input == null ? "" : input;
  }

  /** 只重排前几名；缺失、不是正整数、或大于候选数时按实际候选数处理。 */
  private static int topK(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_TOP_K);
    if (raw instanceof Number number && number.intValue() > 0) {
      return number.intValue();
    }
    return DEFAULT_TOP_K;
  }

  private static UUID asUuid(Object raw) {
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
    return null;
  }

  private static String asText(Object raw) {
    return raw instanceof String text ? text : "";
  }
}
