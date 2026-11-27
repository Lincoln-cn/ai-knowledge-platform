package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.platform.knowledge.EntityRecognizerStatusPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 模型页要显示的 NER 状态（`#369`）的判据。
 *
 * <p>★ 这一类的价值不在"字段都填上了"，而在**不可用时必须说清是缺哪个文件** —— 只显示"文件不存在"会让人去查 onnx 运行时，而真正的原因往往是 `config.json`
 * 没就位 （标签表从它读，见 {@code OnnxNerRecognizer} 类注释里那次踩坑）。
 */
class EntityRecognizerStatusAdapterTest {

  @Test
  @DisplayName("三个文件都在：reason 为空、loaded 为 false（惰性加载，没触发过推理）")
  void readyButNotLoadedUntilFirstInference(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("model.onnx"), "x");
    Files.writeString(dir.resolve("tokenizer.json"), "{}");
    Files.writeString(dir.resolve("config.json"), "{\"id2label\":{}}");
    EntityRecognizerStatusAdapter adapter =
        new EntityRecognizerStatusAdapter(new OnnxNerRecognizer(dir, 1), "onnx", dir.toString());

    EntityRecognizerStatusPort.Status status = adapter.status();

    assertThat(status.impl()).isEqualTo("onnx-bert-ner");
    assertThat(status.configured()).isEqualTo("onnx");
    assertThat(status.modelFilePresent()).isTrue();
    assertThat(status.tokenizerPresent()).isTrue();
    assertThat(status.configFilePresent()).isTrue();
    // ★ 惰性加载：只读状态不该把模型读进内存
    assertThat(status.loaded()).isFalse();
    assertThat(status.reason()).isNull();
    assertThat(status.supportedTypes()).containsExactly("ORG", "PERSON");
  }

  @Test
  @DisplayName("缺 config.json：reason 要点名这个文件，而不是笼统说『文件缺失』")
  void namesTheMissingFile(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("model.onnx"), "x");
    Files.writeString(dir.resolve("tokenizer.json"), "{}");
    EntityRecognizerStatusAdapter adapter =
        new EntityRecognizerStatusAdapter(new OnnxNerRecognizer(dir, 1), "onnx", dir.toString());

    EntityRecognizerStatusPort.Status status = adapter.status();

    assertThat(status.configFilePresent()).isFalse();
    assertThat(status.reason()).contains("模型文件缺失").contains("config.json");
    // 缺一个就不是可用状态，哪怕另外两个都在
    assertThat(status.reason()).doesNotContain("tokenizer.json");
  }

  @Test
  @DisplayName("HanLP 实现：没有模型目录这一说，不给假的『文件缺失』")
  void hanlpHasNoModelFiles() {
    EntityRecognizer hanlp =
        new EntityRecognizer() {
          @Override
          public List<Recognized> recognize(String text) {
            return List.of();
          }

          @Override
          public List<String> supportedTypes() {
            return List.of("ORG", "PERSON");
          }

          @Override
          public String name() {
            return "hanlp";
          }
        };
    EntityRecognizerStatusAdapter adapter =
        new EntityRecognizerStatusAdapter(hanlp, "hanlp", "models/bert-chinese-ner-onnx");

    EntityRecognizerStatusPort.Status status = adapter.status();

    assertThat(status.impl()).isEqualTo("hanlp");
    assertThat(status.configured()).isEqualTo("hanlp");
    assertThat(status.modelDir()).isEmpty();
    assertThat(status.reason()).isNull();
  }
}
