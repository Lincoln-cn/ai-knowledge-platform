package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalOnnxEmbedding#hasModelFilesFor(String)} 的判据（{@code #305} 缺口 A）。
 *
 * <p>★ 这一条测的不是"能不能向量化"，而是"**判断走错路的那一行日志有没有可靠的依据**"： {@code app.embedding.local.model-name} 默认是
 * {@code multilingual-e5-small}，而 {@code models/bge-small-zh-v1.5/} 里有一套完整的模型文件。用户把模型行的 {@code
 * baseModel} 填成后者时，路由按名字判定"不归本地" ⇒ 静默走上游 —— 上游回来的是另一种维度的向量，**检索照样有结果、只是全不对**，而日志里一个字都没有。
 *
 * <p>本测试只回答一件事：**磁盘上到底有没有那套文件**。它不启动 ONNX 推理（那是 {@link LocalOnnxEmbeddingTest} 的事，需要真实模型与
 * Testcontainers），所以它在任何机器上都跑得起来。
 */
class LocalOnnxEmbeddingModelFilesTest {

  /** 用仓库里真实的那份配置造一个实例 —— 路径不硬编码，与 {@code application.yml} 的缺省值一致。 */
  private static LocalOnnxEmbedding instance() {
    return new LocalOnnxEmbedding(
        true,
        "models/multilingual-e5-small/model_quantized.onnx",
        "models/multilingual-e5-small/tokenizer.json",
        "multilingual-e5-small");
  }

  @Test
  @DisplayName("另一个基础模型的文件在磁盘上 ⇒ 认得出（这正是 #305 要报警的那种情形）")
  void anotherModelsFilesAreFoundOnDisk() {
    LocalOnnxEmbedding local = instance();

    assertThat(local.serves("bge-small-zh-v1.5"))
        .as("路由按名字判定：它不是本地服务的那个名字 —— 所以下面那行 WARN 才有意义")
        .isFalse();
    assertThat(local.hasModelFilesFor("bge-small-zh-v1.5"))
        .as("models/bge-small-zh-v1.5/ 里有 model_quantized.onnx 与 tokenizer.json ⇒ 必须认得出")
        .isTrue();
  }

  @Test
  @DisplayName("本地服务的那个名字当然也认得出")
  void theServedModelsOwnFilesAreFound() {
    LocalOnnxEmbedding local = instance();

    assertThat(local.serves("multilingual-e5-small")).isTrue();
    assertThat(local.hasModelFilesFor("multilingual-e5-small")).isTrue();
  }

  @Test
  @DisplayName("名字不存在 / 空 / null ⇒ 认不出（不能把「没有」说成「有」）")
  void nothingIsReportedForUnknownNames() {
    LocalOnnxEmbedding local = instance();

    assertThat(local.hasModelFilesFor("完全不存在的模型")).isFalse();
    assertThat(local.hasModelFilesFor("")).isFalse();
    assertThat(local.hasModelFilesFor("   ")).isFalse();
    assertThat(local.hasModelFilesFor(null)).isFalse();
  }

  @Test
  @DisplayName("名字带前后空格也要认得出（用户在表单里粘进来的值常有空格）")
  void surroundingSpacesDoNotDefeatTheLookup() {
    LocalOnnxEmbedding local = instance();

    assertThat(local.hasModelFilesFor("  bge-small-zh-v1.5  ")).isTrue();
  }
}
