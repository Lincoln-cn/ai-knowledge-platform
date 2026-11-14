package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 平台内置函数表。
 *
 * <h3>为什么是这一组</h3>
 *
 * <p>编排里真正天天要用的只有几件事：<strong>取值、拼接、截断、替换、拆分、兜底、从 JSON 里取一格</strong>。
 * 这些用不着脚本语言，而它们的共同特点是「输入一段文本、输出一段文本」—— 正好落在 {@link FunctionInvokePort} 的形状里。
 *
 * <p>★ 没有 {@code eval} / 没有反射 / 没有文件与网络访问。这个类只做字符串变换， 加一个函数就要在这里写一段 Java —— 这看起来笨，但它换来两个东西： ①
 * 内置函数的行为可以被单元测试穷举；② 不会出现"某天有人往参数里塞了一段表达式"。
 *
 * <h3>命名</h3>
 *
 * <p>统一小写（{@code upper} 而不是 {@code toUpperCase}）：函数名要出现在面板下拉与节点配置里， 大小写混排会让人靠记忆而不是靠看。入参时统一 {@code
 * toLowerCase} 再查表，所以 {@code Upper} 与 {@code upper} 是同一个函数 —— <strong>这是刻意的宽容</strong>，
 * 因为"名字大小写写错"没有任何诊断价值。
 */
@Component
public class BuiltinFunctions implements FunctionInvokePort {

  /**
   * 序列化器。
   *
   * <p>★ 用 Jackson 3（{@code tools.jackson}）而不是 2 —— 全仓统一，且 {@code JacksonTwoUsageTest} 会红：
   * "用另一台序列化器断形状，绿了也不说明生产发出去的是什么"。
   */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Set<String> NAMES =
      new LinkedHashSet<>(
          List.of(
              "upper",
              "lower",
              "trim",
              "length",
              "concat",
              "substring",
              "replace",
              "split",
              "join",
              "default",
              "jsonpath"));

  /** {@code $.a.b[0]} 这种最小子集：只支持点路径与下标，不支持通配、递归、过滤表达式。 */
  private static final Pattern PATH_TOKEN = Pattern.compile("\\.([A-Za-z0-9_\\-]+)|\\[(\\d+)\\]");

  @Override
  public Set<String> functionNames() {
    return NAMES;
  }

  @Override
  public String invoke(String functionName, List<String> args) {
    if (functionName == null || functionName.isBlank()) {
      throw new IllegalArgumentException("函数名不能为空");
    }
    String name = functionName.trim().toLowerCase(Locale.ROOT);
    if (!NAMES.contains(name)) {
      throw new IllegalArgumentException(
          "没有这个内置函数：" + functionName + "。可用的有 " + String.join("、", NAMES));
    }
    List<String> a = args == null ? List.of() : args;
    return switch (name) {
      case "upper" -> require(a, 1, 1, name).get(0).toUpperCase(Locale.ROOT);
      case "lower" -> require(a, 1, 1, name).get(0).toLowerCase(Locale.ROOT);
      case "trim" -> require(a, 1, 1, name).get(0).trim();
      case "length" -> String.valueOf(require(a, 1, 1, name).get(0).length());
      case "concat" -> String.join("", a);
      case "substring" -> substring(name, a);
      case "replace" -> replace(name, a);
      case "split" -> split(name, a);
      case "join" -> join(name, a);
      case "default" -> defaultValue(name, a);
      case "jsonpath" -> jsonPath(name, a);
      default -> throw new IllegalArgumentException("内置函数表里列了但没实现：" + name);
    };
  }

  private static List<String> require(List<String> args, int min, int max, String name) {
    if (args.size() < min || args.size() > max) {
      throw new IllegalArgumentException(
          name + " 要 " + min + (max == min ? "" : "–" + max) + " 个参数，实际给了 " + args.size() + " 个");
    }
    return args;
  }

  private static String substring(String name, List<String> args) {
    require(args, 2, 3, name);
    String text = args.get(0);
    int from = parseInt(args.get(1), "substring 的起始位置");
    if (from < 0 || from > text.length()) {
      throw new IllegalArgumentException(
          "substring 的起始位置 " + from + " 超出范围（这段文本有 " + text.length() + " 个字符）");
    }
    if (args.size() == 2) {
      return text.substring(from);
    }
    int to = parseInt(args.get(2), "substring 的结束位置");
    if (to < from || to > text.length()) {
      throw new IllegalArgumentException(
          "substring 的结束位置 " + to + " 不在 [" + from + ", " + text.length() + "] 之内");
    }
    return text.substring(from, to);
  }

  private static String replace(String name, List<String> args) {
    require(args, 3, 3, name);
    return args.get(0).replace(args.get(1), args.get(2));
  }

  private static String split(String name, List<String> args) {
    require(args, 2, 2, name);
    String separator = args.get(1);
    if (separator.isEmpty()) {
      throw new IllegalArgumentException("split 的分隔符不能是空串（那会把每个字符都拆开）");
    }
    return String.join("\n", args.get(0).split(Pattern.quote(separator), -1));
  }

  private static String join(String name, List<String> args) {
    require(args, 2, 2, name);
    // 第一段按换行拆（与 split 的输出对接），再用第二个参数拼起来
    String[] parts = args.get(0).split("\n", -1);
    return String.join(args.get(1), parts);
  }

  private static String defaultValue(String name, List<String> args) {
    require(args, 2, 2, name);
    String value = args.get(0);
    return value == null || value.isBlank() ? args.get(1) : value;
  }

  /**
   * 最小 JSON 路径：{@code jsonpath(json, "$.a.b[0]")}。
   *
   * <p>★ 只支持点路径与下标。不引 JSONPath 库，也不支持通配 / 递归 / 过滤表达式 —— 那些一旦支持，"用户在配置里写一条路径"就变成"用户可以写一段查询"，
   * 而要保证它不会把进程拖死就需要另一套预算控制。
   */
  private static String jsonPath(String name, List<String> args) {
    require(args, 2, 2, name);
    String json = args.get(0);
    String path = args.get(1).trim();
    if (!path.startsWith("$")) {
      throw new IllegalArgumentException("jsonpath 的路径要以 $ 开头，例如 $.items[0].name");
    }
    Object node;
    try {
      node = JSON.readValue(json, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("jsonpath 的第一个参数不是合法 JSON：" + e.getMessage());
    }
    String rest = path.substring(1);
    if (rest.isEmpty()) {
      return asText(node);
    }
    Matcher m = PATH_TOKEN.matcher(rest);
    int consumed = 0;
    while (m.find()) {
      if (m.start() != consumed) {
        throw new IllegalArgumentException(
            "jsonpath 的路径解析不了：" + path + "（出错处在 " + rest.substring(consumed) + "）");
      }
      consumed = m.end();
      String key = m.group(1);
      String index = m.group(2);
      if (key != null) {
        if (!(node instanceof java.util.Map<?, ?> map)) {
          throw new IllegalArgumentException(
              "jsonpath 在 " + path + " 处要取字段「" + key + "」，但那一层的值不是对象");
        }
        node = map.get(key);
      } else {
        int i = Integer.parseInt(index);
        if (!(node instanceof List<?> list)) {
          throw new IllegalArgumentException("jsonpath 在 " + path + " 处要取下标 " + i + "，但那一层的值不是数组");
        }
        if (i < 0 || i >= list.size()) {
          throw new IllegalArgumentException(
              "jsonpath 的下标 " + i + " 超出范围（数组长度 " + list.size() + "）");
        }
        node = list.get(i);
      }
    }
    if (consumed != rest.length()) {
      throw new IllegalArgumentException("jsonpath 的路径解析不了：" + path);
    }
    return asText(node);
  }

  private static String asText(Object node) {
    if (node == null) {
      throw new IllegalArgumentException(
          "jsonpath 取到了 null —— 路径对但那一格没有值。要兜底请套一层 default(jsonpath(...), '缺省值')");
    }
    if (node instanceof java.util.Map<?, ?> || node instanceof List<?>) {
      try {
        return JSON.writeValueAsString(node);
      } catch (Exception e) {
        throw new IllegalArgumentException("jsonpath 取到的子结构不能转成文本：" + e.getMessage());
      }
    }
    return String.valueOf(node);
  }

  private static int parseInt(String raw, String what) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(what + "不是整数：" + raw);
    }
  }
}
