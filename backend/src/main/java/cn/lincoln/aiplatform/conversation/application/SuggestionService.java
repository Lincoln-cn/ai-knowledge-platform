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

  /**
   * 回答只取前这么多字进 prompt。
   *
   * <p>★ 从 800 降到 400（2026-10-18 实测）：喂太长的回答时 1.5B 会只写 1 条就「满足」了， 而追问需要的是「这段在讲什么」，不是全文。
   */
  private static final int ANSWER_CLIP = 400;

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
    // ★ 开关关掉 ⇒ 不调模型、不落库、界面上不出现那一排。
    //   `count()` 返回 0 就是这个信号（见它的 javadoc）。
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
    List<String> generated = new ArrayList<>();
    try {
      String raw = chat.complete(modelId, prompt(row.question(), row.answer(), want), TIMEOUT);
      // ★ 原始输出记 debug：1.5B 的输出格式不稳定，遇到"chips 数量不对"时看这一行就能判断
      //   是模型没按格式写，还是我们的解析吃掉了。
      log.debug("追问原始输出（消息 {}）：{}", messageId, raw);
      generated.addAll(parse(raw, want));
      // ★★ 补齐：1.5B 经常只写 1 条（同一个 prompt 时好时坏）。
      //   差几条就再要一次，并把它已经写过的告诉它别重复 —— 最多补一次，
      //   所以最坏情况是两次模型调用，不会变成 N 次。
      if (generated.size() < want) {
        int missing = want - generated.size();
        String more =
            chat.complete(
                modelId, topUpPrompt(row.question(), row.answer(), missing, generated), TIMEOUT);
        log.debug("追问补齐原始输出（消息 {}）：{}", messageId, more);
        for (String extra : parse(more, missing)) {
          if (!generated.contains(extra)) {
            generated.add(extra);
          }
        }
      }
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

  /**
   * 「补齐」用的 prompt：只要差的那几条，并明确告诉它已有的别重复。
   *
   * <p>★ 为什么不复用 {@link #prompt}：复用会让模型**重新写一遍全部**，然后我们再剔除重复 —— 既慢又容易和已有的撞车。把「已经写过什么」直接写进 prompt
   * 是这类补齐调用里最省事的做法。
   *
   * @param question 本轮问题
   * @param answer 本轮回答
   * @param missing 还差几条
   * @param existing 已经生成的
   * @return prompt
   */
  private String topUpPrompt(String question, String answer, int missing, List<String> existing) {
    String clipped = stripCode(answer);
    if (clipped.length() > ANSWER_CLIP) {
      clipped = clipped.substring(0, ANSWER_CLIP);
    }
    StringBuilder sb = new StringBuilder();
    String system = policy == null ? null : policy.systemPrompt();
    if (system != null) {
      sb.append(system).append("\n\n");
    }
    sb.append("下面这一轮问答已经生成过这些问题：\n");
    for (String s : existing) {
      sb.append("- ").append(s).append('\n');
    }
    sb.append("请再写出 ")
        .append(missing)
        .append(" 个**不同**的、用户可能继续追问的问题。")
        .append("\n要求：每个问题不超过 20 个字；用 1. 2. 3. 这样的编号开头；")
        .append("每个问题单独一行；不要和上面重复；不要解释。")
        .append("\n\n问：")
        .append(question)
        .append("\n答：")
        .append(clipped)
        .append("\n\n请输出这 ")
        .append(missing)
        .append(" 个问题：");
    return sb.toString();
  }

  /**
   * 当前配置的条数。
   *
   * <p>★ 开关与条数是两件事：开关关掉时**不调模型**（而不是"调了模型再丢掉"）， 所以这里返回 0 是给 {@link #generate} 当"别干活"的信号。
   *
   * @return 0 = 关闭；否则 1..MAX_COUNT（读不到配置 ⇒ DEFAULT_COUNT）
   */
  private int count() {
    if (policy != null && !policy.suggestionEnabled()) {
      return 0;
    }
    int configured = policy == null ? DEFAULT_COUNT : policy.suggestionCount();
    // ★ 下限是 1 不是 0：开了开关却配出 0 条是没有意义的组合（DB 约束也是 1..10）
    return Math.max(1, Math.min(MAX_COUNT, configured));
  }

  /**
   * 切掉回答里的代码块，再截断。
   *
   * <p>★ 实测（2026-10-18）：回答里有一大段 CSS/SFC 代码时，1.5B 会把代码片段本身当追问吐出来 —— 实际拿到的是 {@code 圆角边框: 5px;} /
   * {@code </style>} / {@code <script type="text/plain">}。 那段代码对"下一步想问什么"毫无信息量，只会把模型带偏 ⇒ 干脆不给它。
   *
   * @param answer 回答全文
   * @return 去掉围栏代码块后的文本（找不到围栏 ⇒ 原样返回）
   */
  private static String stripCode(String answer) {
    String out = answer.replaceAll("(?s)```.*?```", " ");
    // 还留着行内代码块（`x`）不切 —— 它常常是关键词（比如 `computed`），对生成追问有用
    return out.strip();
  }

  private String prompt(String question, String answer, int want) {
    String clipped = stripCode(answer);
    if (clipped.length() > ANSWER_CLIP) {
      clipped = clipped.substring(0, ANSWER_CLIP);
    }
    String system = policy == null ? null : policy.systemPrompt();
    StringBuilder sb = new StringBuilder();
    if (system != null) {
      sb.append(system).append("\n\n");
    }
    // ★★★ 格式选择的实测依据（2026-10-18，同一轮问答，走平台的 chat 端点）：
    //
    //   ① 把「1. / 2. / 3.」当**占位符放在 prompt 尾部** ⇒ 模型把它理解成「已经写到 3 了，
    //      我接着写 4」，输出被污染成「答：单文件组件如何使用？」这种，且条数不够。
    //   ② 用「1. <问题>」模板占位 ⇒ 4 行干净。
    //   ③ **把编号写进指令、不占位** ⇒ 4 行干净 ← 现在用这个。
    //
    //   ★ 判据：给模型的"格式示范"如果长得像**已完成的内容**，小模型会去续写它而不是填它。
    sb.append("你是问答助手。请根据下面这一轮问答，写出 ")
        .append(want)
        .append(" 个用户可能继续追问的问题。")
        .append("\n要求：每个问题不超过 20 个字；用 1. 2. 3. 这样的编号开头；")
        .append("每个问题单独一行；不要解释、不要引号、不要写「问：」「答：」。")
        .append("\n\n问：")
        .append(question)
        .append("\n答：")
        .append(clipped)
        .append("\n\n请输出这 ")
        .append(want)
        .append(" 个问题：");
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
        s = s.replaceFirst("^(?:追问|问题|问|答|回答|A|Q)\\s*[：:]\\s*", "");
        // 去掉包裹的引号/书名号
        s = s.replaceAll("^[\"“'『「]+|[\"”'」』]+$", "").strip();
        if (s.isEmpty() || s.length() > MAX_LEN) {
          continue;
        }
        // 明显不是问题的（"以下是…"、"希望…"）跳过
        if (s.startsWith("以下") || s.startsWith("希望") || s.endsWith("：")) {
          continue;
        }
        // ★★ 只收"像问题"的行。
        //   实测 1.5B 会混进代码残片（`圆角边框: 5px;` / `}` / `</style>`），
        //   它们长度合法、前缀也不命中任何规则 ⇒ 光靠前缀清洗挡不住。
        //   判据：以问号结尾，或以疑问词开头。宁可少一条，也不要一排看不懂的碎片。
        if (!looksLikeQuestion(s)) {
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

  /** 疑问词开头四种语言习惯各取一个，覆盖中文提问的主要写法。 */
  private static final String[] INTERROGATIVES = {
    "如何", "怎么", "怎样", "什么", "为什么", "为何", "是否", "能否", "可以", "哪些", "哪个", "多少", "有没有"
  };

  /**
   * 这一行像不像一个"用户会问的问题"。
   *
   * @param s 已清洗过的候选行
   * @return true = 收
   */
  private static boolean looksLikeQuestion(String s) {
    if (s.endsWith("？") || s.endsWith("?")) {
      return true;
    }
    for (String word : INTERROGATIVES) {
      if (s.startsWith(word)) {
        return true;
      }
    }
    return false;
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
