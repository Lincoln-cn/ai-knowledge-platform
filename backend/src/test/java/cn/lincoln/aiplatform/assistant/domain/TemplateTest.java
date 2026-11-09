package cn.lincoln.aiplatform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Template} 的领域规则（{@code #295}）。
 *
 * <p>这一层不需要数据库 —— 所以它能在这里跑，而"模板→应用"那条链路要等真库。
 */
class TemplateTest {

  private static Template template(Map<String, Object> payload, boolean builtin) {
    return new Template(
        UUID.randomUUID(),
        "契约审查",
        "带密级过滤的契约问答",
        "LEGAL",
        null,
        payload,
        builtin,
        Instant.parse("2026-11-09T00:00:00Z"),
        null,
        Instant.parse("2026-11-09T00:00:00Z"),
        null);
  }

  @Test
  @DisplayName("名称与说明会被裁剪，超长直接拒（不是悄悄截断）")
  void nameAndDescriptionAreValidated() {
    assertThat(template(Map.of(), false).name()).isEqualTo("契约审查");

    assertThatThrownBy(
            () ->
                new Template(
                    UUID.randomUUID(),
                    "  ",
                    "",
                    "GENERAL",
                    null,
                    Map.of(),
                    false,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("模板名称不能为空");

    String tooLong = "x".repeat(Template.NAME_MAX + 1);
    assertThatThrownBy(
            () ->
                new Template(
                    UUID.randomUUID(),
                    tooLong,
                    "",
                    "GENERAL",
                    null,
                    Map.of(),
                    false,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能超过");
  }

  @Test
  @DisplayName("分类缺省是 GENERAL —— 列表页按它分组，空分类会让模板掉出所有分组")
  void categoryFallsBackToGeneral() {
    Template t =
        new Template(
            UUID.randomUUID(), "空分类", "", null, null, Map.of(), false, null, null, null, null);
    assertThat(t.category()).isEqualTo("GENERAL");
  }

  @Test
  @DisplayName("payload 是快照不是引用：构造后改调用方那份 map，模板里的值不变")
  void payloadIsDeepCopied() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("topN", 5);
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("config", inner);

    Template t = template(source, false);

    inner.put("topN", 99);
    source.put("config", Map.of("topN", -1));

    @SuppressWarnings("unchecked")
    Map<String, Object> kept = (Map<String, Object>) t.payload().get("config");
    assertThat(kept.get("topN")).isEqualTo(5);
  }

  @Test
  @DisplayName("内置模板不许删 —— 它是随包发布的那几个 seed，删了新环境就一片空白")
  void builtinTemplatesAreNotEditable() {
    template(Map.of(), false).requireEditable(); // 不抛
    assertThatThrownBy(() -> template(Map.of(), true).requireEditable())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("内置模板");
  }
}
