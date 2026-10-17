package cn.lincoln.aiplatform.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★ 引用快照的<strong>落库形状</strong>：{@link SourceRef} 经 Jackson 序列化 / 反序列化必须<strong>逐字段相等</strong> ——
 * 纯单测（不起 Spring、不起容器，也不需要真库）。
 *
 * <h3>为什么这条必须单独钉（而不是"等真栈测试顺便验一下"）</h3>
 *
 * <p>>引用落进 {@code jsonb} 列这一路上有两个<strong>静默</strong>的坏法：
 *
 * <ul>
 *   <li><strong>字段名变形</strong>：后端的 {@code docName} 若被写成 {@code documentName}（或配置成 {@code
 *       SNAKE_CASE}），序列化<strong>不会报错</strong>，只是前端读到的字段是 {@code undefined} ——
 *       引用栏照样渲染，标题空着、深链跳不动。契约的字段名是 camelCase，这一条由本类的 <strong>裸 JSON 字段名断言</strong>钉住（不看对象、直接看那串
 *       JSON 里有没有 {@code docName} 这几个字）。
 *   <li><strong>可选字段被塞成空串</strong>：契约里 {@code rank} / {@code full} / {@code segmentId} 等是
 *       "要么有要么没有"，用空串代替 {@code null} 会让前端 {@code if (ref.segmentId)} 走成"有这个深链"， 而那条链接指向一个空 id。
 * </ul>
 *
 * <p>★ 与 {@code StreamContractCrossCheckTest} 的分工：那个类比对的是<strong>类型形状</strong>（record 分量名 vs TS
 * 字段名）， 本类比对的是<strong>序列化之后的线形状</strong>（JSON 里的键名与取值）。两者都可能单独失守 （例如给 record 加一个
 * {@code @JsonProperty("...")} 改名，前一个类照样绿）。
 *
 * <p>★ 为什么用 Jackson 2（{@code com.fasterxml.jackson}）而不是项目里常用的 Jackson 3（{@code tools.jackson}）： 写
 * {@code jsonb} 列的是 <strong>Hibernate 的 {@code JsonFormatMapper}</strong>，而 Hibernate 7.4.5 用的是
 * Jackson 2（它由 {@code openai-java-core} 传递进来）。★ 本类刻意用<strong>与 Hibernate 同一个</strong>序列化器，
 * 这样"真库里读到什么"这件事在不需要容器的前提下就有读数了。
 */
class SourceRefSerializationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  // ────────────────────────── ① 逐字段往返 ──────────────────────────

  /**
   * ★ <strong>全字段</strong>往返：10 个契约字段一个不少、取值逐字相同。
   *
   * <p>★ 断言整个对象相等（record 是值对象）而不是逐字段断言：逐字段写下去很容易漏掉一个， 而"漏掉的那个字段"正好就是会静默变 {@code undefined} 的那一个。
   */
  @Test
  void fullReferenceRoundTripsFieldByField() throws Exception {
    SourceRef original =
        new SourceRef(
            3,
            "第三章 报销标准",
            "报销管理规定.pdf",
            "制度库",
            0.9123,
            2,
            "住宿费标准为每人每天 500 元。",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            ClassificationLevel.SECRET);

    SourceRef restored = JSON.readValue(JSON.writeValueAsString(original), SourceRef.class);

    assertThat(restored).as("★ 引用快照往返必须逐字段相等（含 index / score / 三个深链 id / 密级）").isEqualTo(original);
    assertThat(restored.score()).as("score 是浮点，往返不许被取整").isEqualTo(0.9123);
    assertThat(restored.classification())
        .as("★ 密级必须原样往返 —— 它是会话累计密级的唯一来源，丢了会让水位算低")
        .isEqualTo(ClassificationLevel.SECRET);
  }

  /**
   * ★ <strong>裸 JSON 的键名 = 契约的字段名</strong>（camelCase，不许被改成 snake_case / 别名）。
   *
   * <p>★ 这条是"看着像小事、症状是静默"的那一类：{@code docName} 变成 {@code doc_name} 或 {@code documentName}
   * 都不会让任何一处报错，前端只是拿到 {@code undefined}。
   */
  @Test
  void jsonKeysAreExactlyTheContractFieldNames() throws Exception {
    SourceRef ref =
        new SourceRef(
            1,
            "标题",
            "文档名",
            "知识库名",
            0.5,
            1,
            "全文",
            "seg-1",
            "doc-1",
            "kb-1",
            ClassificationLevel.INTERNAL);

    JsonNode node = JSON.readTree(JSON.writeValueAsString(ref));

    assertThat(node.properties().stream().map(java.util.Map.Entry::getKey).toList())
        .as(
            "★ JSON 键名必须逐字等于契约 SourceRef 的 11 个字段名"
                + "（写成 doc_name / documentName 都不会报错，只是前端读不到；classification 同理）")
        .containsExactly(
            "index",
            "title",
            "docName",
            "kbName",
            "score",
            "rank",
            "full",
            "segmentId",
            "documentId",
            "kbId",
            "classification");
  }

  // ────────────────────────── ② 可选字段：缺省就是缺省 ──────────────────────────

  /**
   * ★ 可选字段缺省时序列化成 {@code null}，<strong>不许变成空串</strong>。
   *
   * <p>★ 契约里 {@code segmentId?: string} 的语义是"要么有要么没有"。用空串代替 {@code null} 会让读取端 的 {@code if
   * (ref.segmentId)} 走成"这条引用有深链"，而那条链接指向一个空 id —— 点下去是静默 no-op （{@code #91} 记着这一类账）。
   */
  @Test
  void optionalFieldsStayNullInsteadOfEmptyString() throws Exception {
    SourceRef ref =
        new SourceRef(
            1, "标题", "文档名", "知识库名", 0.5, null, null, null, null, null, ClassificationLevel.PUBLIC);

    JsonNode node = JSON.readTree(JSON.writeValueAsString(ref));

    for (String optional : List.of("rank", "full", "segmentId", "documentId", "kbId")) {
      assertThat(node.get(optional).isNull())
          .as("★ 可选字段 %s 缺省时必须是 null —— 空串会被前端读成'有值'，指向一个空 id 的深链", optional)
          .isTrue();
    }
  }

  /**
   * ★ 空串进构造函数时被<strong>规范化成 {@code null}</strong>（{@link SourceRef} 的紧凑构造器）。
   *
   * <p>★ 这一条是"两处口径"的守门人：{@code SourceRef} 已经规定"可选字段要么有要么没有"， 若某个上游传了空串就原样存进 {@code
   * jsonb}，"没有"这件事在库里就有了两种表达（{@code null} 与 {@code ""}）。
   */
  @Test
  void blankOptionalFieldsAreNormalisedToNull() {
    SourceRef ref =
        new SourceRef(
            1, "标题", "文档名", "知识库名", 0.5, null, "  ", "", " ", "", ClassificationLevel.CONFIDENTIAL);

    assertThat(ref.full()).isNull();
    assertThat(ref.segmentId()).isNull();
    assertThat(ref.documentId()).isNull();
    assertThat(ref.kbId()).isNull();
    assertThat(ref.classification())
        .as("★ 密级不是可选字段：空串规范化那一条只管那五个可空串，密级必须原样留下")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  // ────────────────────────── ③ 多条引用：数组往返 ──────────────────────────

  /**
   * ★ <strong>列表</strong>往返，且 {@code index} 的取值与顺序都不被动过。
   *
   * <p>★ 断言 {@code containsExactly}（有序）而不是"集合相等"：{@code index} 是正文里 {@code [N]} 的编号， 顺序一变，用户点
   * {@code [1]} 看到的就是另一段原文 —— 而那种错不会报错。
   */
  @Test
  void referenceListRoundTripsInOrder() throws Exception {
    List<SourceRef> refs =
        List.of(
            new SourceRef(
                1,
                "第一章 总则",
                "报销管理规定.pdf",
                "制度库",
                0.91,
                1,
                null,
                null,
                null,
                null,
                ClassificationLevel.INTERNAL),
            new SourceRef(
                2,
                "第三章 报销标准",
                "报销管理规定.pdf",
                "制度库",
                0.88,
                3,
                null,
                null,
                null,
                null,
                ClassificationLevel.PUBLIC),
            new SourceRef(
                3,
                "附件 A 住宿标准表",
                "报销管理规定.pdf",
                "制度库",
                0.75,
                null,
                null,
                null,
                null,
                null,
                ClassificationLevel.SECRET));

    String json = JSON.writeValueAsString(refs);
    List<SourceRef> restored =
        JSON.readValue(
            json, JSON.getTypeFactory().constructCollectionType(List.class, SourceRef.class));

    assertThat(restored)
        .as("★ 引用列表往返必须保持顺序与 index（重排会让正文里的 [N] 指到别的来源上）")
        .containsExactlyElementsOf(refs);
  }

  /** ★ 空列表往返仍是空列表（{@code []} = 明确的"零命中"，不是"从来没到过"）。 */
  @Test
  void emptyReferenceListStaysEmpty() throws Exception {
    String json = JSON.writeValueAsString(List.of());

    List<SourceRef> restored =
        JSON.readValue(
            json, JSON.getTypeFactory().constructCollectionType(List.class, SourceRef.class));

    assertThat(restored).as("零命中是 []，与 null（引用从没到过）是两个不同的答案").isEmpty();
  }
}
