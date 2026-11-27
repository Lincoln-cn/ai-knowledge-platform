package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.platform.knowledge.EntityRecognizerStatusPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link EntityRecognizerStatusPort} 的 knowledge 侧实现（`#369`）。
 *
 * <p>★ 它只读三样东西：配置原值、磁盘上三个文件、识别器自己的 {@code loaded()} / {@code loadFailure()}。 不触发加载 —— 打开模型页不该顺手把
 * 400MB 的 onnx 读进内存。
 */
@Component
class EntityRecognizerStatusAdapter implements EntityRecognizerStatusPort {

  private final EntityRecognizer recognizer;
  private final String configured;
  private final String modelDir;

  EntityRecognizerStatusAdapter(
      EntityRecognizer recognizer,
      @Value("${app.entity.recognizer:onnx}") String configured,
      @Value("${app.entity.onnx.model-dir:models/bert-chinese-ner-onnx}") String modelDir) {
    this.recognizer = recognizer;
    this.configured = configured;
    this.modelDir = modelDir;
  }

  @Override
  public Status status() {
    if (!(recognizer instanceof OnnxNerRecognizer onnx)) {
      // HanLP：没有模型文件这一说，分词器在 jar 里。
      return new Status(
          recognizer.name(),
          configured,
          "",
          true,
          true,
          true,
          true,
          recognizer.supportedTypes(),
          null);
    }
    Path dir = resolve(modelDir);
    boolean modelFile = Files.isRegularFile(dir.resolve("model.onnx"));
    boolean tokenizer = Files.isRegularFile(dir.resolve("tokenizer.json"));
    boolean configFile = Files.isRegularFile(dir.resolve("config.json"));
    // ★ 三个文件缺哪个要说清：缺 config.json 时"文件在、加载失败"会让人去查 onnx 运行时，
    //   而真正的原因是标签表读不到（见 OnnxNerRecognizer 类注释里那次踩坑）。
    List<String> missing = new ArrayList<>();
    if (!modelFile) {
      missing.add("model.onnx");
    }
    if (!tokenizer) {
      missing.add("tokenizer.json");
    }
    if (!configFile) {
      missing.add("config.json");
    }
    String reason;
    if (onnx.loadFailure() != null) {
      reason = onnx.loadFailure();
    } else if (!missing.isEmpty()) {
      reason = "模型文件缺失：" + dir.toAbsolutePath() + "（缺 " + String.join(" / ", missing) + "）";
    } else {
      reason = null;
    }
    return new Status(
        recognizer.name(),
        configured,
        dir.toAbsolutePath().toString(),
        modelFile,
        tokenizer,
        configFile,
        onnx.loaded(),
        recognizer.supportedTypes(),
        reason);
  }

  /**
   * 相对路径按"当前工作目录 → 上一层"解析。
   *
   * <p>与 {@code RerankStatusService#resolve} 同款：后端可能从 {@code backend/} 启动，也可能是仓库根，
   * 两种工作目录下都要能找到默认的相对路径。
   */
  private static Path resolve(String configured) {
    Path configuredPath = Path.of(configured);
    if (configuredPath.isAbsolute() || Files.exists(configuredPath)) {
      return configuredPath;
    }
    Path fromParent = Path.of("..").resolve(configured).normalize();
    return Files.exists(fromParent) ? fromParent : configuredPath;
  }
}
