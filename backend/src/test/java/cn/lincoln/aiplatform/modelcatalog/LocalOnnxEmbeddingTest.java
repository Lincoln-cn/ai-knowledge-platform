package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ 本地 ONNX 向量化的判据（`#172`）—— 「不再需要 11434」这件事的读数。
 *
 * <h3>四条判据</h3>
 *
 * <ol>
 *   <li>★ <b>维度 = 512</b>：`bge-small-zh-v1.5` 是 512 维，而本机 `vector(768)` 是另一维 ——
 *       维度错了<b>不会报错</b>，只会写错表或写失败，所以必须直接断言向量长度；
 *   <li><b>同一文本两次结果一致</b>：不是随机向量（桩给的是随机向量，这是本质差别）；
 *   <li><b>不同文本结果不同 + 不是全零</b>：全零是"模型没真的跑"的典型症状；
 *   <li>★ <b>批量顺序 == 入参顺序</b>：`EmbeddingPort` 契约里最贵的一条 —— 顺序错配不报错， 只会让检索返回"别的内容"的邻居。
 * </ol>
 *
 * <h3>★★ 依赖真实推理的三条在 CI 里会 **skip**，不是绿</h3>
 *
 * <p>模型（23 MB）不进仓库，CI 上没有它 ⇒ 这三条用 {@link Assumptions#assumeTrue} 跳过， 并带上可读原因。★
 * 这不是把红藏起来：文件在时它们必须绿（本机已绿）， 文件不在时状态是**可见的 skip**；若哪天模型进了仓库，它们会自动开始守 CI。
 *
 * <p>★ 而 {@code missingModelFailsWithAReadableReason} <b>不</b>跳过 ——
 * 它验的是"文件缺失时必须给可读错误、不许静默回退"，**恰恰要在没有模型的环境里验**。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
    properties = {
      "app.embedding.local.enabled=true",
      "app.embedding.local.model-path=models/bge-small-zh-v1.5/model_quantized.onnx",
      "app.embedding.local.tokenizer-path=models/bge-small-zh-v1.5/tokenizer.json",
      "app.embedding.local.model-name=bge-small-zh-v1.5"
    })
class LocalOnnxEmbeddingTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /**
   * 仓库根：模型的落点是**仓库根**的 `models/`，而 surefire 的 CWD 是 `backend/`。
   *
   * <p>⇒ 先看 CWD 下有没有 `models/`，没有再向上找一层。
   */
  private static final Path REPO_ROOT =
      Path.of("models").toFile().isDirectory() ? Path.of(".") : Path.of("..");

  private static final Path MODEL =
      REPO_ROOT.resolve("models/bge-small-zh-v1.5/model_quantized.onnx");
  private static final Path TOKENIZER =
      REPO_ROOT.resolve("models/bge-small-zh-v1.5/tokenizer.json");

  @Autowired LocalOnnxEmbedding local;

  /** 模型文件不在 ⇒ 跳过依赖真实推理的判据（原因写进 skip 消息，见类注释）。 */
  private static void assumeModelPresent() {
    Assumptions.assumeTrue(
        MODEL.toFile().isFile() && TOKENIZER.toFile().isFile(),
        "本地模型文件不在（已 gitignore，见 models/README.md §五）⇒ 跳过依赖真实推理的判据");
  }

  @Test
  void vectorLengthIs512() {
    assumeModelPresent();

    List<float[]> vectors = local.embed(List.of("出差住宿费一天最多报多少"));

    assertThat(vectors).hasSize(1);
    assertThat(vectors.get(0).length)
        .as(
            "★★ 必须是 512 —— `bge-small-zh-v1.5` 是 512 维，而本机 `vector(768)` 是另一维。"
                + "维度错了**不会报错**：要么写进错表，要么写入失败。所以这里直接断言长度")
        .isEqualTo(512);
    assertThat(local.ready()).as("★ 加载成功后 must be ready（健康指示与它同一来源）").isTrue();
    assertThat(local.loadFailure()).as("★ 没有失败原因").isNull();
  }

  @Test
  void sameTextIsStableAndDifferentTextsDiffer() {
    assumeModelPresent();

    float[] first = local.embed(List.of("发票丢了还能报销吗")).get(0);
    float[] again = local.embed(List.of("发票丢了还能报销吗")).get(0);
    float[] other = local.embed(List.of("模型加载失败时应该怎么办")).get(0);

    assertThat(first)
        .as("★★ 同一文本必须得到同一个向量 —— 每次不同说明不是确定性推理（桩那种随机向量会让检索无意义）")
        .containsExactly(again);
    assertThat(first).as("★ 不同文本必须不同 —— 常量/全零向量会让检索退化成『随便返回』").isNotEqualTo(other);
    float sum = 0f;
    for (float value : first) {
      sum += Math.abs(value);
    }
    assertThat(sum).as("★ 全零向量是『模型没真的跑』的典型症状").isGreaterThan(0f);
  }

  @Test
  void batchOrderMatchesInputOrder() {
    assumeModelPresent();

    List<String> texts = List.of("第一个问题", "第二个问题", "第三个问题");

    List<float[]> batch = local.embed(texts);

    assertThat(batch).hasSize(3);
    for (int i = 0; i < texts.size(); i++) {
      float[] single = local.embed(List.of(texts.get(i))).get(0);
      assertThat(batch.get(i))
          .as(
              "★★ 批量的第 %d 个向量必须等于单独算第 %d 条的结果 —— 顺序错配**不会报错**，"
                  + "只会让检索返回『别的内容』的邻居（EmbeddingPort 契约里最贵的一条）",
              i, i)
          .containsExactly(single);
    }
  }

  @Test
  void missingModelFailsWithAReadableReason() {
    // ★ 这条**不**跳过：它要在没有模型文件的环境里也能验
    LocalOnnxEmbedding broken =
        new LocalOnnxEmbedding(
            true, "models/does-not-exist.onnx", "models/does-not-exist.json", "bge-small-zh-v1.5");

    BusinessException error =
        catchThrowableOfType(() -> broken.embed(List.of("随便")), BusinessException.class);

    assertThat(error).as("★★ 文件缺失必须抛业务异常（可读），不许 500、不许返回空向量").isNotNull();
    assertThat(error.getMessage()).as("★ 原因里要有路径，否则用户不知道该放哪个文件").contains("does-not-exist.onnx");
    assertThat(error.hint()).as("★ hint 要说『不回退上游』—— 静默回退会让两个向量空间混用，检索照样有结果、只是全错").contains("不会回退");
    assertThat(broken.ready()).isFalse();
    assertThat(broken.loadFailure()).as("★ 失败原因要留下来（健康指示要用）").isNotNull();

    BusinessException second =
        catchThrowableOfType(() -> broken.embed(List.of("再试一次")), BusinessException.class);
    assertThat(second).as("★ 二次调用仍失败（失败被缓存，不重复昂贵的初始化）").isNotNull();
    assertThat(second.getMessage()).isEqualTo(error.getMessage());
  }
}
