package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ModelCapabilityPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量模型字符上限解析器（第 7 周 · 0.2.2 批 2b）。
 *
 * <p>★ 独立成组件的原因：{@code resolve} 必须跑在 {@code NOT_SUPPORTED} 传播里， 否则内部的 {@code @Transactional}
 * 调用抛异常时会把外层事务标记为 rollback-only。 放在调用方里自调用绕过代理，注解不生效 —— 必须是独立 bean。
 *
 * <p>★ 取值顺序（领域配置，不是常量）：
 *
 * <ol>
 *   <li>KB 绑定的向量模型（{@code embeddingModelId} → {@code Model.contextWindow}）<strong>有则用它</strong> ——
 *       口径：把模型窗口数当字数上限，近似
 *   <li>否则回落配置 {@code app.segmentation.max-chars}，默认 1200 —— 与前端 mock 的 {@code MODEL_LIMIT} 一致
 * </ol>
 */
@Component
public class MaxCharsResolver {

  private static final Logger log = LoggerFactory.getLogger(MaxCharsResolver.class);

  /**
   * token 窗口 → 安全字数的保守比。
   *
   * <p>★ 取值依据（可核对）：本机要接入的 `bge-small-zh-v1.5` 窗口是 **512 token**， `floor(512 × 0.78) = 399` ≈ **400
   * 字** —— 正是"本地 ONNX 强制 400 字"的出处。 见 `models/README.md` §二 与设计册 `03` 的「单分段字符上限」。
   *
   * <p>⚠️ 这是**保守**估计，不是精确值：精确值取决于分词器对具体文本的切分。 保守的代价是"段稍短"，激进的代价是"尾部不进向量且不报错" —— 后者贵得多。
   */
  private static final double CONTEXT_WINDOW_TO_CHARS_RATIO = 0.78;

  private final KnowledgeBaseService knowledgeBases;
  private final ModelCapabilityPort modelCapability;

  public MaxCharsResolver(
      KnowledgeBaseService knowledgeBases, ModelCapabilityPort modelCapability) {
    this.knowledgeBases = knowledgeBases;
    this.modelCapability = modelCapability;
  }

  /**
   * 解析向量模型字符上限。
   *
   * <h3>★★ 一条口径：`min(配置回落值, 模型安全字数预算)`</h3>
   *
   * <p>本方法此前是「模型有窗口就**用窗口**」（注释原话"把模型窗口数当字数上限，近似"， 代码直接 {@code return window}）。那有两个问题：
   *
   * <ol>
   *   <li>★ <b>窗口是 token、不是字数</b>。BERT 系中文分词器里 1 个汉字常常就是 1 个 token， 还要留 {@code [CLS]} / {@code
   *       [SEP]} ⇒ 512 窗口的安全字数约 <b>400</b>，不是 512； 超出的部分<b>不参与向量</b>，检索命中率下降 ——
   *       <b>但不报错</b>（本仓最忌讳的静默劣化）；
   *   <li>★ <b>它覆盖了配置</b>：配置里写 1200 也会被窗口顶掉，配置项对本地模型彻底失效。
   * </ol>
   *
   * <p>⇒ 现在统一取 {@code min}：配置是上限，模型能力是更硬的上限，两者谁小听谁的。 口径见设计册 `03-关键机制设计.md` 的「单分段字符上限：token 窗口 ≠
   * 字数」。
   *
   * <p>★ {@code NOT_SUPPORTED}：内部调用若抛异常，不把外层事务标记为 rollback-only —— 这里只是尽力查一下模型上限，查不到就回落配置。
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int resolve(UUID kbId, int fallback) {
    try {
      var base = knowledgeBases.get(kbId);
      UUID modelId = UUID.fromString(base.embeddingModelId());
      // ★★ 三级退让（顺序不能反）：
      //   ① `max_chars` —— 登记的人写的确切值（单位：字），最可信；
      //   ② `context_window × 0.78` —— 只对**对话模型**有值；向量模型这一列**恒为 null**
      //      （`ai_model` 表注释写死），所以这一级对向量化通常不动；
      //   ③ 配置回落值。
      //   实测教训：本类此前只问 `context_window`，而它在向量模型上永远是 null ⇒
      //   「本地 ONNX 压到 400 字」这条口径**从来没有生效过**，一直是配置在起作用。
      Integer declared = modelCapability.maxChars(modelId);
      if (declared != null && declared > 0) {
        int effective = Math.min(fallback, declared);
        if (effective < fallback) {
          log.info(
              "分段上限被模型登记的字符预算收口：配置={} → 实际={}（模型 {} 的 max_chars={}）",
              fallback,
              effective,
              modelId,
              declared);
        }
        return Math.max(1, effective);
      }
      Integer window = modelCapability.contextWindow(modelId);
      if (window != null && window > 0) {
        // ★ 保守比：0.78 的来历 = 本机要接入的 `bge-small-zh-v1.5`（窗口 512）的安全字数
        //   floor(512 × 0.78) = 399 ≈ 400。见 models/README.md §三。
        int budget = (int) Math.floor(window * CONTEXT_WINDOW_TO_CHARS_RATIO);
        int effective = Math.min(fallback, budget);
        if (effective < fallback) {
          // ★ 压缩必须可见：不打印的话，"为什么这篇文档的段比那篇短"永远查不清
          log.info(
              "分段上限被模型能力收口：配置={} → 实际={}（模型 {} 的窗口 {} token × {}）",
              fallback,
              effective,
              modelId,
              window,
              CONTEXT_WINDOW_TO_CHARS_RATIO);
        }
        return Math.max(1, effective);
      }
    } catch (Exception e) {
      // 模型不存在 / KB 无绑定 / 上游不可达 ⇒ 回落配置，不阻断
      log.debug("取不到向量模型窗口，分段上限回落配置 {}：{}", fallback, e.getMessage());
    }
    return fallback;
  }
}
