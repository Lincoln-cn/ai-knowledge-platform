package cn.lincoln.aiplatform.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 第 1 层机械断言：把角色设定里「必须怎样」的要求转成可机器判的条目（ROADMAP §11.63.2）。
 *
 * <p>★ 这一层只覆盖<strong>形式</strong>：有没有标出第几条、有没有说"不确定"、有没有分点、多少字。 语气是否得体、判断是否符合业务口径， 这一层测不了 ——
 * 所以它不是一个"提示词质量分"，只能单独看通过率。
 *
 * <p>★ 判定<strong>不调用模型</strong>（零成本、可离线、可进 CI）：只看答案文本。所以这里全是纯函数， 没有依赖、没有事务，单测直接跑。
 *
 * <p>★ 两种"零"必须分得开：断言列表为空 ⇒ {@link #check} 返回空列表，调用方据此显示「未设断言」； 断言列表非空且全过 ⇒ 返回的每条 {@code
 * passed=true}，通过数等于断言数。把前者显示成「0 通过 0 失败」 就等于把"没测"说成"测了且都好"。
 */
public final class PromptAssertions {

  /** 一类断言只比一件事 —— 混在一起就无法从读数里定位到"哪条要求没被满足"。 */
  public enum Kind {
    /** 必须出现这段文字（原样子串）。 */
    CONTAINS,
    /** 不许出现这段文字（比如禁止出现"无法回答"这种敷衍话）。 */
    NOT_CONTAINS,
    /** 必须匹配这条正则（找得到即通过，不要求整段匹配）。 */
    REGEX,
    /** 字符数不超过 N（按 strip 后的长度算）。 */
    MAX_CHARS,
    /** 字符数不少于 N。 */
    MIN_CHARS,
    /** 必须分点输出（至少 2 个列表项）。 */
    LIST,
    /** 必须表达不确定（"没有提到 / 无法确定"这类），用于"不知道就说不知道"的设定。 */
    UNCERTAIN
  }

  /**
   * 一道题上的一条断言。{@code value} 按 {@link Kind} 解释；{@code label} 是给人看的原话。
   *
   * <p>★ {@code id} 用字符串而不是 {@code UUID}：前端新增一条时它还不知道 id（传空串最自然）， 而 Jackson 把 {@code ""} 反序列化成
   * {@code UUID} 会直接抛 400 —— 让"新增"变成一次能预料失败的请求。
   */
  public record Def(String id, String label, Kind kind, String value) {}

  /** 一条断言的判定结果。{@code detail} 要能单独读明白（"没有出现「第七条」"），而不是只给 true/false。 */
  public record Result(
      String assertionId, String label, Kind kind, boolean passed, String detail) {}

  /** 单题断言上限：超过这个数，人不会逐条写、也不会逐条看。 */
  public static final int MAX_PER_ITEM = 20;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<String> UNCERTAIN_WORDS =
      List.of(
          "没有提到", "未提到", "未提及", "没有提及", "无法确定", "不确定", "无法回答", "不能确定", "不知道", "未涉及", "没有涉及", "未找到",
          "没有找到", "没有相关", "无相关", "资料未", "无法判断", "未见", "未包含");

  /** 列表项：`- x` / `* x` / `• x` / `1. x` / `1、x` / `(1) x`。 */
  private static final Pattern LIST_LINE =
      Pattern.compile("^\\s*(?:[-*•]\\s+|\\d+\\s*[.、)）]\\s+|\\(\\d+\\)\\s*)\\S", Pattern.MULTILINE);

  private PromptAssertions() {}

  /** 逐条判。{@code answer} 为 null 按空串判（答案为空时：必须出现的都算失败，必须不出现的都算通过）。 */
  public static List<Result> check(String answer, List<Def> defs) {
    if (defs == null || defs.isEmpty()) {
      return List.of();
    }
    String text = answer == null ? "" : answer;
    String stripped = text.strip();
    List<Result> out = new ArrayList<>(defs.size());
    for (Def def : defs) {
      out.add(checkOne(text, stripped, def));
    }
    return out;
  }

  private static Result checkOne(String text, String stripped, Def def) {
    if (def == null || def.kind() == null) {
      return new Result(
          def == null ? null : def.id(),
          def == null ? null : def.label(),
          def == null ? null : def.kind(),
          false,
          "断言定义不完整（缺判定类型）");
    }
    String value = def.value() == null ? "" : def.value();
    String label = labelOr(def);
    return switch (def.kind()) {
      case CONTAINS -> {
        boolean ok = !value.isBlank() && text.contains(value);
        yield new Result(
            def.id(), label, def.kind(), ok, ok ? "出现了「" + value + "」" : "没有出现「" + value + "」");
      }
      case NOT_CONTAINS -> {
        boolean ok = value.isBlank() || !text.contains(value);
        yield new Result(
            def.id(),
            label,
            def.kind(),
            ok,
            ok ? "没有出现「" + value + "」" : "不该出现「" + value + "」，但出现了");
      }
      case REGEX -> {
        try {
          boolean ok = Pattern.compile(value).matcher(text).find();
          yield new Result(
              def.id(), label, def.kind(), ok, ok ? "匹配到 /" + value + "/" : "没有匹配 /" + value + "/");
        } catch (PatternSyntaxException e) {
          // 正则写错是"定义错了"，不是"答案不合格"——要能一眼看出来，否则会去反复改提示词
          yield new Result(def.id(), label, def.kind(), false, "正则写错了：" + e.getDescription());
        }
      }
      case MAX_CHARS -> {
        int limit = number(value);
        int len = stripped.length();
        // 上限写成非整数是"定义错了"，与"答案太长"必须分开报：否则会去反复改提示词
        yield limit <= 0
            ? new Result(def.id(), label, def.kind(), false, "上限写错了：「" + value + "」不是正整数")
            : new Result(def.id(), label, def.kind(), len <= limit, "字数 " + len + " / 上限 " + limit);
      }
      case MIN_CHARS -> {
        int limit = number(value);
        int len = stripped.length();
        yield limit <= 0
            ? new Result(def.id(), label, def.kind(), false, "下限写错了：「" + value + "」不是正整数")
            : new Result(def.id(), label, def.kind(), len >= limit, "字数 " + len + " / 下限 " + limit);
      }
      case LIST -> {
        long count = LIST_LINE.matcher(text).results().count();
        yield new Result(def.id(), label, def.kind(), count >= 2, "列表项 " + count + " 个（至少 2 个）");
      }
      case UNCERTAIN -> {
        String hit = UNCERTAIN_WORDS.stream().filter(text::contains).findFirst().orElse(null);
        yield new Result(
            def.id(),
            label,
            def.kind(),
            hit != null,
            hit != null ? "说了「" + hit + "」" : "没有出现「没有提到 / 无法确定」这类表述");
      }
    };
  }

  /** 断言的显示文本：人写的优先，没写就按类型给一句默认的。 */
  public static String labelOr(Def def) {
    if (def == null) {
      return "";
    }
    String label = def.label() == null ? "" : def.label().strip();
    if (!label.isEmpty()) {
      return label;
    }
    String value = def.value() == null ? "" : def.value().strip();
    return switch (def.kind()) {
      case CONTAINS -> "必须出现「" + value + "」";
      case NOT_CONTAINS -> "不许出现「" + value + "」";
      case REGEX -> "必须匹配 /" + value + "/";
      case MAX_CHARS -> "不超过 " + value + " 字";
      case MIN_CHARS -> "不少于 " + value + " 字";
      case LIST -> "要分点输出";
      case UNCERTAIN -> "不确定时要说出来";
    };
  }

  /**
   * 校验一条断言定义：类型必须能认、数值类必须给正整数、正则必须能编译。
   *
   * @throws IllegalArgumentException 校验失败（调用方翻译成面向用户的 400 文案）
   */
  public static void validate(Def def, int index) {
    String where = "第 " + (index + 1) + " 条断言";
    if (def == null || def.kind() == null) {
      throw new IllegalArgumentException(where + "没有判定类型");
    }
    String value = def.value() == null ? "" : def.value().strip();
    switch (def.kind()) {
      case CONTAINS, NOT_CONTAINS -> {
        if (value.isEmpty()) {
          throw new IllegalArgumentException(where + "要写上「必须出现 / 不许出现」的内容");
        }
      }
      case REGEX -> {
        if (value.isEmpty()) {
          throw new IllegalArgumentException(where + "要写一条正则");
        }
        try {
          Pattern.compile(value);
        } catch (PatternSyntaxException e) {
          throw new IllegalArgumentException(where + "的正则写错了：" + e.getDescription());
        }
      }
      case MAX_CHARS, MIN_CHARS -> {
        int n = number(value);
        if (n <= 0) {
          throw new IllegalArgumentException(where + "的字数要是一个正整数");
        }
      }
      case LIST, UNCERTAIN -> {
        // 不需要参数
      }
    }
  }

  private static int number(String value) {
    try {
      return Integer.parseInt(value == null ? "" : value.strip());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // ── 落库的 JSON 形状 ────────────────────────────────────────────
  //
  // ★ 手写节点而不是让 Jackson 反射 record：落库的字段名是**对外契约**（前端读同一套名字），
  //   显式写出来以后改 Java 字段名不会悄悄改契约。

  public static List<Def> parseDefs(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = JSON.readTree(json);
      if (root == null || !root.isArray()) {
        return List.of();
      }
      List<Def> out = new ArrayList<>();
      for (JsonNode n : root) {
        Kind kind = kindOf(n.path("kind").asText(""));
        if (kind == null) {
          continue;
        }
        out.add(
            new Def(
                textOf(n.path("id").asText("")),
                n.path("label").asText(""),
                kind,
                n.path("value").asText("")));
      }
      return out;
    } catch (JacksonException e) {
      // 库里的 JSON 坏了不该让整个列表读不出来：当作"没设断言"，页面仍能打开
      return List.of();
    }
  }

  public static String writeDefs(List<Def> defs) {
    ArrayNode arr = JSON.createArrayNode();
    if (defs != null) {
      for (Def def : defs) {
        if (def == null || def.kind() == null) {
          continue;
        }
        ObjectNode n = arr.addObject();
        n.put("id", def.id() == null ? UUID.randomUUID().toString() : def.id().toString());
        n.put("label", def.label() == null ? "" : def.label());
        n.put("kind", def.kind().name());
        n.put("value", def.value() == null ? "" : def.value());
      }
    }
    return JSON.writeValueAsString(arr);
  }

  public static List<Result> parseResults(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = JSON.readTree(json);
      if (root == null || !root.isArray()) {
        return List.of();
      }
      List<Result> out = new ArrayList<>();
      for (JsonNode n : root) {
        out.add(
            new Result(
                textOf(n.path("assertionId").asText("")),
                n.path("label").asText(""),
                kindOf(n.path("kind").asText("")),
                n.path("passed").asBoolean(false),
                n.path("detail").asText("")));
      }
      return out;
    } catch (JacksonException e) {
      return List.of();
    }
  }

  public static String writeResults(List<Result> results) {
    ArrayNode arr = JSON.createArrayNode();
    if (results != null) {
      for (Result r : results) {
        if (r == null) {
          continue;
        }
        ObjectNode n = arr.addObject();
        n.put("assertionId", r.assertionId() == null ? "" : r.assertionId().toString());
        n.put("label", r.label() == null ? "" : r.label());
        n.put("kind", r.kind() == null ? "" : r.kind().name());
        n.put("passed", r.passed());
        n.put("detail", r.detail() == null ? "" : r.detail());
      }
    }
    return JSON.writeValueAsString(arr);
  }

  public static int passedCount(List<Result> results) {
    return (int) results.stream().filter(Result::passed).count();
  }

  public static int failedCount(List<Result> results) {
    return (int) results.stream().filter(r -> !r.passed()).count();
  }

  private static Kind kindOf(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return Kind.valueOf(name.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 读回一个 id：空串按"没有"处理，交给上游补。★ 不按 UUID 解析 —— 解析失败会把 id 静默吞掉。 */
  private static String textOf(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }
}
