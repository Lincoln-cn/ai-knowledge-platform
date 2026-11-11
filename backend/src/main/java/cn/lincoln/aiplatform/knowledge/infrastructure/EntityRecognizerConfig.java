package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实体识别器的装配：一个配置项决定用哪一个。
 *
 * <p>★ 为什么要有这个类：{@link OnnxNerRecognizer} 的构造需要一个模型目录，而目录是部署 参数、不是常量 —— 它没法像 {@link
 * HanlpEntityRecognizer} 那样自己 {@code @Component} 上去。 所以 ONNX 这一路在这里显式建 bean，HanLP 那一路用
 * {@code @ConditionalOnProperty} 关掉。
 *
 * <p>★ 默认是 {@code onnx}：2026-11-11 在 30 段人工验证集上的读数是 ONNX F1 98.7% / HanLP 49.4%（见 {@code
 * backend/tools/ner_testset.jsonl} 与 {@code sla/probes/ner.py}）。 HanLP 保留为可切换实现 —— 它的分词对关系抽取仍然有用，
 * 而且换回它只需要改一行配置。
 *
 * <p>★ 配错会**拒绝启动**而不是静默退回 HanLP：{@code app.entity.recognizer=nogood} 时 没有任何 bean 满足 {@code
 * EntityRecognizer}， {@code EntityExtractionService} 构造失败。 这是有意的 —— 静默退回意味着"你以为在跑 ONNX，实际在跑 HanLP"，
 * 而两者的读数差 49 个百分点，这种误会不会有任何提示。
 */
@Configuration
public class EntityRecognizerConfig {

  /**
   * ONNX BERT NER。
   *
   * @param modelDir 模型目录（`model.onnx` + `tokenizer.json` + `config.json`）
   * @param threads 推理线程数，0 = 交给 ONNX Runtime 按核数决定
   * @param dictionaryPort 后缀表来源（与 HanLP 那一路同一份，保证两个实现的过滤口径一致）
   */
  @Bean
  @ConditionalOnProperty(
      name = "app.entity.recognizer",
      havingValue = "onnx",
      matchIfMissing = true)
  EntityRecognizer onnxEntityRecognizer(
      @Value("${app.entity.onnx.model-dir:models/bert-chinese-ner-onnx}") String modelDir,
      @Value("${app.entity.onnx.threads:0}") int threads,
      EntityDictionaryPort dictionaryPort) {
    // ★ 后缀表用「内置下限」而不是问 dictionaryPort：dictionaryPort 要 kbId，
    //   而识别器是**进程级单例**、构造时还不知道会处理哪个库。per-库的那份由
    //   EntityExtractionService 在后处理层补 —— 与 HanLP 那一路同一口径。
    return new OnnxNerRecognizer(
        Path.of(modelDir), threads, JdbcEntityDictionaryAdapter.builtin().orgSuffixes());
  }
}
