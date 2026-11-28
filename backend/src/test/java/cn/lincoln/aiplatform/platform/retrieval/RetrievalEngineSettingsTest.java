package cn.lincoln.aiplatform.platform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 配置页那几格的范围校验（`platform/V100`）。
 *
 * <p>★ 为什么这些断言值得写：四个上限（窗口 50 / 批 100 / 截断长度 32–2048 / listwise 20）在**三处**被引用 —— 迁移里的 `check` 约束、这个
 * record 的常量、页面上的提示。任何一处单独松动，另两处不会报错， 只会表现为"页面上能填 500、后端接受、重排把 500 条一次喂进去"。所以校验必须有一条能红的断言。
 */
class RetrievalEngineSettingsTest {

  private static RetrievalEngineSettings of(int topK, int forwardBatch, int listwiseTopK) {
    return new RetrievalEngineSettings(
        true, true, true, topK, forwardBatch, 512, false, listwiseTopK, null);
  }

  @Test
  void acceptsTheBoundaryValues() {
    // ★ 边界值本身必须合法：`check (rerank_top_k between 1 and 50)` 与常量是同一个上限，
    //   如果这里写成 `<` 而迁移写 `<=`，那 50 就会在页面上被接受、在库上被拒。
    assertThat(
            of(RetrievalEngineSettings.MAX_TOP_K, 0, RetrievalEngineSettings.MAX_LISTWISE_TOP_K)
                .checked()
                .rerankTopK())
        .isEqualTo(50);
  }

  @Test
  void rejectsTopKAboveTheCap() {
    assertThatThrownBy(() -> of(RetrievalEngineSettings.MAX_TOP_K + 1, 5, 5).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重排窗口")
        .hasMessageContaining("51");
  }

  @Test
  void rejectsTopKBelowOne() {
    assertThatThrownBy(() -> of(0, 5, 5).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重排窗口")
        .hasMessageContaining("现在写的是 0");
  }

  @Test
  void forwardBatchZeroIsLegalBecauseItMeansWholeWindow() {
    // ★ 0 是**有语义**的合法值（整个窗口一次前向），不是"没填"。
    assertThat(of(10, 0, 5).checked().rerankForwardBatch()).isZero();
  }

  @Test
  void rejectsForwardBatchAboveTheCap() {
    assertThatThrownBy(() -> of(10, RetrievalEngineSettings.MAX_FORWARD_BATCH + 1, 5).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前向批大小")
        .hasMessageContaining("101");
  }

  @Test
  void rejectsNegativeForwardBatchBecauseMathMinWouldSilentlyPassItThrough() {
    // ★ 负数不能只是"非法"，还要说清危害：`batchSize()` 写的是 `configured <= 0 ? window : min(...)`，
    //   负数会走进"整个窗口一次前向"那条分支 —— 看起来能跑，实际上配置被吃掉了。
    assertThatThrownBy(() -> of(10, -1, 5).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前向批大小")
        .hasMessageContaining("现在写的是 -1");
  }

  @Test
  void rejectsListwiseTopKAboveTheCap() {
    assertThatThrownBy(() -> of(10, 5, RetrievalEngineSettings.MAX_LISTWISE_TOP_K + 1).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("listwise")
        .hasMessageContaining("21");
  }

  @Test
  void checkedReturnsItselfSoItCanBeChained() {
    RetrievalEngineSettings value = of(10, 5, 5);
    assertThat(value.checked()).isSameAs(value);
  }

  @Test
  void maxLengthBoundariesAreAccepted() {
    // 32 与 2048 是迁移 check 的两端，必须都被接受：写成 < 而迁移写 <= 会让
    // 「页面上能填 32、保存时被后端拒」反复发生，而两处都读得通。
    assertThat(
            new RetrievalEngineSettings(true, true, true, 10, 5, 32, false, 5, null)
                .checked()
                .rerankMaxLength())
        .isEqualTo(32);
    assertThat(
            new RetrievalEngineSettings(true, true, true, 10, 5, 2048, false, 5, null)
                .checked()
                .rerankMaxLength())
        .isEqualTo(2048);
  }

  @Test
  void maxLengthOutOfRangeIsRejected() {
    // 31 太小（一个短标题都放不下，重排分数退化成噪声），2049 超过模型可用上限。
    assertThatThrownBy(
            () ->
                new RetrievalEngineSettings(true, true, true, 10, 5, 31, false, 5, null).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("截断长度");
    assertThatThrownBy(
            () ->
                new RetrievalEngineSettings(true, true, true, 10, 5, 2049, false, 5, null)
                    .checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("截断长度");
  }

  @Test
  void updatedAtIsCarriedThrough() {
    Instant at = Instant.parse("2026-11-15T05:00:00Z");
    assertThat(
            new RetrievalEngineSettings(true, true, true, 10, 5, 512, false, 5, at)
                .checked()
                .updatedAt())
        .isEqualTo(at);
  }
}
