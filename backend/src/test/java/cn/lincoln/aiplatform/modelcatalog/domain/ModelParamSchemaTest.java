package cn.lincoln.aiplatform.modelcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可调参数声明（{@code ai_model.param_schema}）。
 *
 * <p>★ 这一族的判据一律是「坏数据当场报错」与「官方键与自定义键分得清」两件事： 前者防的是「保存成功、界面空白」； 后者防的是「填了 topP，请求里却发成
 * extraBody.topP，上游不认识、也没人报错」。
 */
class ModelParamSchemaTest {

  @Test
  @DisplayName("空声明是空列表，不是「解析失败」")
  void emptyMeansNoParams() {
    assertThat(ModelParamSchema.parseStrict(null)).isEmpty();
    assertThat(ModelParamSchema.parseStrict("  ")).isEmpty();
    assertThat(ModelParamSchema.parseStrict("[]")).isEmpty();
    assertThat(ModelParamSchema.write(List.of())).isEqualTo("[]");
  }

  @Test
  @DisplayName("不是合法 JSON 就报错（写路径不能容错）")
  void brokenJsonIsRejectedOnWrite() {
    assertThatThrownBy(() -> ModelParamSchema.parseStrict("{\"key\":\"temperature\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是合法的 JSON 数组");
  }

  @Test
  @DisplayName("读路径容错：脏数据当「没有参数」，不让整页打挂")
  void brokenJsonIsToleratedOnRead() {
    assertThat(ModelParamSchema.parse("{not json")).isEmpty();
  }

  @Test
  @DisplayName("序列化会把类型转小写、把文本去空白（否则同一条声明两种写法）")
  void writeNormalizes() {
    String json =
        ModelParamSchema.write(
            List.of(
                new ModelParamSchema.Param(
                    "temperature", " 温度 ", "NUMBER", 0.7, 0D, 2D, null, null)));
    assertThat(json).contains("\"type\":\"number\"");
    assertThat(json).contains("\"label\":\"温度\"");
  }

  @Test
  @DisplayName("重复的字段名必须报错，并指出是第几条")
  void duplicateKeysAreRejected() {
    List<ModelParamSchema.Param> params =
        List.of(
            new ModelParamSchema.Param("temperature", "温度", "number", 0.7, null, null, null, null),
            new ModelParamSchema.Param(
                "temperature", "温度2", "number", 1.0, null, null, null, null));
    assertThatThrownBy(() -> ModelParamSchema.validate(params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("第 2 条参数")
        .hasMessageContaining("重复");
  }

  @Test
  @DisplayName("不认识的类型报错（避免界面按错误控件渲染）")
  void unknownTypeIsRejected() {
    List<ModelParamSchema.Param> params =
        List.of(
            new ModelParamSchema.Param("temperature", "温度", "float", 0.7, null, null, null, null));
    assertThatThrownBy(() -> ModelParamSchema.validate(params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("第 1 条参数");
  }

  @Test
  @DisplayName("integer 的默认值必须是整数，且不能越界")
  void integerBoundsAreChecked() {
    assertThatThrownBy(
            () ->
                ModelParamSchema.validate(
                    List.of(
                        new ModelParamSchema.Param(
                            "max_tokens", "上限", "integer", 1.5, null, null, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("整数");

    assertThatThrownBy(
            () ->
                ModelParamSchema.validate(
                    List.of(
                        new ModelParamSchema.Param(
                            "max_tokens", "上限", "integer", 9, 1D, 8D, null, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("大于最大值");
  }

  @Test
  @DisplayName("enum 必须有候选，默认值必须在候选里")
  void enumNeedsOptionsAndMemberDefault() {
    assertThatThrownBy(
            () ->
                ModelParamSchema.validate(
                    List.of(
                        new ModelParamSchema.Param(
                            "mode", "模式", "enum", "a", null, null, List.of(), null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("候选");

    assertThatThrownBy(
            () ->
                ModelParamSchema.validate(
                    List.of(
                        new ModelParamSchema.Param(
                            "mode", "模式", "enum", "z", null, null, List.of("a", "b"), null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("候选");
  }

  @Test
  @DisplayName("官方键与自定义键分开：temperature 走 ChatOptions，别的走 extraBody")
  void splitSeparatesOfficialKeys() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("temperature", 0.3);
    values.put("topP", 0.9);
    values.put("maxTokens", 512);
    values.put("top_k", 40);

    ModelParamSchema.Split split = ModelParamSchema.split(values);

    assertThat(split.official()).containsOnlyKeys("temperature", "top_p", "max_tokens");
    assertThat(split.official().get("temperature")).isEqualTo(0.3);
    assertThat(split.official().get("top_p")).isEqualTo(0.9);
    assertThat(split.official().get("max_tokens")).isEqualTo(512);
    assertThat(split.extra()).containsOnlyKeys("top_k");
  }

  @Test
  @DisplayName("官方键的别名归一：topP / top-p / maxTokens 都指向同一个官方键")
  void officialAliasesAreNormalized() {
    assertThat(ModelParamSchema.normalizeOfficial("topP")).isEqualTo("top_p");
    assertThat(ModelParamSchema.normalizeOfficial("top-p")).isEqualTo("top_p");
    assertThat(ModelParamSchema.normalizeOfficial("maxTokens")).isEqualTo("max_tokens");
    assertThat(ModelParamSchema.normalizeOfficial("top_k")).isEqualTo("top_k");
  }

  @Test
  @DisplayName("超过 20 条报错（界面上再多也没人看，且请求体会无限长）")
  void tooManyParamsAreRejected() {
    List<ModelParamSchema.Param> params = new java.util.ArrayList<>();
    for (int i = 0; i < 21; i++) {
      params.add(
          new ModelParamSchema.Param("p" + i, "参数 " + i, "number", null, null, null, null, null));
    }
    assertThatThrownBy(() -> ModelParamSchema.validate(params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("20");
  }

  @Test
  @DisplayName("JSON 里默认值的键名是 default，不是 record 的字段名 defaultValue")
  void defaultValueUsesTheShortJsonKey() {
    String json =
        ModelParamSchema.write(
            List.of(
                new ModelParamSchema.Param(
                    "temperature", "温度", "number", 0.35, 0.0, 2.0, List.of(), null)));
    // ★ 这条是 2026-11-28 真栈核对踩出来的：前端按 default 提交，record 字段叫 defaultValue，
    //   没有 @JsonProperty 时那个值被当成未知属性丢掉 —— 库里存下的是 defaultValue: null。
    assertThat(json).contains("\"default\":0.35").doesNotContain("defaultValue");
    assertThat(ModelParamSchema.parse(json).get(0).defaultValue()).isEqualTo(0.35);
  }
}
