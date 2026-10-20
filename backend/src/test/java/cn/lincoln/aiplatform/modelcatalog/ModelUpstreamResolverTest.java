package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 服务商停用/删除时的 fail-closed 判据。
 *
 * <p>★ 这条判据的意义：解析器是“模型资产 → 上游连接”的唯一入口。如果 {@code egressOf} 在服务商 不存在时回落到旧 supplier，界面停用了服务商，模型却仍按旧
 * key / 旧出网边界跑 —— 这是最典型的 silent-failure：状态显示已停用，实际还在用。
 */
@ExtendWith(MockitoExtension.class)
class ModelUpstreamResolverTest {

  @Mock ModelProviderRepository providers;

  private ModelUpstreamResolver resolver;
  private UUID operatorId;

  @BeforeEach
  void setUp() {
    byte[] secret = new byte[32];
    resolver =
        new ModelUpstreamResolver(
            providers, new ApiKeyCipher(Base64.getEncoder().encodeToString(secret)));
    operatorId = UUID.randomUUID();
  }

  @Test
  void disabledProviderHasNoEgressAndCannotBeResolved() {
    ModelProvider provider = provider();
    provider.enabled(false, operatorId);
    when(providers.findById(provider.id())).thenReturn(Optional.of(provider));
    Model model = model();
    model.bindProvider(provider.id());

    assertThat(resolver.egressOf(model)).as("★ 停用服务商必须让出网边界为空，而不是回落到旧 supplier").isEmpty();
    assertThatThrownBy(() -> resolver.resolve(model))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已停用");
  }

  @Test
  void missingProviderHasNoEgressAndCannotBeResolved() {
    UUID providerId = UUID.randomUUID();
    when(providers.findById(providerId)).thenReturn(Optional.empty());
    Model model = model();
    model.bindProvider(providerId);

    assertThat(resolver.egressOf(model)).as("★ 服务商被删后，旧 api_base/key 不得成为隐式回退").isEmpty();
    assertThatThrownBy(() -> resolver.resolve(model))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("服务商不存在");
  }

  @Test
  void enabledProviderSuppliesEgressAndAddress() {
    ModelProvider provider = provider();
    when(providers.findById(provider.id())).thenReturn(Optional.of(provider));
    Model model = model();
    model.bindProvider(provider.id());

    assertThat(resolver.egressOf(model)).contains(ProviderEgress.EXTERNAL);
    assertThat(resolver.resolve(model).apiBase()).isEqualTo("http://localhost:8080/v1");
  }

  private ModelProvider provider() {
    return new ModelProvider(
        UUID.randomUUID(),
        "JAiRouter（localhost）",
        ProviderKind.GATEWAY,
        "http://localhost:8080/v1",
        AuthStyle.X_API_KEY,
        ProviderEgress.EXTERNAL,
        operatorId);
  }

  private Model model() {
    return new Model(
        UUID.randomUUID(),
        "旧式直连模型",
        ModelKind.CHAT,
        Supplier.EXTERNAL_API,
        "qwen3:4b",
        "http://legacy.example/v1",
        null,
        null,
        8192,
        null,
        ThinkingMode.NONE,
        ToolsMode.UNSUPPORTED,
        false,
        null,
        operatorId);
  }
}
