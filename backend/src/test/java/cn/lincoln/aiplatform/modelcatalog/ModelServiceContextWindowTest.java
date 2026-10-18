package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.application.ModelService;
import cn.lincoln.aiplatform.modelcatalog.application.ModelView;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.CapabilityProber;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ★★ 注册对话模型时「上游清单里没有 contextWindow」不许把注册打断。
 *
 * <h3>这个缺陷的真实症状</h3>
 *
 * <p>把任何 **Ollama** 模型注册进平台都返回 {@code 500 服务内部错误}（`POST /api/v1/models`）， 而除了日志里的堆栈之外**完全看不出原因**：
 *
 * <pre>
 * java.lang.NullPointerException
 *   at ModelService.lookupContextWindow(ModelService.java:429)
 * </pre>
 *
 * <h3>根因：`Stream.findFirst()` 遇到 null 元素会抛 NPE</h3>
 *
 * <p>{@code contextWindow} 是 {@code Integer}，而 <b>多数上游根本不返回它</b> （实测 Ollama 的 {@code /v1/models} 只有
 * id / created / owned_by）。 于是这个 null 顺着 {@code map} 流下去，在 {@code findFirst()} 处被 {@code Optional}
 * 的"不接受 null"规则炸掉：
 *
 * <pre>
 * gateway.listModels(...).stream()
 *     .filter(m -&gt; baseModel.equals(m.id()))
 *     .map(UpstreamGateway.UpstreamModel::contextWindow)   // ← null 在这里产生
 *     .findFirst()                                          // ← NPE 在这里
 * </pre>
 *
 * <p>★ 而这段代码的注释写的是 best-effort（"上游不可达时返回 null —— 不阻断注册"） ⇒ **注释承诺的行为与实现能处理的情况不一致**，这就是它藏了这么久的原因。
 *
 * <p>★ 金丝雀：把 {@code .filter(Objects::nonNull)} 删掉 ⇒ 本类必红（NPE 直穿）。
 */
class ModelServiceContextWindowTest {

  private static final UUID OPERATOR = UUID.randomUUID();

  /** 造一个 ModelService，只关心"上游说这个模型没有 contextWindow"。 */
  private ModelService serviceWith(UpstreamGateway gateway) {
    return new ModelService(
        mock(ModelRepository.class),
        mock(ApiKeyCipher.class),
        mock(CapabilityProber.class),
        gateway,
        mock(PlatformTransactionManager.class));
  }

  private ModelService.UpsertCommand chatCommand() {
    return new ModelService.UpsertCommand(
        "qwen2.5-1.5b-本地",
        ModelKind.CHAT,
        Supplier.LOCAL_INFERENCE,
        "qwen2.5:1.5b",
        "http://127.0.0.1:11434/v1",
        null,
        null,
        null,
        ThinkingMode.NONE,
        ToolsMode.UNSUPPORTED,
        "本地测试用");
  }

  @Test
  void chatModelWithoutContextWindowInUpstreamListStillRegisters() {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any()))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("qwen2.5:1.5b", null, null, null)));

    ModelService service = serviceWith(gateway);

    assertThatCode(() -> service.create(chatCommand(), OPERATOR))
        .as(
            "★★ 上游清单里 contextWindow 为 null 时，注册必须成功 —— 真实症状是「注册任何 Ollama 模型都 500」，而 500 的堆栈是"
                + " `NullPointerException at lookupContextWindow`（Stream.findFirst 不接受 null 元素）")
        .doesNotThrowAnyException();
  }

  @Test
  void contextWindowIsAdoptedWhenUpstreamProvidesIt() {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any()))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("qwen2.5:1.5b", 32768, null, null)));

    ModelService service = serviceWith(gateway);

    // ★ 有值时会走到"建 Model + 校验容量"这条正常路径；这里只钉住"不炸"
    assertThatCode(() -> service.create(chatCommand(), OPERATOR)).doesNotThrowAnyException();
  }

  @Test
  void upstreamReturningGarbageDoesNotBlockRegistration() {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    // ★ 上游返回"异形数据"不是"不可达" —— 但同样不该阻断注册（方法语义就是 best-effort）
    // ★ 只 stub 一次：`any(), any()` 已覆盖全部参数；再 stub 一次会在**打桩时**就抛出异常
    //   （Mockito 执行第二个 when(...) 时会真的调一次 mock）
    when(gateway.listModels(any(), any())).thenThrow(new IllegalStateException("上游清单形状不认识"));

    ModelService service = serviceWith(gateway);

    assertThatCode(() -> service.create(chatCommand(), OPERATOR))
        .as("★ best-effort：查窗口失败不该阻断注册（放宽到 RuntimeException 之后才成立）")
        .doesNotThrowAnyException();
  }

  @Test
  void viewDoesNotClaimAContextWindowItDoesNotKnow() {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any()))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("qwen2.5:1.5b", null, null, null)));

    ModelView view = serviceWith(gateway).create(chatCommand(), OPERATOR);

    assertThat(view.contextWindow()).as("★ 不知道就如实为 null —— 编一个默认窗口（如 4096）会让分段上限按错误的预算算").isNull();
  }
}
