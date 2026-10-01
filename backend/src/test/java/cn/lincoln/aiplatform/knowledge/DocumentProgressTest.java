package cn.lincoln.aiplatform.knowledge;

import static cn.lincoln.aiplatform.knowledge.DocumentStateMachineTest.segmented;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import org.junit.jupiter.api.Test;

/**
 * 真实进度与失败恢复（第 8 周 · 0.3.0 · 判据 ②）。
 *
 * <p>★ 这套断言盯的是「注入/重投之后会变成什么样」，而不是单次执行的结果 —— {@code 03 §3.3} 的原话：累加写法「单次执行看起来完全正确，只有在事件重投时才暴露为进度变成
 * 200%」。 所以每条覆盖写的断言都做两遍（投一次、再投同一个数）。
 *
 * <p>★ 另一条纪律在这里落地：<strong>只有全部段都进完向量才允许置成功</strong>（判据 ① 的另一面）。 差一段就标成功 ⇒
 * 界面显示"已完成"而库里有分段没向量，是最贵的"看起来在做、实际没生效"。
 */
class DocumentProgressTest {

  // ────────────────────────── 1 · 覆盖写 / 重投 ──────────────────────────

  /** ★ 同一批报两次同一个数 ⇒ 进度不变（**不翻倍**）。 */
  @Test
  void 同一批重复报进度不翻倍() {
    Document doc = segmented(10);
    doc.startEmbedding();

    doc.reportEmbeddingProgress(3);
    assertThat(doc.processedSegments()).isEqualTo(3);

    doc.reportEmbeddingProgress(3);
    assertThat(doc.processedSegments()).as("重投同一批：覆盖写 ⇒ 仍是 3，而不是 6（累加写法在这里会变成 200%%）").isEqualTo(3);
  }

  /** ★ 回退（旧事件重投）⇒ 不报错、也不改进度：至少一次投递下，旧事件必须是无害的 no-op。 */
  @Test
  void 回退报进度是无害的noop() {
    Document doc = segmented(10);
    doc.startEmbedding();
    doc.reportEmbeddingProgress(5);

    doc.reportEmbeddingProgress(3); // 旧事件重投

    assertThat(doc.processedSegments()).as("进度不许倒退").isEqualTo(5);
  }

  /** 越界必须拒绝并说清范围（不许静默截断到边界值）。 */
  @Test
  void 进度越界必须拒绝() {
    Document doc = segmented(10);
    doc.startEmbedding();

    assertThatThrownBy(() -> doc.reportEmbeddingProgress(11))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("11")
        .hasMessageContaining("10");
    assertThatThrownBy(() -> doc.reportEmbeddingProgress(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 不在向量化中不许报进度：否则「没开始就有进度」会让界面出现凭空的进度条。 */
  @Test
  void 不在向量化中报进度必须拒绝() {
    Document doc = segmented(10); // 待向量化，还没 startEmbedding
    assertThatThrownBy(() -> doc.reportEmbeddingProgress(1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PENDING_EMBEDDING");
  }

  // ────────────────────────── 2 · 开始/完成 ──────────────────────────

  /** ★ 开始向量化要把进度归零：进度是「本轮」的事实，不是终身累计。 走的是真实的重新向量化路径 —— 已完成 → 分段改过、向量旧了 → 重新开始。 */
  @Test
  void 开始向量化把进度归零() {
    Document doc = DocumentStateMachineTest.embeddingSucceeded();
    assertThat(doc.processedSegments()).isEqualTo(4);

    doc.markVectorsStale();
    doc.startEmbedding();

    assertThat(doc.status()).isEqualTo(DocumentStatus.EMBEDDING);
    assertThat(doc.processedSegments()).as("新一轮的进度必须从 0 开始").isZero();
    assertThat(doc.segmentCount()).as("分母不变（分段没变）").isEqualTo(4);
  }

  /** ★ 只走了一部分就标完成 ⇒ 必须拒绝（判据 ① 的另一面）。 */
  @Test
  void 未全部完成不许标成功() {
    Document doc = segmented(10);
    doc.startEmbedding();
    doc.reportEmbeddingProgress(9);

    assertThatThrownBy(doc::markEmbedded)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("9")
        .hasMessageContaining("10");
    assertThat(doc.status()).as("拒绝后状态不许变").isEqualTo(DocumentStatus.EMBEDDING);
  }

  /** 全部完成 ⇒ 成功，并且把上一轮的失败原因清掉（旧错误不该挂在已恢复的文档上）。 */
  @Test
  void 全部完成才能标成功并清失败原因() {
    Document doc = segmented(4);
    doc.startEmbedding();
    doc.reportEmbeddingProgress(2);
    doc.markEmbeddingFailed("上游 embeddings 接口超时", "稍后重试或检查模型服务");
    assertThat(doc.status()).isEqualTo(DocumentStatus.EMBEDDING_FAILED);
    assertThat(doc.failReason()).contains("超时");

    doc.retryEmbedding();
    assertThat(doc.status()).isEqualTo(DocumentStatus.PENDING_EMBEDDING);
    assertThat(doc.processedSegments()).isZero();
    assertThat(doc.failReason()).as("重试后旧失败原因必须清掉").isNull();

    doc.startEmbedding();
    doc.reportEmbeddingProgress(4);
    doc.markEmbedded();

    assertThat(doc.status()).isEqualTo(DocumentStatus.SUCCEEDED);
    assertThat(doc.processedSegments()).isEqualTo(4);
  }

  // ────────────────────────── 3 · 向量过期（SEG-I7） ──────────────────────────

  /** ★ 分段被改过 ⇒ 向量旧了：状态必须回到「待向量化」，否则检索用旧向量答新内容而界面看不出来。 */
  @Test
  void 分段变更后向量过期() {
    Document doc = DocumentStateMachineTest.embeddingSucceeded();
    assertThat(doc.status()).isEqualTo(DocumentStatus.SUCCEEDED);

    doc.markVectorsStale();

    assertThat(doc.status()).isEqualTo(DocumentStatus.PENDING_EMBEDDING);
    assertThat(doc.processedSegments()).isZero();
  }

  /** 已经在待向量化时再标一次过期是 no-op（重复编辑不该报错）。 */
  @Test
  void 已待向量化时再标过期不报错() {
    Document doc = segmented(4);
    doc.markVectorsStale();
    assertThat(doc.status()).isEqualTo(DocumentStatus.PENDING_EMBEDDING);
  }

  /** ★ 向量化进行中**不许**重新分块 —— 那会把正在跑的那一轮的「段数口径」换掉（进度分母变了）。 要重分块必须先让它回到「待向量化」，这道门槛是刻意设的。 */
  @Test
  void 向量化进行中不许重新分块() {
    Document doc = segmented(4);
    doc.startEmbedding();

    assertThatThrownBy(() -> doc.recordSegmentSuccess(9, 2000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMBEDDING");
  }
}
