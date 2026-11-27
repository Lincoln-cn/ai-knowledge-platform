package cn.lincoln.aiplatform.platform.knowledge;

import java.util.List;

/**
 * 实体识别器的<strong>只读</strong>状态端口（`#369`）。
 *
 * <h3>为什么这个接口在 platform 而不是 knowledge</h3>
 *
 * <p>要显示它的是模型页，而模型页属于 {@code modelcatalog} —— 那个模块的 {@code @ApplicationModule(allowedDependencies =
 * {"platform"})} 只有 {@code platform}。 「模型页要显示 NER 状态」这件事，与当年 rerank 的 {@code RerankStatusService}
 * 是同一类： 显示它的模块不拥有它。
 *
 * <p>所以按「SPI 放 platform、实现放提供方模块」：接口与 record 在这里，实现 {@code
 * knowledge/infrastructure/EntityRecognizerStatusAdapter} 由 knowledge 提供。
 *
 * <h3>为什么是只读</h3>
 *
 * <p>NER 不是注册模型：{@code ai_model} 里没有这一行、{@code ModelKind} 里也没有它。 换模型 = 换 onnx 文件 + 改 {@code
 * app.entity.onnx.model-dir} + 重启，不是改一个字符串。 做成可编辑的假模型会让人以为「改个名字就换了模型」。
 */
public interface EntityRecognizerStatusPort {

  /** 当前进程里实体识别器的真实状态（读配置 + 磁盘 + 已加载与否，不查库）。 */
  Status status();

  /**
   * 实体识别器的只读状态。
   *
   * @param impl 实现名（{@code onnx-bert-ner} / {@code hanlp}），来自 {@code EntityRecognizer#name()}
   * @param configured 配置原值（{@code app.entity.recognizer}）—— 与 {@code impl} 分开：配成 {@code hanlp} 却在跑
   *     onnx 这种事，只有把两个值都摆出来才看得出来
   * @param modelDir 解析后的模型目录（hanlp 实现为空串）
   * @param modelFilePresent {@code model.onnx} 是否在
   * @param tokenizerPresent {@code tokenizer.json} 是否在
   * @param configFilePresent {@code config.json} 是否在（★ 标签表从这里读，缺了必然抽不出实体）
   * @param loaded 当前进程是否已把模型载入内存（onnx 惰性加载，第一次抽取时才读）
   * @param supportedTypes 支持的类型（与 {@code recognized} 的 {@code type} 对齐）
   * @param reason 可用时为 {@code null}；否则是不可用的人类可读原因（文件缺失 / 加载失败原文）
   */
  record Status(
      String impl,
      String configured,
      String modelDir,
      boolean modelFilePresent,
      boolean tokenizerPresent,
      boolean configFilePresent,
      boolean loaded,
      List<String> supportedTypes,
      String reason) {}
}
