package cn.lincoln.aiplatform.modelcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 重排模型状态（`#352`）。
 *
 * <p>★ 这份测试守的是「界面上那条卡片说的话是真的」：窗口/权重/文件/原因四格，任何一格开始撒谎都该红。 它不测模型效果（那是 {@code RerankProperties}
 * 类注释里那四组离线对照的事）。
 */
class RerankStatusServiceTest {

  private static RerankProperties props(boolean enabled, String modelPath, String tokenizerPath) {
    return new RerankProperties(enabled, 10, modelPath, tokenizerPath, false, 5, 5);
  }

  @Test
  void reportsTheWindowAndTheQuantisation(@TempDir Path dir) throws IOException {
    Path model = Files.writeString(dir.resolve("model.onnx"), "fake");
    Path tokenizer = Files.writeString(dir.resolve("tokenizer.json"), "fake");

    RerankStatusService.RerankStatus status =
        new RerankStatusService(props(true, model.toString(), tokenizer.toString())).status();

    assertThat(status.enabled()).isTrue();
    assertThat(status.filesPresent()).isTrue();
    assertThat(status.reason()).isNull();
    assertThat(status.topK()).isEqualTo(10);
    assertThat(status.weights()).isEqualTo("fp32");
    assertThat(status.model()).isEqualTo(dir.getFileName().toString());
  }

  @Test
  void intIsReportedWhenTheFileNameSaysSo(@TempDir Path dir) throws IOException {
    Path model = Files.writeString(dir.resolve("model_int8.onnx"), "fake");
    Path tokenizer = Files.writeString(dir.resolve("tokenizer.json"), "fake");

    RerankStatusService.RerankStatus status =
        new RerankStatusService(props(true, model.toString(), tokenizer.toString())).status();

    assertThat(status.weights()).isEqualTo("int8");
  }

  @Test
  void missingFilesAreReportedWithThePathItLookedAt(@TempDir Path dir) {
    String model = dir.resolve("no-such-model.onnx").toString();
    String tokenizer = dir.resolve("no-such-tokenizer.json").toString();

    RerankStatusService.RerankStatus status =
        new RerankStatusService(props(true, model, tokenizer)).status();

    assertThat(status.filesPresent()).isFalse();
    assertThat(status.reason()).contains("模型文件缺失").contains(model);
  }

  /**
   * 未启用时，原因必须是「未启用」而不是「文件缺失」。
   *
   * <p>★ 这两句话对读者的含义完全不同：前者是"你关掉了"，后者是"你该装的没装"。混在一起说等于让人去查错方向。
   */
  @Test
  void disabledWinsOverMissingFiles(@TempDir Path dir) {
    String model = dir.resolve("no-such-model.onnx").toString();
    String tokenizer = dir.resolve("no-such-tokenizer.json").toString();

    RerankStatusService.RerankStatus status =
        new RerankStatusService(props(false, model, tokenizer)).status();

    assertThat(status.enabled()).isFalse();
    assertThat(status.reason()).contains("未启用").doesNotContain("缺失");
  }
}
