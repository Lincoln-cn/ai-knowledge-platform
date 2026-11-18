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

  @Test
  @DisplayName("payload 里允许 null 值 —— 内置模板的 modelId 就是 null，不许因此炸掉整个列表")
  void payloadMayContainNullValues() {
    // ★ 这条是回归测试：原先深拷用 `Map.copyOf`，它拒收 null 值 ⇒ 只要库里有内置模板
    //   （V90 的三个 payload 都带 "modelId": null），`GET /templates` 就 500，
    //   一个模板都读不出来。症状是"模板中心整个打不开"，而根因藏在一行深拷里。
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("modelId", null);
    config.put("name", "合同审查");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("config", config);
    payload.put("dependencies", java.util.List.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> kept =
        (Map<String, Object>) template(payload, true).payload().get("config");

    assertThat(kept).containsKey("modelId");
    assertThat(kept.get("modelId")).isNull();
    assertThat(kept.get("name")).isEqualTo("合同审查");
  }
}
