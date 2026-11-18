package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.application.BaseModelOption;
import cn.lincoln.aiplatform.modelcatalog.application.ModelReferencePort;
import cn.lincoln.aiplatform.modelcatalog.application.ModelService;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelHealth;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.CapabilityProber;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ★★ `#1.7`：网关上有哪些模型（清单），与"这个模型调得通"（健康）是**两件事**。
 *
 * <h3>为什么必须真调一次</h3>
 *
 * <p>JAiRouter 的 {@code GET /v1/models} 只返回 {@code id / object / created / owned_by / service_type
 * / adapter} —— **没有健康字段**。而它自己最常见的故障恰恰是"实例健康、适配器解析不到" （{@code adapterResolved=false}，它自己的 `#1004`
 * 就是这么描述的）⇒ 只看清单会得到一个 <b>绿灯的坏模型</b>，然后每一次用户提问都失败在这个模型上，界面上还看不出是模型的问题。
 *
 * <p>★ 三条边界（本测试逐条钉住）：
 *
 * <ol>
 *   <li>探测失败 ⇒ {@link ModelHealth#UNHEALTHY}，且 {@code healthDetail} 要带上游原话；
 *   <li>不健康的模型 {@code create} 必须被拒（不是"提示一下但仍然存进去"）；
 *   <li>探不了（没有上游地址 ⇒ {@link ModelHealth#UNKNOWN}）必须放行 —— 把"不知道"当"坏了"， 会让本地推理模型完全无法注册。
 * </ol>
 */
class ModelHealthProbeTest {

  private static final UUID OPERATOR = UUID.randomUUID();
  private static final String API_BASE = "http://127.0.0.1:8080/v1";

  private ModelService serviceWith(UpstreamGateway gateway) {
    return new ModelService(
        mock(ModelRepository.class),
        mock(ModelProviderRepository.class),
        mock(ModelUpstreamResolver.class),
        mock(ApiKeyCipher.class),
        mock(CapabilityProber.class),
        gateway,
        mock(LocalOnnxEmbedding.class),
        mock(ModelReferencePort.class),
        mock(PlatformTransactionManager.class));
  }

  private static ModelService.UpsertCommand chatCommand(String baseModel, String apiBase) {
    return new ModelService.UpsertCommand(
        "测试模型",
        ModelKind.CHAT,
        Supplier.LOCAL_INFERENCE,
        baseModel,
        null,
        apiBase,
        null,
        null,
        null,
        ThinkingMode.NONE,
        ToolsMode.UNSUPPORTED,
        "健康探测测试");
  }

  private static UpstreamGateway gatewayWithTwoModels(String healthy, String broken) {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any(), any()))
        .thenReturn(
            List.of(
                new UpstreamGateway.UpstreamModel(healthy, 32768, null, null),
                new UpstreamGateway.UpstreamModel(broken, 8192, null, null)));
    when(gateway.chatCompletion(anyString(), any(), any(), anyString(), any(Duration.class), any()))
        .thenReturn("ok");
    // ★ 后 stub 的优先（Mockito 以最后一次匹配为准）
    when(gateway.chatCompletion(eq(broken), any(), any(), anyString(), any(Duration.class), any()))
        .thenThrow(
            new UpstreamGateway.UpstreamUnreachableException(
                "上游返回 404：{\"error\":{\"message\":\"model not found\"}}"));
    return gateway;
  }

  @Test
  void everyModelInTheCatalogIsActuallyCalled() {
    ModelService service = serviceWith(gatewayWithTwoModels("qwen2.5:7b", "broken-model"));

    ModelService.BaseModelCatalog catalog = service.baseModels(null, null, null, API_BASE, "k");

    assertThat(catalog.options())
        .as("★ 清单里有名字 ≠ 调得通：健康状态必须是真调出来的，不是从清单推的")
        .extracting(BaseModelOption::baseModel, BaseModelOption::health)
        .containsExactly(
            tuple("qwen2.5:7b", ModelHealth.HEALTHY), tuple("broken-model", ModelHealth.UNHEALTHY));
    assertThat(catalog.options().get(1).healthDetail())
        .as("★ 不健康的原因要带上游原话 —— 写成「调用失败」四个字，等于把人挡在门外还不说为什么")
        .contains("model not found");
  }

  @Test
  void unhealthyBaseModelIsRefusedAtRegistration() {
    ModelService service = serviceWith(gatewayWithTwoModels("qwen2.5:7b", "broken-model"));

    assertThatThrownBy(() -> service.create(chatCommand("broken-model", API_BASE), OPERATOR))
        .as("★ 用户口径：不健康的模型不允许配置到模型管理（提示「该模型不健康」），而不是存进去等问答时才炸")
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前不健康");
  }

  @Test
  void healthyBaseModelPassesTheGate() {
    ModelService service = serviceWith(gatewayWithTwoModels("qwen2.5:7b", "broken-model"));

    assertThatCode(() -> service.create(chatCommand("qwen2.5:7b", API_BASE), OPERATOR))
        .doesNotThrowAnyException();
  }

  @Test
  void withoutAnUpstreamAddressNothingIsProbedAndNothingIsBlocked() {
    // ★ 本地推理没有 apiBase 是合法配置：此时"探不了"只能记 UNKNOWN，绝不能据此拒绝
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any(), any()))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("qwen2.5:1.5b", 32768, null, null)));

    ModelService service = serviceWith(gateway);

    assertThat(service.baseModels(null, null, null, null, null).options())
        .extracting(BaseModelOption::health)
        .containsExactly(ModelHealth.UNKNOWN);
    assertThatCode(() -> service.create(chatCommand("qwen2.5:1.5b", null), OPERATOR))
        .as("★ UNKNOWN 不是 UNHEALTHY：不知道的时候不许拦人")
        .doesNotThrowAnyException();
  }

  @Test
  void multimodalKindsAreNotProbedAtAll() {
    UpstreamGateway gateway = mock(UpstreamGateway.class);
    when(gateway.listModels(any(), any(), any()))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("qwen2.5:7b", 32768, null, null)));

    ModelService service = serviceWith(gateway);
    ModelService.BaseModelCatalog catalog = service.baseModels(null, "TTS", null, API_BASE, "k");

    assertThat(catalog.options()).as("kind 过滤后没有候选，就一条探测都不该发").isEmpty();
  }
}
