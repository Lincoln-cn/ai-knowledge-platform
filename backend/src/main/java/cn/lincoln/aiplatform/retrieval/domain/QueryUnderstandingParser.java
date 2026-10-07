package cn.lincoln.aiplatform.retrieval.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型的原始输出解析成 {@link QueryUnderstanding}（{@code 10 §3.1.2} / {@code §3.3.2}）。
 *
 * <h3>★ 这一层的第一条纪律：**不信模型**</h3>
 *
 * <p>结构化输出不是保证，是一种<strong>请求</strong>。实测过的形状至少有四种（都能遇到）： 外面套一层 ```json
 * 代码块、字段名对但类型不对、只给了一个数组、以及干脆用换行列了几条。 所以这里<strong>先容错解析、再逐条校验</strong>，而且每一步的"我改了什么"都要留痕 （{@link
 * Parsed#events()}）—— 因为"模型给了 5 条我砍到 3 条"与"模型只给了 3 条" 在界面上长得一样，只有事件能区分。
 *
 * <h3>★ 第二条纪律：规则是**强制的下限**，不是建议</h3>
 *
 * <p>子问题数、查询条数、单条长度都有上限（{@code 10 §6.2}）。上限超出时<strong>按顺序截断并记事件</strong>
 * （不是静默丢弃，也不是报错——报错会让一次多层策略的收益被一个超限数字抹掉）。
 *
 * <h3>★ 与 ROADMAP 的一处口径差异（如实记）</h3>
 *
 * <p>{@code ROADMAP} 第 12 周判据 ① 写的是"换行分隔解析 + <strong>数量不符回退原查询</strong>"， 而 {@code 10 §3.3.2}
 * 写的是"超上限 ⇒ 按顺序截断"。两者对"数量不符"的处理相反。 这里取<strong>设计册</strong>的口径（截断），理由：回退到原查询会把"模型多给了两条"
 * 放大成"这一层的收益全丢"，而截断是可解释的（事件里能看到砍了几条）。 ★ 但"数量不符"里那两种**真的该回退**的情形仍然回退：产出 0 条、以及解析不出任何查询。
 */
public final class QueryUnderstandingParser {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 单条查询的长度上限（{@code 10 §3.1.2} 第 6 条：长查询在全文路会退化）。 */
  public static final int MAX_QUERY_LENGTH = 60;

  /** 上限的硬顶 —— 配置再怎么填都不越过它们（{@code 10 §6.2} 的"上限"一列）。 */
  public static final int MAX_QUERY_COUNT_CAP = 5;

  public static final int MAX_SUB_QUESTIONS_CAP = 5;

  private QueryUnderstandingParser() {}

  /**
   * 解析与校验的产出。
   *
   * @param subQuestions 可用的子问题（{@code failure} 非空时它是"原始问题单路"的兜底形状）
   * @param truncated 是否发生了截断（子问题数或查询条数）—— 埋点要看它，见 {@code 10 §7.1}
   * @param events 我改动了什么（去重 / 截断 / 兜底 / 合并）—— ★ 必须留痕，否则静默
   * @param failure <strong>非空表示这一层没产出可用结果</strong>，调用方据此降级到 D4（原始问题单路）
   */
  public record Parsed(
      QueryUnderstanding understanding, boolean truncated, List<String> events, String failure) {

    public boolean usable() {
      return failure == null;
    }
  }

  /**
   * 解析模型的原始输出。
   *
   * @param raw 模型输出的原文（可能带代码块、前后废话）
   * @param originalQuestion 用户的原始问题 —— 兜底与"单意图"时用它
   * @param queryCount 每条查询数上限（1 = 纯改写；0 不该走到这里）
   * @param maxSubQuestions 子问题数上限
   * @param decomposeEnabled {@code false} ⇒ 把多个子问题**合并**成一个（{@code 10 §6.3} 的 D2）
   */
  public static Parsed parse(
      String raw,
      String originalQuestion,
      int queryCount,
      int maxSubQuestions,
      boolean decomposeEnabled) {
    List<String> events = new ArrayList<>();
    int queryLimit = clamp(queryCount, 1, MAX_QUERY_COUNT_CAP);
    int subLimit = clamp(maxSubQuestions, 1, MAX_SUB_QUESTIONS_CAP);

    List<QueryUnderstanding.SubQuestion> parsed = parseJson(raw, events);
    if (parsed == null) {
      // ★ 兜底解析：按行切。它不是"退而求其次"——很多模型对"列几条查询"这种要求
      //   会直接给出行列表，而那是**完全可用**的输出，只是形状不同。
      // ★ 但**要求至少两行**：单行非 JSON 文本正是"模型说了句废话"的形状
      //   （"抱歉，我无法完成这个请求"）。把它当成一条查询会同时丢掉两件事 ——
      //   检索不到东西（这还好），以及"解析失败"这个数（坏）：它是模型准入的实测指标，
      //   被一次"看起来成功了"的解析掩盖掉，就再也量不出来。
      List<String> lines = parseLines(raw);
      if (lines.size() < 2) {
        return new Parsed(
            QueryUnderstanding.single(originalQuestion),
            false,
            events,
            "模型输出既不是 json、也不是多行查询列表（只拿到 " + lines.size() + " 行）");
      }
      events.add("json 解析失败，按行解析出 " + lines.size() + " 条");
      parsed = List.of(new QueryUnderstanding.SubQuestion(originalQuestion, lines));
    }

    boolean truncated = false;

    // ① 子问题数上限：按顺序截断
    if (parsed.size() > subLimit) {
      events.add("子问题 " + parsed.size() + " 个超过上限 " + subLimit + "，按顺序截断");
      parsed = parsed.subList(0, subLimit);
      truncated = true;
    }

    // ② 逐个子问题：清洗查询、长度截断、数量截断、空则兜底成它自己的 question
    List<QueryUnderstanding.SubQuestion> cleaned = new ArrayList<>(parsed.size());
    for (QueryUnderstanding.SubQuestion sub : parsed) {
      String question = text(sub.question(), originalQuestion);
      List<String> queries = new ArrayList<>();
      for (String query : sub.queries()) {
        String value = text(query, null);
        if (value == null) {
          continue;
        }
        if (value.length() > MAX_QUERY_LENGTH) {
          value = value.substring(0, MAX_QUERY_LENGTH);
          events.add("查询超长（>" + MAX_QUERY_LENGTH + " 字）已截断：「" + abbreviate(value) + "…」");
        }
        if (queries.contains(value)) {
          events.add("查询重复已去掉：「" + abbreviate(value) + "」");
          continue;
        }
        queries.add(value);
      }
      if (queries.isEmpty()) {
        // ★ 兜底成该子问题的 question，而不是丢掉它：丢掉会让"这个子问题"从下游消失，
        //   而它恰恰是用户问过的意图之一
        events.add("子问题的查询为空，用它的 question 兜底：「" + abbreviate(question) + "」");
        queries.add(question);
      }
      if (queries.size() > queryLimit) {
        events.add("查询 " + queries.size() + " 条超过上限 " + queryLimit + "，按顺序截断");
        queries = queries.subList(0, queryLimit);
        truncated = true;
      }
      cleaned.add(new QueryUnderstanding.SubQuestion(question, List.copyOf(queries)));
    }

    // ③ 子问题去重（字符串包含关系粗判，10 §3.3.2）—— 只对"问题"判，不合并不同的查询集
    List<QueryUnderstanding.SubQuestion> deduped = new ArrayList<>(cleaned.size());
    for (QueryUnderstanding.SubQuestion candidate : cleaned) {
      boolean duplicate =
          deduped.stream()
              .anyMatch(
                  kept ->
                      kept.question().contains(candidate.question())
                          || candidate.question().contains(kept.question()));
      if (duplicate) {
        events.add("子问题与前面重复，已合并：「" + abbreviate(candidate.question()) + "」");
        continue;
      }
      deduped.add(candidate);
    }

    // ④ 关掉分解 ⇒ 合并成一个（D2 的形状：问题回到原始问题，查询合并去重后截断）
    if (!decomposeEnabled && deduped.size() > 1) {
      Set<String> merged = new LinkedHashSet<>();
      deduped.forEach(sub -> merged.addAll(sub.queries()));
      List<String> queries =
          new ArrayList<>(merged).subList(0, Math.min(merged.size(), queryLimit));
      events.add("分解已关闭，把 " + deduped.size() + " 个子问题合并为 1 个");
      deduped =
          List.of(
              new QueryUnderstanding.SubQuestion(
                  originalQuestion != null && !originalQuestion.isBlank()
                      ? originalQuestion
                      : deduped.getFirst().question(),
                  List.copyOf(queries)));
    }

    if (deduped.isEmpty()) {
      return new Parsed(
          QueryUnderstanding.single(originalQuestion), truncated, events, "解析后没有剩下任何子问题");
    }
    return new Parsed(new QueryUnderstanding(List.copyOf(deduped)), truncated, events, null);
  }

  // ────────────────────────── 解析 ──────────────────────────

  /** JSON 形状（含容错）。返回 null 表示"不像 JSON"。 */
  private static List<QueryUnderstanding.SubQuestion> parseJson(String raw, List<String> events) {
    String body = stripCodeFence(raw);
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (Exception e) {
      return null;
    }
    if (root == null) {
      return null;
    }

    JsonNode subQuestions = root.path("subQuestions");
    if (subQuestions.isArray()) {
      return readSubQuestions(subQuestions, events);
    }
    // 容错：顶层直接是一个数组（模型很常这么给）；元素既可能是对象，也可能直接是字符串
    if (root.isArray()) {
      if (root.isEmpty() || root.get(0).isObject()) {
        return readSubQuestions(root, events);
      }
      List<String> lines = new ArrayList<>();
      root.forEach(node -> lines.add(node.asText("")));
      return List.of(new QueryUnderstanding.SubQuestion(null, lines));
    }
    // 容错：只给了 queries（单意图、纯改写）
    JsonNode queries = root.path("queries");
    if (queries.isArray()) {
      List<String> values = new ArrayList<>();
      queries.forEach(node -> values.add(node.asText("")));
      events.add("输出里只有 queries（没有 subQuestions），按单意图处理");
      return List.of(new QueryUnderstanding.SubQuestion(null, values));
    }
    return null;
  }

  private static List<QueryUnderstanding.SubQuestion> readSubQuestions(
      JsonNode array, List<String> events) {
    List<QueryUnderstanding.SubQuestion> result = new ArrayList<>();
    for (JsonNode node : array) {
      if (node.isTextual()) {
        result.add(new QueryUnderstanding.SubQuestion(null, List.of(node.asText())));
        continue;
      }
      String question = node.path("question").asText(null);
      List<String> queries = new ArrayList<>();
      JsonNode queriesNode = node.path("queries");
      if (queriesNode.isArray()) {
        queriesNode.forEach(value -> queries.add(value.asText("")));
      } else if (queriesNode.isTextual()) {
        queries.add(queriesNode.asText());
      }
      result.add(new QueryUnderstanding.SubQuestion(question, queries));
    }
    if (result.isEmpty()) {
      events.add("subQuestions 是空数组");
    }
    return result;
  }

  /**
   * 按行解析（ROADMAP 第 12 周判据 ① 的"换行分隔"）。
   *
   * <p>去掉常见的列表装饰（{@code -} / {@code *} / {@code 1.} / {@code 1、} / 包裹的引号）， 空行丢弃。★ 不做"看起来像 JSON
   * 就跳过"的判断：调用方已经确认 JSON 解析失败了。
   */
  private static List<String> parseLines(String raw) {
    List<String> lines = new ArrayList<>();
    if (raw == null) {
      return lines;
    }
    for (String line : raw.split("\\r?\\n")) {
      String value = line.strip();
      value = value.replaceFirst("^[-*•]+\\s*", "");
      value = value.replaceFirst("^\\d+[.、)]\\s*", "");
      value = value.replaceFirst("^[\"“]|[\"”]$", "");
      value = value.strip();
      if (!value.isEmpty()) {
        lines.add(value);
      }
    }
    return lines;
  }

  /** 去掉 ```json 围栏与前后废话（模型最常见的两种包装）。 */
  private static String stripCodeFence(String raw) {
    if (raw == null) {
      return "";
    }
    String text = raw.strip();
    int fence = text.indexOf("```");
    if (fence >= 0) {
      int start = text.indexOf('\n', fence);
      int end = text.lastIndexOf("```");
      if (start > 0 && end > start) {
        text = text.substring(start + 1, end).strip();
      }
    }
    // 再砍掉 JSON 之外的前后废话：从第一个 { 或 [ 到与之配平的最后一个 } 或 ]
    int objectStart = text.indexOf('{');
    int arrayStart = text.indexOf('[');
    int start =
        objectStart < 0
            ? arrayStart
            : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
    if (start > 0) {
      text = text.substring(start);
    }
    return text.strip();
  }

  // ────────────────────────── 小工具 ──────────────────────────

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String text(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.strip();
  }

  private static String abbreviate(String value) {
    return value.length() <= 12 ? value : value.substring(0, 12);
  }
}
