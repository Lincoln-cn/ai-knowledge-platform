package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.application.ModelReferencePort;
import cn.lincoln.aiplatform.modelcatalog.application.ModelService;
import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelProviderRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.CapabilityProber;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 同一个基础模型的向量维度必须唯一（`#305` 缺口 B）。
 *
 * <p>★ 这条测试防的是一个**界面上看起来完全正常**的错：维度决定写哪张 {@code vec_embedding_<dim>} 表， 同一个 {@code baseModel} 被登记成
 * 768 和 1024 时，两批向量分居两表，检索按模型自己的 dimension 选表 —— 于是**两边互相看不见**。它只在"换个模型重新向量化"之后以"检索结果突然少一半"的形式暴露。
 *
 * <p>★ 为何不去动存量：向量表里只有 {@code id/content/metadata/embedding}，**没有模型归属**； {@code kb_document} 也**没有
 * embedding_model_id 列**（查过 information_schema）。⇒ 存量无从回填， 只能拦住新增，这个限制写在 ROADMAP 里而不是在这里假装解决了。
 */
class ModelDimensionConsistencyTest {

  private static final UUID OPERATOR = UUID.randomUUID();
  private static final String BASE = "bge-m3";

  private ModelService serviceWith(ModelRepository models) {
    return new ModelService(
        models,
        mock(ModelProviderRepository.class),
        mock(ModelUpstreamResolver.class),
        mock(ApiKeyCipher.class),
        mock(CapabilityProber.class),
        mock(UpstreamGateway.class),
        mock(LocalOnnxEmbedding.class),
        mock(ModelReferencePort.class),
        mock(PlatformTransactionManager.class));
  }

  private ModelService.UpsertCommand embedding(String baseModel, int dimension) {
    return new ModelService.UpsertCommand(
        "维度一致性夹具-" + dimension,
        ModelKind.EMBEDDING,
        Supplier.LOCAL_INFERENCE,
        baseModel,
        null,
        "http://127.0.0.1:11434/v1",
        null,
        dimension,
        null,
        null,
        null,
        "#305 缺口 B 的判据夹具");
  }

  /** 造一条已登记的向量模型：只用 mock 暴露校验路径读到的两个方法。 */
  private Model sibling(String baseModel, int dimension) {
    Model m = mock(Model.class);
    when(m.id()).thenReturn(UUID.randomUUID());
    when(m.baseModel()).thenReturn(baseModel);
    when(m.dimension()).thenReturn(dimension);
    return m;
  }

  @Test
  @DisplayName("同一个 baseModel 已登记 768，再登记 1024 必须被拒并说清后果")
  void aSecondDimensionForTheSameBaseModelIsRejected() {
    ModelRepository models = mock(ModelRepository.class);
    Model existing = sibling(BASE, 768);
    when(models.embeddingModelsOf(BASE)).thenReturn(List.of(existing));

    assertThatThrownBy(() -> serviceWith(models).create(embedding(BASE, 1024), OPERATOR))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              // 消息说清"为什么不行"，hint 说清"后果与怎么办" —— 两段分开断言，
              // 因为 BusinessException 把提示放在另一个字段上，混在一起断言会漏掉其中一段。
              assertThat(e.getMessage()).contains("已有登记维度 768").contains("不能再登记成 1024");
              assertThat(((BusinessException) e).hint()).contains("vec_embedding_1024");
            });
  }

  @Test
  @DisplayName("沿用同一个维度就放行（这条是上一条的对照，防它把正常路径也拦掉）")
  void theSameDimensionIsAccepted() {
    ModelRepository models = mock(ModelRepository.class);
    Model existing = sibling(BASE, 768);
    when(models.embeddingModelsOf(BASE)).thenReturn(List.of(existing));
    when(models.findById(any())).thenReturn(java.util.Optional.empty());
    Model saved = sibling(BASE, 768);
    when(models.save(any())).thenReturn(saved);

    // 走到 save 之前不应抛 —— 这里只断言"没有校验异常"，落库细节不是本条关心的
    assertThat(serviceWith(models).create(embedding(BASE, 768), OPERATOR)).isNotNull();
  }

  @Test
  @DisplayName("不同 baseModel 各自有维度是正常的（e5 是 384、bge-m3 是 1024）")
  void differentBaseModelsKeepTheirOwnDimensions() {
    ModelRepository models = mock(ModelRepository.class);
    when(models.embeddingModelsOf("multilingual-e5-small")).thenReturn(List.of());
    Model saved = sibling("multilingual-e5-small", 384);
    when(models.save(any())).thenReturn(saved);

    assertThat(serviceWith(models).create(embedding("multilingual-e5-small", 384), OPERATOR))
        .isNotNull();
  }

  @Test
  @DisplayName("baseModel 为空时不参与这条校验（校验只对能对上基础模型的登记生效）")
  void aBlankBaseModelIsNotGuardedHere() {
    ModelRepository models = mock(ModelRepository.class);
    Model saved = sibling("", 768);
    when(models.save(any())).thenReturn(saved);

    assertThat(serviceWith(models).create(embedding("", 768), OPERATOR)).isNotNull();
  }
}
