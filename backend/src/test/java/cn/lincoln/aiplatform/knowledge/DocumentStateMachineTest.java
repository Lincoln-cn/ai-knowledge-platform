package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 文档状态机（第 8 周 · 0.3.0 · 判据 ①）—— 「哪些状态能转到哪些状态」必须是一处可测的真源。
 *
 * <p>★ 为什么这条值得单独立一个测试类（{@code 03 §3.2} 的流水线）：状态机的价值全在<strong>拒绝</strong>上 ——
 * 合法转移写了没人会怀疑，而「加载失败直接标成功」「跳过分段直接成功」这类<strong>跳步</strong>不会报错，
 * 只会让界面把一个其实没加工的文档显示成可用。所以下面<strong>反例与正例一样多</strong>。
 *
 * <p>★ 与 {@code modelcatalog} 的 {@code ModelStateMachineTest} 同构：转移表放领域类型上，实体每个行为过守卫， 非法转移抛带
 * from/to 的可读异常。
 */
class DocumentStateMachineTest {

  // ────────────────────────── 1 · 转移表本身 ──────────────────────────

  /** ★ 起点不许被转回：{@code PENDING_PARSE} 的来源集合为空。 否则「成功后回退到未解析」会把加工结果擦掉而没人察觉。 */
  @Test
  void 未解析是真正的起点_没有状态能转回它() {
    assertThat(DocumentStatus.PENDING_PARSE.allowedSources()).isEmpty();
  }

  /** ★ 合法转移逐条钉住（与 {@code 03 §3.2} 的流水线一一对应）。 */
  @ParameterizedTest(name = "{0} → {1} 合法")
  @CsvSource({
    // 解析与分段
    "PENDING_PARSE, SEGMENTING",
    "PENDING_PARSE, PENDING_EMBEDDING",
    "PENDING_PARSE, SEGMENT_FAILED",
    "SEGMENTING, PENDING_EMBEDDING",
    "SEGMENTING, SEGMENT_FAILED",
    // 向量化
    "PENDING_EMBEDDING, EMBEDDING",
    "EMBEDDING, SUCCEEDED",
    "EMBEDDING, EMBEDDING_FAILED",
    // 重试（03 §3.5 失败恢复）
    "SEGMENT_FAILED, SEGMENTING",
    "EMBEDDING_FAILED, PENDING_EMBEDDING",
    // 分段被编辑/删除后向量旧了（SEG-I7）
    "SUCCEEDED, PENDING_EMBEDDING",
  })
  void 合法转移(DocumentStatus from, DocumentStatus to) {
    assertThat(to.canMoveFrom(from)).as("%s → %s 应当合法", from, to).isTrue();
    DocumentStatus.requireLegal(from, to, "测试动作"); // 不抛即通过
  }

  /** ★ 非法转移逐条钉住：跳步、回退、把失败说成成功。 */
  @ParameterizedTest(name = "{0} → {1} 非法")
  @CsvSource({
    // 跳过加工直接成功 —— 最危险的一种：界面显示可用，实际没分段也没向量
    "PENDING_PARSE, SUCCEEDED",
    "PENDING_PARSE, EMBEDDING",
    // 失败原因还没处理就当成功
    "SEGMENT_FAILED, SUCCEEDED",
    "EMBEDDING_FAILED, SUCCEEDED",
    "SEGMENT_FAILED, PENDING_EMBEDDING",
    // 向量化阶段退回去改分段结构（顺序反了）
    "EMBEDDING, SEGMENT_FAILED",
    "EMBEDDING, SEGMENTING",
    "EMBEDDING, PENDING_PARSE",
    // 成功后回退到加工前
    "SUCCEEDED, PENDING_PARSE",
    "SUCCEEDED, SEGMENTING",
    "SUCCEEDED, EMBEDDING",
  })
  void 非法转移(DocumentStatus from, DocumentStatus to) {
    assertThat(to.canMoveFrom(from)).as("%s → %s 应当非法", from, to).isFalse();
  }

  /** ★ 非法转移的异常必须说清<strong>从哪到哪</strong>：排障时不能只知道"失败了"。 */
  @Test
  void 非法转移的异常消息含来源与目标() {
    assertThatThrownBy(
            () ->
                DocumentStatus.requireLegal(
                    DocumentStatus.PENDING_PARSE, DocumentStatus.SUCCEEDED, "标记向量化完成"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PENDING_PARSE")
        .hasMessageContaining("SUCCEEDED")
        .hasMessageContaining("标记向量化完成")
        .hasMessageContaining("状态未改变");
  }

  // ────────────────────────── 2 · 实体行为确实过守卫 ──────────────────────────

  /** 建档初值必须是「未解析」—— 显示成成功就等于把没加工的文档说成可检索。 */
  @Test
  void 新建文档初值是未解析() {
    assertThat(newDocument().status()).isEqualTo(DocumentStatus.PENDING_PARSE);
  }

  /** ★ 解析成功：未解析 → 分段中 → 待向量化（两步都过守卫）。 */
  @Test
  void 解析成功后是待向量化() {
    Document doc = newDocument();
    doc.startParse(0);
    assertThat(doc.status()).as("解析是在途态（扫描件的 OCR 要跑分钟级）").isEqualTo(DocumentStatus.SEGMENTING);
    doc.recordParseSuccess("# 第一章\n\n正文。", 0);
    assertThat(doc.status()).isEqualTo(DocumentStatus.PENDING_EMBEDDING);
    assertThat(doc.parsedContent()).contains("第一章");
  }

  /**
   * ★ 已经成功的文档不能再被当成"解析一次"：{@code SUCCEEDED → SEGMENTING} 非法 ⇒ 必须抛。
   *
   * <p>★ 第 9 周起这道闸在 {@code startParse}（解析的**入口**）上 —— 之前它在 {@code recordParseSuccess}
   * 的第一步，那是因为解析是同步的、两步在一个方法里。 改成异步流水线后入口就是 startParse，守卫跟着入口走才对（"能不能开始解析"是入口该回答的问题）。
   */
  @Test
  void 已成功的文档不允许重新走解析() {
    Document doc = embeddingSucceeded();
    assertThatThrownBy(() -> doc.startParse(0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUCCEEDED");
  }

  /** ★ 失败态不许直接标成功：跳步会让"失败原因还没被处理"变成"已完成"。 */
  @Test
  void 分段失败的文档不允许直接标向量化完成() {
    Document doc = newDocument();
    doc.recordParseFailure("解析结果为空：可能是空文档或文件损坏", "修复后重新上传", "");
    assertThat(doc.status()).isEqualTo(DocumentStatus.SEGMENT_FAILED);
    assertThatThrownBy(doc::markEmbedded)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEGMENT_FAILED");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  static Document newDocument() {
    return new Document(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "差旅报销规定.docx",
        "local",
        "制度",
        // ★ 责任部门改成 org_id（UUID）—— 见 `07 §十一` / Issue #37。领域构造器直调，不校验存在性。
        UUID.randomUUID(),
        ClassificationLevel.INTERNAL,
        UUID.randomUUID());
  }

  /** 走到「待向量化」且有 10 个分段的文档（进度类断言的共同起点）。 */
  static Document segmented(int segmentCount) {
    Document doc = newDocument();
    doc.startParse(0);
    doc.recordParseSuccess("# 第一章 总则\n\n本制度适用于公司各部门。", 0);
    doc.recordSegmentSuccess(segmentCount, 1000);
    return doc;
  }

  /** 走到「已完成」的文档。 */
  static Document embeddingSucceeded() {
    Document doc = segmented(4);
    doc.startEmbedding();
    doc.reportEmbeddingProgress(4);
    doc.markEmbedded();
    return doc;
  }
}
