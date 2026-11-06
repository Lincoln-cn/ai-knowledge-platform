package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalOnnxEmbedding#hasModelFilesFor(String)} 的判据（{@code #305} 缺口 A）。
 *
 * <p>★ 这一条测的不是"能不能向量化"，而是"**判断走错路的那一行日志有没有可靠的依据**"： {@code app.embedding.local.model-name} 默认是
 * {@code multilingual-e5-small}，而 {@code models/bge-small-zh-v1.5/} 里可能放着一套完整的模型文件。用户把模型行的 {@code
 * baseModel} 填成后者时，路由按名字判定"不归本地" ⇒ 静默走上游 —— 上游回来的是另一种维度的向量，**检索照样有结果、只是全不对**，而日志里一个字都没有。
 *
 * <p>本测试只回答一件事：**磁盘上到底有没有那套文件**。它不启动 ONNX 推理（那是 {@link LocalOnnxEmbeddingTest} 的事，需要真实模型与
 * Testcontainers）。
 *
 * <p>★★ 2026-11-07 订正：**原来它读的是仓库里真实的 {@code models/} 目录**，结果在 CI 上必红（runner 没把 24MB 的 onnx
 * 提交进仓库）。现在一律用 {@link TempDir} 现场造目录结构 —— 判据问的是"给定一个路径，能不能认出来"， 与"仓库里恰好有没有那份文件"无关。
 */
class LocalOnnxEmbeddingModelFilesTest {

  /** 造一个"服务 multilingual-e5-small"的实例，但它指向 {@code root} 下的自造目录。 */
  private static LocalOnnxEmbedding instanceUnder(Path root, String servedName) {
    Path servedDir = root.resolve("models").resolve(servedName);
    return new LocalOnnxEmbedding(
        true,
        servedDir.resolve("model_quantized.onnx").toString(),
        servedDir.resolve("tokenizer.json").toString(),
        servedName);
  }

  /** 在 {@code root/models/<name>/} 下放两个必需文件（内容无所谓，判据只看在不在）。 */
  private static void putModelFiles(Path root, String name) throws IOException {
    Path dir = root.resolve("models").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("model_quantized.onnx"), "fake-onnx");
    Files.writeString(dir.resolve("tokenizer.json"), "{}");
  }

  @Test
  @DisplayName("另一个基础模型的文件在磁盘上 ⇒ 认得出（这正是 #305 要报警的那种情形）")
  void anotherModelsFilesAreFoundOnDisk(@TempDir Path root) throws IOException {
    putModelFiles(root, "multilingual-e5-small");
    putModelFiles(root, "bge-small-zh-v1.5");
    LocalOnnxEmbedding local = instanceUnder(root, "multilingual-e5-small");

    assertThat(local.serves("bge-small-zh-v1.5"))
        .as("路由按名字判定：它不是本地服务的那个名字 —— 所以下面那行 WARN 才有意义")
        .isFalse();
    assertThat(local.hasModelFilesFor("bge-small-zh-v1.5"))
        .as("models/bge-small-zh-v1.5/ 里有 model_quantized.onnx 与 tokenizer.json ⇒ 必须认得出")
        .isTrue();
  }

  @Test
  @DisplayName("本地服务的那个名字当然也认得出")
  void theServedModelsOwnFilesAreFound(@TempDir Path root) throws IOException {
    putModelFiles(root, "multilingual-e5-small");
    LocalOnnxEmbedding local = instanceUnder(root, "multilingual-e5-small");

    assertThat(local.serves("multilingual-e5-small")).isTrue();
    assertThat(local.hasModelFilesFor("multilingual-e5-small")).isTrue();
  }

  @Test
  @DisplayName("名字不存在 / 空 / null ⇒ 认不出（不能把「没有」说成「有」）")
  void nothingIsReportedForUnknownNames(@TempDir Path root) throws IOException {
    putModelFiles(root, "multilingual-e5-small");
    LocalOnnxEmbedding local = instanceUnder(root, "multilingual-e5-small");

    assertThat(local.hasModelFilesFor("不存在的模型")).isFalse();
    assertThat(local.hasModelFilesFor("")).isFalse();
    assertThat(local.hasModelFilesFor("   ")).isFalse();
    assertThat(local.hasModelFilesFor(null)).isFalse();
  }

  @Test
  @DisplayName("前后空格不影响判定（用户手填的 baseModel 常带空格）")
  void surroundingSpacesDoNotDefeatTheLookup(@TempDir Path root) throws IOException {
    putModelFiles(root, "bge-small-zh-v1.5");
    LocalOnnxEmbedding local = instanceUnder(root, "multilingual-e5-small");

    assertThat(local.hasModelFilesFor("  bge-small-zh-v1.5  ")).isTrue();
  }

  @Test
  @DisplayName("目录在但缺一个必需文件 ⇒ 认不出（半套文件不是一套文件）")
  void aPartialDirectoryIsNotAModel(@TempDir Path root) throws IOException {
    putModelFiles(root, "multilingual-e5-small");
    Path partial = root.resolve("models").resolve("half-model");
    Files.createDirectories(partial);
    Files.writeString(partial.resolve("model_quantized.onnx"), "fake-onnx");
    LocalOnnxEmbedding local = instanceUnder(root, "multilingual-e5-small");

    assertThat(local.hasModelFilesFor("half-model"))
        .as("只有 onnx 没有 tokenizer ⇒ 起不了向量化，不能报「有」")
        .isFalse();
  }
}
