package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐追问（「猜你想问」）。
 *
 * <h3>为什么是**独立的一次调用**，而不是塞进回答那一轮</h3>
 *
 * <p>契约里 {@code artifact}（含 suggestions）必须落在 {@code delta*} 之后、{@code done} 之前。 若在那一轮里顺手生成，用户会**等完
 * 60 秒回答、再等 10~20 秒才看到"完成"**（1.5B 的生成速度）。 而追问是"锦上添花"，不该拖慢主回答。
 *
 * <p>⇒ 现在是：回答 {@code done} 之后，前端**另发一次**请求，后台用同一模型生成 3 条短问题。 生成慢一点无所谓（用户已经在读答案了），失败也无所谓（返回空数组，UI
 * 只是不显示 chips）。
 *
 * <h3>★ 落库而不是只返回</h3>
 *
 * <p>写进 {@code cv_message.artifacts}（JSON {@code {"suggestions":[...]}}）： 刷新会话后这些 chip
 * 还在。否则"刷新一次就消失"，与 {@code A2}（刷新不重置）的口径冲突。
 */
@Service
public class SuggestionService {

  private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

  /** 配置读不到时的默认条数（3 条是"刚好能选、又不会把人淹掉"的量）。 */
  private static final int DEFAULT_COUNT = 3;

  /** 条数上限。再多就不叫"推荐"、叫菜单了；也是 DB 约束那一档。 */
  private static final int MAX_COUNT = 10;

  /** 单条追问的字数上限（超过的丢掉 —— 模型偶尔会写出一整段）。 */
  private static final int MAX_LEN = 40;

  /** 回答只取前这么多字进 prompt：1.5B 的 window 很小，塞整篇会挤掉指令。 */
  private static final int ANSWER_CLIP = 800;

  /** 生成超时。它是一次"小任务"，不该占用主链路的时间预算。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final JdbcTemplate jdbc;
  private final ChatPort chat;
  private final ChatPolicyResolver policy;

  public SuggestionService(JdbcTemplate jdbc, ChatPort chat, ChatPolicyResolver policy) {
    this.jdbc = jdbc;
    this.chat = chat;
    this.policy = policy;
  }

  /**
   * 为一条**已生成完成**的助手消息生成推荐追问。
   *
   * @param messageId 助手消息 id
   * @return 追问列表（生成失败 ⇒ 空列表，绝不抛）
   */
  @Transactional
  public List<String> generate(UUID messageId) {
    // ★ 0 = 关闭：不调模型、不落库、界面上不出现那一排。
    //   用"一个数字"表达关闭，是为了不必为了这件事改代码 ——
    //   有些团队就是反感"AI 替我猜我想问什么"。
    int want = count();
    if (want <= 0) {
      return List.of();
    }
    Row row = load(messageId);
    if (row == null) {
      return List.of();
    }
    // 已经有就不重复生成（前端可能因为重试/切页重复请求）
    if (!row.suggestions().isEmpty()) {
      return row.suggestions();
    }
    UUID modelId = row.modelId();
    if (modelId == null) {
      return List.of();
    }
    List<String> generated;
    try {
      String raw = chat.complete(modelId, prompt(row.question(), row.answer(), want), TIMEOUT);
      // ★ 原始输出记 debug：1.5B 的输出格式不稳定，遇到"chips 数量不对"时看这一行就能判断
      //   是模型没按格式写，还是我们的解析吃掉了。
      log.debug("追问原始输出（消息 {}）：{}", messageId, raw);
      generated = parse(raw, want);
    } catch (RuntimeException failure) {
      // ★ 降级而不是报错：追问生成失败不该让用户在界面上看到一个"错误"，
      //   他刚刚拿到一条正常回答。
      log.warn("推荐追问生成失败（消息 {}）：{}", messageId, failure.getMessage());
      return List.of();
    }
    if (generated.isEmpty()) {
      return List.of();
    }
    persist(messageId, generated);
    return generated;
  }

  /**
   * 批量读**已落库**的追问（一次 SQL，不生成）。
   *
   * <p>★ 为什么批量而不是逐条：历史一页 20 条，逐条查就是 20 次往返； 而这件事的收益只是"刷新后 chips 还在"，不值得 N+1。
   *
   * <p>★ 为什么历史读要单独走它、而不复用 {@link #generate}：{@code generate} 在没有存量时 会调模型 —— 打开一条老会话就触发一次 1.5B
   * 生成，是"读路径带写副作用"，不能接受。
   *
   * @param messageIds 消息 id
   * @return id → 追问列表（没有的不出现在 map 里）
   */
  public java.util.Map<UUID, List<String>> storedFor(java.util.Collection<UUID> messageIds) {
    if (messageIds == null || messageIds.isEmpty()) {
      return java.util.Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(messageIds.size(), "?"));
    java.util.Map<UUID, List<String>> out = new java.util.HashMap<>();
    jdbc.query(
        "select id, artifacts from cv_message where id in (" + placeholders + ")",
        rs -> {
          UUID id = rs.getObject("id", UUID.class);
          List<String> parsed = parseStored(rs.getString("artifacts"));
          if (!parsed.isEmpty()) {
            out.put(id, parsed);
          }
        },
        messageIds.toArray());
    return out;
  }

  /** 从 {@code {"suggestions":[...]}} 里取数组（宽容解析，坏掉即空）。 */
  static List<String> parseStored(String artifacts) {
    if (artifacts == null || artifacts.isBlank()) {
      return List.of();
    }
    int at = artifacts.indexOf("\"suggestions\"");
    if (at < 0) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(artifacts.substring(at));
    int seen = 0;
    while (m.find()) {
      seen++;
      if (seen == 1) {
        continue; // 第一个匹配是键名
      }
      out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
      if (out.size() >= MAX_COUNT) {
        break;
      }
    }
    return out;
  }

  /** 当前配置的条数（夹到 0..MAX_COUNT；读不到 ⇒ 默认 3）。 */
  private int count() {
    int configured = policy == null ? DEFAULT_COUNT : policy.suggestionCount();
    return Math.max(0, Math.min(MAX_COUNT, configured));
  }

  private String prompt(String question, String answer, int want) {
    String clipped = answer.length() > ANSWER_CLIP ? answer.substring(0, ANSWER_CLIP) : answer;
    String system = policy == null ? null : policy.systemPrompt();
    StringBuilder sb = new StringBuilder();
    if (system != null) {
      sb.append(system).append("\n\n");
    }
    // ★ 用**编号占位**而不是"请写 3 个"：实测 1.5B 对"写 3 个"经常只写 1 个，
    //   而给它 `1.` `2.` `3.` 三行开头，它会把三行都填上（模型对"续写"远比对"计数"可靠）。
    sb.append("根据下面这一轮问答，按编号写出 ")
        .append(want)
        .append(" 个用户可能继续追问的问题，每个问题不超过 20 个字。")
        .append("\n直接输出这 ")
        .append(want)
        .append(" 行，不要解释、不要引号、不要写「问：」：");
    // ★ 用编号占位而不是"请写 N 个"：实测 1.5B 对"写 3 个"经常只写 1 个，
    //   而给它每行编号开头，它会把每行都填上（模型对"续写"远比对"计数"可靠）。
    for (int i = 1; i <= want; i++) {
      sb.append("\n").append(i).append(".");
    }
    sb.append("\n\n问：").append(question).append("\n答：").append(clipped);
    return sb.toString();
  }

  /**
   * 解析模型输出。
   *
   * <p>★ 宽容解析：实测 1.5B 会干这三件事 —— 带「问：」前缀、把多个问题塞在**一行**里、 带编号或引号。逐行清洗只能解决前一种，所以这里先按行切、每条再按问号切，
   * 任何一条不合法就跳过，而不是"格式不对就整体丢弃"（那会让 chips 直接不出现）。
   */
  static List<String> parse(String raw, int want) {
    List<String> out = new ArrayList<>(want);
    if (raw == null) {
      return out;
    }
    // 去掉行首的「问：」「问题：」「追问：」——它们常常和问题写在同一行
    String cleaned = raw.replaceAll("(?m)^\\s*(?:追问|问题|问)\\s*[：:]\\s*", "");
    for (String line : cleaned.split("\\r?\\n")) {
      // ★ 一行里可能塞了多个问题（模型常这么干）⇒ 在每个问号后就地切开
      for (String piece : line.split("(?<=[？?])")) {
        String s = piece.strip();
        // 去掉行首编号：1. / 1、 / - / * / • / ①
        s = s.replaceFirst("^[\\d一二三四五六]+[.、)．]\\s*", "");
        s = s.replaceFirst("^[-*•·①-⑳]\\s*", "");
        s = s.replaceFirst("^(?:追问|问题|问)\\s*[：:]\\s*", "");
        // 去掉包裹的引号/书名号
        s = s.replaceAll("^[\"“'『「]+|[\"”'」』]+$", "").strip();
        if (s.isEmpty() || s.length() > MAX_LEN) {
          continue;
        }
        // 明显不是问题的（"以下是…"、"希望…"）跳过
        if (s.startsWith("以下") || s.startsWith("希望") || s.endsWith("：")) {
          continue;
        }
        if (!out.contains(s)) {
          out.add(s);
        }
        if (out.size() >= want) {
          return out;
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Row load(UUID messageId) {
    List<Row> rows =
        jdbc.query(
            "select m.model_id, m.artifacts, m.content, (select u.content from cv_message u   where"
                + " u.conversation_id = m.conversation_id and u.role = 'USER'     and u.created_at"
                + " <= m.created_at order by u.created_at desc limit 1) as question from cv_message"
                + " m where m.id = ?",
            (rs, i) ->
                new Row(
                    rs.getObject("model_id", UUID.class),
                    rs.getString("artifacts"),
                    rs.getString("content"),
                    rs.getString("question")),
            messageId);
    if (rows.isEmpty()) {
      return null;
    }
    Row raw = rows.get(0);
    return new Row(raw.modelId(), raw.artifacts(), raw.answer(), raw.question());
  }

  private void persist(UUID messageId, List<String> suggestions) {
    String json =
        "{\"suggestions\":["
            + String.join(",", suggestions.stream().map(SuggestionService::quote).toList())
            + "]}";
    // ★ `artifacts` 是 jsonb：不显式转型会报
    //   "column artifacts is of type jsonb but expression is of type character varying"
    jdbc.update(
        "update cv_message set artifacts = ?::jsonb, updated_at = now() where id = ?",
        json,
        messageId);
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** 一行原始数据。 */
  private record Row(UUID modelId, String artifacts, String answer, String question) {
    /** 已落库的追问（解析失败 ⇒ 空）。 */
    List<String> suggestions() {
      if (artifacts == null || artifacts.isBlank()) {
        return List.of();
      }
      int at = artifacts.indexOf("\"suggestions\"");
      if (at < 0) {
        return List.of();
      }
      List<String> out = new ArrayList<>();
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"")
              .matcher(artifacts.substring(at));
      int seen = 0;
      while (m.find()) {
        seen++;
        if (seen == 1) {
          continue; // 第一个匹配是键名 "suggestions"
        }
        out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        // ★ 存量的条数由当初生成时决定，这里只按"业务上限"兜底，不再用配置值 ——
        //   否则用户把条数从 5 调到 3 之后，老消息里的 5 条会被截成 3 条（改了历史）。
        if (out.size() >= MAX_COUNT) {
          break;
        }
      }
      return out;
    }
  }
}
