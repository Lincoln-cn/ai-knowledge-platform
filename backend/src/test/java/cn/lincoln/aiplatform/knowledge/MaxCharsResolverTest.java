package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseService;
import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseView;
import cn.lincoln.aiplatform.knowledge.application.MaxCharsResolver;
import cn.lincoln.aiplatform.knowledge.domain.ModelCapabilityPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★★ 分段字数上限的口径判据（`#172`）—— **`min(配置, 模型预算)`**。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>改动前 `MaxCharsResolver` 是「模型有窗口就**用窗口**」（代码直接 `return window`）。两个问题：
 *
 * <ol>
 *   <li>★ <b>窗口是 token、不是字数</b>：512 token 的中文模型安全字数约 <b>400</b>；
 *       超出部分<b>不参与向量</b>，命中率下降而<b>不报错</b>（静默劣化）；
 *   <li>★ <b>它覆盖了配置</b>：配置写 1200 也被窗口顶掉 ⇒ 配置项对本地模型彻底失效。
 * </ol>
 *
 * <p>⇒ 本类钉住三件事：<b>收口生效</b>（1200 + 512 窗口 ⇒ 399）、<b>取 min 不是取窗口</b>
 * （配置比预算小就听配置）、<b>查不到就回落</b>（不许因为查不到而下限变 0 —— 那会把文档切成碎片）。
 *
 * <p>★ 金丝雀：把 {@code Math.min(fallback, budget)} 改回 {@code budget} ⇒ 第二条用例必红。
 */
class MaxCharsResolverTest {

  private static final UUID KB = UUID.randomUUID();
  private static final UUID MODEL = UUID.randomUUID();

  /** 造一个"窗口已知"的解析器。 */
  private MaxCharsResolver withWindow(Integer window) {
    KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    // ★ 只 mock 一个方法就够：本类要的只是"这个库绑了哪个向量模型"。
    //   `KnowledgeBaseView` 是个 20 个分量的 record，逐字段构造会让这条判据
    //   一改字段就编译不过 —— 那是**判据与无关改动耦合**，不是本类要守的东西。
    KnowledgeBaseView view = mock(KnowledgeBaseView.class);
    when(view.embeddingModelId()).thenReturn(MODEL.toString());
    when(bases.get(KB)).thenReturn(view);

    ModelCapabilityPort caps = mock(ModelCapabilityPort.class);
    when(caps.contextWindow(any())).thenReturn(window);
    return new MaxCharsResolver(bases, caps);
  }

  @Test
  void modelBudgetCapsTheConfiguredValue() {
    // ★ 配置 1200、窗口 512 ⇒ 必须被压到 399（floor(512 × 0.78)），而不是 512、也不是 1200
    assertThat(withWindow(512).resolve(KB, 1200))
        .as(
            "★★ 配置 1200 + 512 token 窗口 ⇒ 实际必须是 **399**。若得到 512 说明还在把 token 当字数；"
                + "若得到 1200 说明配置赢了模型能力 —— 两种都会让尾部正文**静默不进向量**")
        .isEqualTo(399);
  }

  @Test
  void configuredValueWinsWhenItIsSmallerThanTheBudget() {
    // ★ 取 min：配置 300 < 预算 399 ⇒ 听配置（用户想切更碎，是他的自由）
    assertThat(withWindow(512).resolve(KB, 300))
        .as("★ 取 min 而不是取窗口 —— 配置比模型预算小时必须听配置")
        .isEqualTo(300);
  }

  @Test
  void unknownWindowFallsBackToTheConfiguredValue() {
    assertThat(withWindow(null).resolve(KB, 1200))
        .as("★ 查不到窗口 ⇒ 回落配置（不是 0、不是抛异常）——把下限算成 0 会把文档切成碎片")
        .isEqualTo(1200);
  }

  @Test
  void invisibleKnowledgeBaseFallsBackInsteadOfFailing() {
    KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    when(bases.get(KB)).thenThrow(new RuntimeException("看不见"));
    ModelCapabilityPort caps = mock(ModelCapabilityPort.class);

    assertThat(new MaxCharsResolver(bases, caps).resolve(KB, 1200))
        .as("★ 库不可见/模型不存在 ⇒ 回落配置，不阻断入库（这条是尽力而为的查询）")
        .isEqualTo(1200);
  }
}
