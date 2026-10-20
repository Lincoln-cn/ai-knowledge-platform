package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.application.ModelProviderService;
import cn.lincoln.aiplatform.modelcatalog.application.ModelProviderService.ProviderDraft;
import cn.lincoln.aiplatform.modelcatalog.application.ModelProviderView;
import cn.lincoln.aiplatform.modelcatalog.application.ProviderTestResult;
import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProvider;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.JairouterProperties;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

/** 服务商配置的关键判据：密钥不外泄、删除守卫、停用 fail-closed、快速绑定的先探后写与幂等。 */
@ExtendWith(MockitoExtension.class)
class ModelProviderServiceTest {

  @Mock ModelProviderRepository providers;
  @Mock ModelRepository models;
  @Mock UpstreamGateway gateway;
  @Mock ModelUpstreamResolver resolver;

  private ModelProviderService service;
  private JairouterProperties jairouter;

  @BeforeEach
  void setUp() {
    byte[] secret = new byte[32];
    service =
        new ModelProviderService(
            providers,
            models,
            new ApiKeyCipher(Base64.getEncoder().encodeToString(secret)),
            gateway,
            resolver,
            jairouter = new JairouterProperties());
  }

  @Test
  void createSealsKeyAndNeverReturnsPlaintext() {
    when(providers.findAll()).thenReturn(List.of());
    when(models.countByProviderId(any())).thenReturn(0L);
    when(providers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ModelProviderView view =
        service.create(
            new ProviderDraft(
                "OpenAI 生产", ProviderKind.OPENAI, null, null, null, "sk-plain-secret", "prod"),
            UUID.randomUUID());

    ArgumentCaptor<ModelProvider> saved = ArgumentCaptor.forClass(ModelProvider.class);
    verify(providers).save(saved.capture());
    assertThat(saved.getValue().apiKeyCipher()).isNotBlank();
    assertThat(saved.getValue().apiKeyCipher()).doesNotContain("sk-plain-secret");
    assertThat(saved.getValue().apiBase()).isEqualTo("https://api.openai.com/v1");
    assertThat(view.hasApiKey()).isTrue();
    // ★ 不能只断言 view.toString()：record 的 toString 本来就只有那 10 个字段，密钥字段不存在，
    //   那条断言在“视图将来加了 apiKey 字段”时才会红，而现在看不出任何东西。
    //   这里钉的是**对外 JSON 契约**：密钥的明文、密文、IV 都不许出现在序列化结果里。
    String json = new ObjectMapper().writeValueAsString(view);
    assertThat(json).doesNotContain("sk-plain-secret");
    assertThat(json).doesNotContain("apiKeyCipher");
    assertThat(json).doesNotContain("apiKeyIv");
    assertThat(json).doesNotContain("\"apiKey\"");
  }

  @Test
  void deleteRejectsProviderStillReferencedByModels() {
    ModelProvider provider = provider(true);
    when(providers.findById(provider.id())).thenReturn(Optional.of(provider));
    when(models.countByProviderId(provider.id())).thenReturn(3L);

    assertThatThrownBy(() -> service.delete(provider.id()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("还有 3 个模型");

    verify(providers, never()).delete(any());
  }

  @Test
  void testDisabledProviderFailsClosedWithoutCallingUpstream() {
    ModelProvider provider = provider(false);
    when(providers.findById(provider.id())).thenReturn(Optional.of(provider));

    ProviderTestResult result = service.test(provider.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("已停用");
    verify(gateway, never()).listModels(any(), any(), any());
  }

  @Test
  void quickBindReusesSameGatewayWhenAlreadyBound() {
    ModelProvider existing = provider(true);
    when(providers.findByKindAndApiBase(ProviderKind.GATEWAY, "http://localhost:8080/v1"))
        .thenReturn(Optional.of(existing));
    when(gateway.listModels("http://localhost:8080/v1", "k", AuthStyle.X_API_KEY))
        .thenReturn(
            List.of(
                new UpstreamGateway.UpstreamModel("deepseek-chat", null, null, null),
                new UpstreamGateway.UpstreamModel("bge-m3", null, null, null)));
    when(providers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(models.countByProviderId(existing.id())).thenReturn(2L);

    var result = service.quickBind("http://localhost:8080/v1", "k", UUID.randomUUID());

    assertThat(result.created()).isFalse();
    assertThat(result.modelCount()).isEqualTo(2);
    assertThat(result.provider().name()).isEqualTo(existing.name());
    verify(providers).save(existing);
  }

  @Test
  void quickBindNormalizesAddressBeforeLookingForExistingProvider() {
    ModelProvider existing = provider(true);
    when(providers.findByKindAndApiBase(ProviderKind.GATEWAY, "http://localhost:8080/v1"))
        .thenReturn(Optional.of(existing));
    when(gateway.listModels("http://localhost:8080/v1", "k", AuthStyle.X_API_KEY))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("m1", null, null, null)));
    when(providers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(models.countByProviderId(existing.id())).thenReturn(1L);

    // 同一个地址的三种写法：大写 scheme/host、尾斜杠、默认端口都不该建出第二条服务商。
    var result = service.quickBind("HTTP://LOCALHOST:8080/v1/", "k", UUID.randomUUID());

    assertThat(result.created()).as("★ 不归一地址 ⇒ 这里会新建一条，轮换密钥只改其中一条").isFalse();
    verify(providers).save(existing);
  }

  @Test
  void quickBindDoesNotReEnableAnIntentionallyDisabledProvider() {
    ModelProvider existing = provider(false);
    when(providers.findByKindAndApiBase(ProviderKind.GATEWAY, "http://localhost:8080/v1"))
        .thenReturn(Optional.of(existing));
    when(gateway.listModels("http://localhost:8080/v1", "k", AuthStyle.X_API_KEY))
        .thenReturn(List.of(new UpstreamGateway.UpstreamModel("m1", null, null, null)));
    when(providers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(models.countByProviderId(existing.id())).thenReturn(1L);

    var result = service.quickBind("http://localhost:8080/v1", "k", UUID.randomUUID());

    assertThat(existing.enabled()).as("★ 快速绑定只更新连接/密钥，替用户把停用改成启用是静默改变安全状态").isFalse();
    assertThat(result.provider().enabled()).isFalse();
  }

  @Test
  void deleteTranslatesForeignKeyRaceIntoReadableConflict() {
    ModelProvider provider = provider(true);
    when(providers.findById(provider.id())).thenReturn(Optional.of(provider));
    when(models.countByProviderId(provider.id())).thenReturn(0L);
    doThrow(new DataIntegrityViolationException("fk_ai_model_provider"))
        .when(providers)
        .delete(provider);

    assertThatThrownBy(() -> service.delete(provider.id()))
        .as("★ count 与 delete 之间被并发绑定 ⇒ 外键 RESTRICT 不应该变成 500")
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仍被模型引用");
  }

  @Test
  void quickBindDoesNotPersistWhenProbeFails() {
    when(gateway.listModels("http://localhost:8080/v1", null, AuthStyle.X_API_KEY))
        .thenThrow(new UpstreamGateway.UpstreamUnreachableException("连接被拒绝"));

    assertThatThrownBy(() -> service.quickBind(null, null, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有绑定成功");

    verify(providers, never()).save(any());
  }

  @Test
  void setKeyRejectsBlankInsteadOfSilentlyClearing() {
    assertThatThrownBy(() -> service.setKey(UUID.randomUUID(), "  ", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能为空");

    verify(providers, never()).save(any());
  }

  private ModelProvider provider(boolean enabled) {
    ModelProvider provider =
        new ModelProvider(
            UUID.randomUUID(),
            "JAiRouter（localhost）",
            ProviderKind.GATEWAY,
            "http://localhost:8080/v1",
            AuthStyle.X_API_KEY,
            ProviderEgress.EXTERNAL,
            UUID.randomUUID());
    provider.enabled(enabled, UUID.randomUUID());
    return provider;
  }
}
