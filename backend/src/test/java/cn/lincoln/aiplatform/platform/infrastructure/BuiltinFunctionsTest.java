package cn.lincoln.aiplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内置函数表的行为。
 *
 * <p>★ 这一组测试是"沙箱不在本版本"的替代品：{@code FUNCTION} 两个节点不执行用户脚本， 能跑的东西就是这 11 个函数。它们的行为必须被逐条钉住 ——
 * 否则「节点跑通了」这件事 只能说明"调到了一个函数"，说明不了"算得对"。
 */
class BuiltinFunctionsTest {

  private final BuiltinFunctions functions = new BuiltinFunctions();

  @Test
  @DisplayName("函数名清单就是面板下拉里能看到的那一组")
  void advertisesItsFunctions() {
    Set<String> names = functions.functionNames();

    assertThat(names)
        .containsExactly(
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
            "jsonpath");
  }

  @Test
  @DisplayName("大小写与前后空白都不影响函数名 —— 名字写错没有诊断价值")
  void functionNameIsCaseInsensitive() {
    assertThat(functions.invoke("Upper", List.of("abc"))).isEqualTo("ABC");
    assertThat(functions.invoke("  upper  ", List.of("abc"))).isEqualTo("ABC");
  }

  @Test
  @DisplayName("大小写转换与去空白")
  void caseAndTrim() {
    assertThat(functions.invoke("upper", List.of("aBc"))).isEqualTo("ABC");
    assertThat(functions.invoke("lower", List.of("aBc"))).isEqualTo("abc");
    assertThat(functions.invoke("trim", List.of("  x  "))).isEqualTo("x");
    assertThat(functions.invoke("length", List.of("中文 ab"))).isEqualTo("5");
  }

  @Test
  @DisplayName("拼接不插分隔符；拼接零个参数给空串（不是报错）")
  void concat() {
    assertThat(functions.invoke("concat", List.of("a", "b", "c"))).isEqualTo("abc");
    assertThat(functions.invoke("concat", List.of())).isEmpty();
  }

  @Test
  @DisplayName("截取：两个参数取到结尾，三个参数取到指定位置")
  void substring() {
    assertThat(functions.invoke("substring", List.of("abcdef", "2"))).isEqualTo("cdef");
    assertThat(functions.invoke("substring", List.of("abcdef", "1", "3"))).isEqualTo("bc");
    assertThatThrownBy(() -> functions.invoke("substring", List.of("abc", "5")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出范围");
    assertThatThrownBy(() -> functions.invoke("substring", List.of("abcdef", "3", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在");
    assertThatThrownBy(() -> functions.invoke("substring", List.of("abc", "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是整数");
  }

  @Test
  @DisplayName("替换走字面量替换，不是正则 —— 用户填的 $ 与 \\ 不该被当成正则语法")
  void replaceIsLiteral() {
    assertThat(functions.invoke("replace", List.of("a-b-c", "-", "+"))).isEqualTo("a+b+c");
    // 把点换成"反斜杠 + 点"：结果是 a\.b（若走正则替换，$ 与 \ 会被当成反向引用与转义）
    assertThat(functions.invoke("replace", List.of("a.b", ".", "\\."))).isEqualTo("a\\.b");
  }

  @Test
  @DisplayName("拆分用 \\n 连接、合并按 \\n 切分 —— 两个函数能直接对接")
  void splitAndJoinRoundTrip() {
    String split = functions.invoke("split", List.of("a,b,c", ","));
    assertThat(split).isEqualTo("a\nb\nc");
    assertThat(functions.invoke("join", List.of(split, "-"))).isEqualTo("a-b-c");
    // 末尾空段要保留：丢掉它等于悄悄改了数据
    assertThat(functions.invoke("split", List.of("a,,", ","))).isEqualTo("a\n\n");
    assertThatThrownBy(() -> functions.invoke("split", List.of("abc", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("分隔符");
  }

  @Test
  @DisplayName("兜底：空串与空白都算缺值，非空原样返回")
  void defaultFunction() {
    assertThat(functions.invoke("default", List.of("", "缺省"))).isEqualTo("缺省");
    assertThat(functions.invoke("default", List.of("   ", "缺省"))).isEqualTo("缺省");
    assertThat(functions.invoke("default", List.of("有值", "缺省"))).isEqualTo("有值");
  }

  @Test
  @DisplayName("从 JSON 里取一格：点路径、下标、取到对象/数组时转成 JSON 文本")
  void jsonPath() {
    String json = "{\"items\":[{\"name\":\"甲\",\"tags\":[\"x\",\"y\"]}],\"n\":3}";

    assertThat(functions.invoke("jsonpath", List.of(json, "$.items[0].name"))).isEqualTo("甲");
    assertThat(functions.invoke("jsonpath", List.of(json, "$.items[0].tags[1]"))).isEqualTo("y");
    assertThat(functions.invoke("jsonpath", List.of(json, "$.n"))).isEqualTo("3");
    assertThat(functions.invoke("jsonpath", List.of(json, "$.items[0].tags")))
        .isEqualTo("[\"x\",\"y\"]");
  }

  @Test
  @DisplayName("JSON 路径的四种失败：不以 $ 开头、路径解析不了、类型不对、下标越界")
  void jsonPathFailures() {
    String json = "{\"a\":{\"b\":1},\"list\":[1,2]}";

    assertThatThrownBy(() -> functions.invoke("jsonpath", List.of(json, "a.b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("$");
    assertThatThrownBy(() -> functions.invoke("jsonpath", List.of("{不是 JSON", "$.a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("合法 JSON");
    assertThatThrownBy(() -> functions.invoke("jsonpath", List.of(json, "$.a.b.c")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是对象");
    assertThatThrownBy(() -> functions.invoke("jsonpath", List.of(json, "$.list[5]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出范围");
  }

  @Test
  @DisplayName("取到 null 时要报错并指向 default(...) —— 静默给空串会让下游以为「那一格是空的」")
  void jsonPathNullPointsAtDefault() {
    assertThatThrownBy(() -> functions.invoke("jsonpath", List.of("{\"a\":null}", "$.a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default(");
  }

  @Test
  @DisplayName("未知函数与参数个数错都要说清哪个名字、该给几个")
  void unknownFunctionAndArity() {
    assertThatThrownBy(() -> functions.invoke("eval", List.of("1+1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有这个内置函数")
        .hasMessageContaining("upper");
    assertThatThrownBy(() -> functions.invoke("upper", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("要 1 个参数")
        .hasMessageContaining("实际给了 0 个");
    assertThatThrownBy(() -> functions.invoke("", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("函数名不能为空");
  }
}
