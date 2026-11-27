package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型可调参数的<strong>声明</strong>（{@code #371} 第 135 条 · 0.9.8 §11.65.3 ③c）。
 *
 * <h3>★★ 它解决的是"表单能填，但填了没用"</h3>
 *
 * <p>此前 AI 节点的模型参数弹窗里 {@code getApi} 直接返回空数组，界面只能显示空表单 —— 而"空表单"与"这个模型没有
 * 可调参数"在界面上长得一模一样，用户分不出来。这一层补的是<strong>声明</strong>：某个模型暴露了哪些参数、类型是什么、取值范围多大。
 *
 * <p>★★ 但声明本身<strong>不是能力</strong>：真正的判据是"这些值有没有进入上游请求"（见 {@code ChatPort#complete} 的 {@code
 * params} 参数与 {@code ChatModelAdapter} 的映射）。只渲染表单、只落库、调用时不带， 比空表单更糟 —— 用户以为调了，其实没生效，而且没人会去核对第二次。
 *
 * <h3>★ 哪些 key 走"官方项"</h3>
 *
 * <p>{@code temperature} / {@code top_p}（或 {@code topP}）/ {@code max_tokens}（或 {@code
 * maxTokens}）这四个是各家 OpenAI 兼容实现都认的采样参数，走 Spring AI 的 {@code ChatOptions} 官方字段；其余 key 原样塞进 {@code
 * extraBody} —— 后者只在供应商真的认这个字段时有用，所以界面上要如实标注"原样透传给上游"。
 */
public final class ModelParamSchema {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 一道模型最多声明多少条参数（与断言的上限同口径：超过就不是"可调"，是"把整张请求体搬进表单"）。 */
  public static final int MAX_PARAMS = 20;

  /** 参数类型（存成字符串，便于 JSON 与前端共用；不存枚举序号）。 */
  public static final Set<String> TYPES =
      new LinkedHashSet<>(List.of("number", "integer", "boolean", "enum", "string"));

  /** 走 Spring AI {@code ChatOptions} 官方字段的四个 key（一律小写下划线形式比较）。 */
  private static final Set<String> OFFICIAL_KEYS =
      new LinkedHashSet<>(List.of("temperature", "top_p", "max_tokens"));

  private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");

  /**
   * 一条参数声明。
   *
   * @param key 上游认的字段名（{@code temperature} / {@code top_k} / …），ASCII 标识符
   * @param label 人看的名字；空则界面回退到 {@code key}
   * @param type 五种之一：{@code number} / {@code integer} / {@code boolean} / {@code enum} / {@code
   *     string}
   * @param defaultValue 默认值；{@code null} 表示没有默认（界面留空）
   * @param min 数值下限（只对 {@code number} / {@code integer} 有意义）
   * @param max 数值上限
   * @param options {@code enum} 的候选值
   * @param help 一句话说明（会显示在输入框下方）
   */
  public record Param(
      String key,
      String label,
      String type,
      Object defaultValue,
      Double min,
      Double max,
      List<String> options,
      String help) {}

  private ModelParamSchema() {}

  /** 解析存库的 JSON；空/坏数据一律当"没有声明"，不抛 —— 读路径不该因为一条脏数据把整页打挂。 */
  public static List<Param> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      Param[] parsed = JSON.readValue(raw, Param[].class);
      return parsed == null ? List.of() : List.of(parsed);
    } catch (RuntimeException broken) {
      return List.of();
    }
  }

  /**
   * 与 {@link #parse} 相同，但<strong>坏数据抛异常</strong>。
   *
   * <p>★ 为什么要有两个入口：读路径（把已存的声明交给界面）不该因为一条脏数据把整页打挂， 所以 {@code parse} 容错成空数组；写路径（管理员保存）必须让"这串不是合法
   * JSON"当场报出来， 否则保存"成功"、界面上却是空表单 —— 又一次"收下了但没用"。
   */
  public static List<Param> parseStrict(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      Param[] parsed = JSON.readValue(raw, Param[].class);
      return parsed == null ? List.of() : List.of(parsed);
    } catch (RuntimeException broken) {
      throw new IllegalArgumentException("参数声明不是合法的 JSON 数组：" + broken.getMessage());
    }
  }

  /** 序列化；{@code null} 与空集合都存成 {@code []}（列是 not null，且"没有"必须能被读出来）。 */
  public static String write(List<Param> params) {
    if (params == null || params.isEmpty()) {
      return "[]";
    }
    List<Param> normalized = new ArrayList<>(params.size());
    for (Param p : params) {
      normalized.add(
          new Param(
              strip(p.key()),
              strip(p.label()),
              strip(p.type()) == null ? null : strip(p.type()).toLowerCase(Locale.ROOT),
              p.defaultValue(),
              p.min(),
              p.max(),
              p.options() == null
                  ? List.of()
                  : p.options().stream().map(ModelParamSchema::strip).toList(),
              strip(p.help())));
    }
    return JSON.writeValueAsString(normalized);
  }

  /**
   * 校验一份声明，不合法就抛 {@link IllegalArgumentException}（调用方翻成 400）。
   *
   * <p>★ 文案带"第 N 条参数"：用户改的是一张表，只说"参数不合法"他找不到是哪一行。
   */
  public static void validate(List<Param> params) {
    if (params == null || params.isEmpty()) {
      return;
    }
    if (params.size() > MAX_PARAMS) {
      throw new IllegalArgumentException("一个模型最多声明 " + MAX_PARAMS + " 条可调参数");
    }
    Set<String> seen = new LinkedHashSet<>();
    for (int i = 0; i < params.size(); i++) {
      Param p = params.get(i);
      String at = "第 " + (i + 1) + " 条参数";
      String key = strip(p.key());
      if (key == null) {
        throw new IllegalArgumentException(at + "：字段名不能为空");
      }
      if (!KEY.matcher(key).matches()) {
        throw new IllegalArgumentException(at + "：「" + key + "」不是合法的字段名（字母开头，只含字母、数字、下划线，≤40 字符）");
      }
      if (!seen.add(key.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(at + "：字段名「" + key + "」重复了");
      }
      String type = strip(p.type()) == null ? "" : strip(p.type()).toLowerCase(Locale.ROOT);
      if (!TYPES.contains(type)) {
        throw new IllegalArgumentException(
            at + "：类型「" + p.type() + "」不认识（可选 " + String.join(" / ", TYPES) + "）");
      }
      switch (type) {
        case "number", "integer" -> validateNumber(at, type, p);
        case "boolean" -> validateBoolean(at, p);
        case "enum" -> validateEnum(at, p);
        default -> {
          // string：没有额外约束
        }
      }
    }
  }

  private static void validateNumber(String at, String type, Param p) {
    Double min = p.min();
    Double max = p.max();
    if (min != null && max != null && min > max) {
      throw new IllegalArgumentException(at + "：最小值 " + min + " 大于最大值 " + max);
    }
    if ("integer".equals(type)
        && (isFractional(min) || isFractional(max) || isFractional(p.defaultValue()))) {
      throw new IllegalArgumentException(at + "：整数参数的默认值与上下限都必须是整数");
    }
    Double value = asDouble(p.defaultValue());
    if (p.defaultValue() != null && value == null) {
      throw new IllegalArgumentException(at + "：默认值「" + p.defaultValue() + "」不是数字");
    }
    if (value != null) {
      if (min != null && value < min) {
        throw new IllegalArgumentException(at + "：默认值 " + value + " 小于最小值 " + min);
      }
      if (max != null && value > max) {
        throw new IllegalArgumentException(at + "：默认值 " + value + " 大于最大值 " + max);
      }
    }
  }

  private static void validateBoolean(String at, Param p) {
    if (p.defaultValue() != null && !(p.defaultValue() instanceof Boolean)) {
      throw new IllegalArgumentException(at + "：布尔参数的默认值只能是 true / false");
    }
  }

  private static void validateEnum(String at, Param p) {
    List<String> options = p.options();
    if (options == null || options.isEmpty()) {
      throw new IllegalArgumentException(at + "：枚举参数必须给出候选值");
    }
    if (p.defaultValue() != null && !options.contains(String.valueOf(p.defaultValue()))) {
      throw new IllegalArgumentException(
          at + "：默认值「" + p.defaultValue() + "」不在候选值里（" + String.join(" / ", options) + "）");
    }
  }

  /** 这个 key 是不是走 Spring AI 官方字段（大小写与下划线/驼峰都兼容）。 */
  public static boolean isOfficial(String key) {
    return key != null && OFFICIAL_KEYS.contains(normalizeOfficial(key));
  }

  /**
   * 统一成官方字段的名字：{@code topP} / {@code top-p} → {@code top_p}，{@code maxTokens} → {@code max_tokens}。
   */
  public static String normalizeOfficial(String key) {
    String k = key.replace("-", "_");
    StringBuilder out = new StringBuilder();
    for (char c : k.toCharArray()) {
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    String lowered = out.toString();
    return lowered.startsWith("_") ? lowered.substring(1) : lowered;
  }

  /**
   * 把"界面收上来的值"整理成两类：走官方字段的、原样透传的。
   *
   * <p>★ 为什么要有这一步而不是直接把 Map 塞给适配器：官方字段有<strong>类型</strong>（温度是 float、maxTokens 是 Integer），而 JSON
   * 里拿到的是 {@code Number}/{@code String}；不在这里归一，适配器就要在每个实现里各写一遍 "把 Object 转成正确类型"，且转错了只会得到一个上游 400。
   *
   * @param values 调用方给的值（key → 值），{@code null} 视为空
   * @return 两段：{@code official} 与 {@code extra}（都是有序的，便于测试与日志）
   */
  public static Split split(Map<String, Object> values) {
    Map<String, Object> official = new LinkedHashMap<>();
    Map<String, Object> extra = new LinkedHashMap<>();
    if (values != null) {
      values.forEach(
          (key, value) -> {
            if (key == null || key.isBlank() || value == null) {
              return;
            }
            if (isOfficial(key)) {
              official.put(normalizeOfficial(key), value);
            } else {
              extra.put(key, value);
            }
          });
    }
    return new Split(official, extra);
  }

  /** {@link #split} 的结果：官方字段（已归一名字）与透传字段分开。 */
  public record Split(Map<String, Object> official, Map<String, Object> extra) {}

  /** 官方字段里的浮点值（{@code null} = 没给或不是数）。 */
  public static Float floatOf(Object value) {
    Double d = asDouble(value);
    return d == null ? null : d.floatValue();
  }

  /** 官方字段里的整数值（{@code null} = 没给或不是整数）。 */
  public static Integer intOf(Object value) {
    Double d = asDouble(value);
    if (d == null || d != Math.rint(d)) {
      return null;
    }
    return d.intValue();
  }

  private static Double asDouble(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException notANumber) {
        return null;
      }
    }
    return null;
  }

  private static boolean isFractional(Number value) {
    return value != null && value.doubleValue() != Math.rint(value.doubleValue());
  }

  private static boolean isFractional(Object value) {
    Double d = asDouble(value);
    return d != null && d != Math.rint(d);
  }

  private static String strip(String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
