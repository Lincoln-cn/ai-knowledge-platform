package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettings;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettingsStore;
import cn.lincoln.aiplatform.retrieval.application.OnnxRerankerAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link OnnxRerankerAdapter} 的真实推理测试（{@code BAAI/bge-reranker-base}）。
 *
 * <h3>★ CI 上会被整体跳过（有意的）</h3>
 *
 * <p>模型 {@code model.onnx} 约 1GB，<b>不进仓库</b>（{@code models/*} 被 {@code .gitignore} 忽略）—— CI runner
 * 上没有它。所以每个用例先 {@code assumeTrue} 模型与 tokenizer 都在，不在就跳过 （与 {@code LocalOnnxEmbeddingTest} / {@code
 * ModelCapabilityProbeTest} 同款做法）。 ⇒ <b>本类在 CI 上的读数是 {@code Tests run: N, Skipped:
 * N}</b>，绿不等于判据跑过；真实读数只在放了模型的本机。
 *
 * <h3>★ 判据设计：期望值不照实现抄</h3>
 *
 * <p>断言的是"与问题相关的段落被排到最前"——这个期望来自<b>模型的语义</b>（离线评测 B3 提升的根据）， 与实现内部怎么编码、怎么排序无关。
 * 而且相关段被<b>刻意放在原序最后/窗口里靠后</b>： 重排没生效（返回原序）时它到不了第一位，那条断言立刻红。
 */
class OnnxRerankerAdapterTest {

  private static final Path REPO_ROOT =
      Path.of("models").toFile().isDirectory() ? Path.of(".") : Path.of("..");
  private static final Path MODEL = REPO_ROOT.resolve("models/bge-reranker-base/model.onnx");
  private static final Path TOKENIZER =
      REPO_ROOT.resolve("models/bge-reranker-base/tokenizer.json");
  private static final String QUESTION = "合同违约金的上限是多少？";
  private static final String ON_TOPIC =
      "合同违约金上限：约定的违约金超过造成损失的百分之三十的，一般可以认定为过分高于造成的损失，当事人可以请求适当减少。";
  private static final String OFF_TOPIC_HANDBOOK = "员工手册：年假按工龄折算，入职满一年享有五天带薪年假。";
  private static final String OFF_TOPIC_MENU = "食堂本周菜单：周一是红烧肉，周二是清蒸鱼，周三是土豆炖牛肉。";
  private static final String OFF_TOPIC_MEETING = "会议室预订：三楼大会议室每周五下午用于部门例会。";
  private static final String OFF_TOPIC_PRINTER = "打印机维护：碳粉更换请联系行政部前台登记。";

  @Test
  void rerankPutsTheOnTopicPassageFirst() {
    OnnxRerankerAdapter adapter = newAdapter();
    UUID offTopic1 = seg(1);
    UUID offTopic2 = seg(2);
    UUID onTopic = seg(3);
    // ★ 相关段在原序的**最后**：重排没生效 ⇒ 第一条是 seg(1)（无关段），断言必红
    List<UUID> input = List.of(offTopic1, offTopic2, onTopic);
    Map<UUID, String> texts =
        Map.of(offTopic1, OFF_TOPIC_HANDBOOK, offTopic2, OFF_TOPIC_MENU, onTopic, ON_TOPIC);

    List<UUID> result = adapter.rerank(QUESTION, input, texts, 5);

    assertThat(result).as("相关段（违约金上限）必须被排到最前 —— 没生效时第一条是无关段").first().isEqualTo(onTopic);
    assertThat(result)
        .as("重排结果必须是输入的排列（不丢不增）")
        .containsExactlyInAnyOrder(offTopic1, offTopic2, onTopic);
  }

  @Test
  void onlyTheTopKWindowMoves() {
    OnnxRerankerAdapter adapter = newAdapter();
    UUID a = seg(1);
    UUID b = seg(2);
    UUID c = seg(3);
    UUID d = seg(4);
    UUID e = seg(5);
    // ★ 窗口 = 前 topK=2：只有 [a, b] 的相对顺序可能变；c/d/e 无论模型怎么想都必须原序
    List<UUID> input = List.of(a, b, c, d, e);
    Map<UUID, String> texts =
        Map.of(
            a, OFF_TOPIC_MEETING,
            b, ON_TOPIC,
            c, OFF_TOPIC_PRINTER,
            d, OFF_TOPIC_HANDBOOK,
            e, OFF_TOPIC_MENU);

    List<UUID> result = adapter.rerank(QUESTION, input, texts, 2);

    assertThat(result.subList(2, 5)).as("窗口外的候选必须保持原序（重排只决定谁排最前，不淘汰任何人）").containsExactly(c, d, e);
    assertThat(result).as("窗口内重排后仍是排列").containsExactlyInAnyOrder(a, b, c, d, e);
  }

  /**
   * 构造真实适配器；模型不在（CI）⇒ 跳过本用例。
   *
   * @return 已配置的适配器
   */
  private static OnnxRerankerAdapter newAdapter() {
    Assumptions.assumeTrue(
        MODEL.toFile().isFile() && TOKENIZER.toFile().isFile(),
        "模型文件不在（CI runner 没有 1GB 的 model.onnx）⇒ 跳过依赖真实推理的判据");
    return new OnnxRerankerAdapter(props());
  }

  private static RerankProperties props() {
    return new RerankProperties(true, 5, MODEL.toString(), TOKENIZER.toString(), false, 5, 5, 512);
  }

  /**
   * 判据：截断长度必须<b>从库里读</b>，而且改了之后 tokenizer 真的重建。
   *
   * <p>★ 这条要能红的姿势：把 {@code OnnxRerankerAdapter.maxLength()} 改回只读 {@code properties.maxLength()}，或者把
   * {@code Bundle} 的命中条件改回"有就复用"，第二次断言立刻红。
   *
   * <p>为什么必须测到这一层：{@code HuggingFaceTokenizer.optMaxLength} 在<b>构造时</b>固定，而 tokenizer 是懒加载缓存的 ——
   * 不重建就会出现「界面上从 512 改成 64、实际还按 512 截断」，并且没有任何报错。
   */
  @Test
  void changingMaxLengthAtRuntimeRebuildsTheTokenizer() throws Exception {
    OnnxRerankerAdapter adapter = newAdapter();
    FixedSettings settings = new FixedSettings(engineSettings(512));
    adapter.setEngineSettings(settings);

    UUID a = seg(1);
    UUID b = seg(2);
    List<UUID> input = List.of(a, b);
    Map<UUID, String> texts = Map.of(a, ON_TOPIC, b, OFF_TOPIC_HANDBOOK);

    adapter.rerank(QUESTION, input, texts, 2);
    assertThat(bundleMaxLength(adapter)).as("首次加载按库里的 512").isEqualTo(512);

    settings.value = engineSettings(64);
    adapter.rerank(QUESTION, input, texts, 2);
    assertThat(bundleMaxLength(adapter))
        .as("★★ 库里把截断长度改成 64 之后 tokenizer 必须重建 —— 这条红了就是「界面上改了、实际还按 512 跑」")
        .isEqualTo(64);
  }

  private static UUID seg(int value) {
    return UUID.fromString(String.format("50000000-0000-4000-8000-%012d", value));
  }

  private static RetrievalEngineSettings engineSettings(int maxLength) {
    return new RetrievalEngineSettings(true, true, true, 10, 5, maxLength, false, 5, null);
  }

  /**
   * 只覆盖 {@code current()} 的替身。
   *
   * <p>构造时传 {@code null} 的 {@code JdbcTemplate} 是安全的：本类从不调 {@code loadOrFallback()}，因此永远不碰库。
   */
  private static final class FixedSettings extends RetrievalEngineSettingsStore {

    private RetrievalEngineSettings value;

    FixedSettings(RetrievalEngineSettings initial) {
      super(null, props(), new RetrievalBudgetProperties(5000, 5000, 0.6, false, false));
      this.value = initial;
    }

    @Override
    public RetrievalEngineSettings current() {
      return value;
    }
  }

  /** 读适配器里缓存的那个 {@code Bundle} 记下的截断长度（不重跑推理就能看出实际用的是多少）。 */
  private static int bundleMaxLength(OnnxRerankerAdapter adapter) throws Exception {
    java.lang.reflect.Field field = OnnxRerankerAdapter.class.getDeclaredField("bundle");
    field.setAccessible(true);
    Object reference = field.get(adapter);
    Object bundle = ((java.util.concurrent.atomic.AtomicReference<?>) reference).get();
    assertThat(bundle).as("重排跑过一次之后 bundle 必须已经建出来").isNotNull();
    java.lang.reflect.Method method = bundle.getClass().getDeclaredMethod("maxLength");
    method.setAccessible(true);
    return (int) method.invoke(bundle);
  }
}
