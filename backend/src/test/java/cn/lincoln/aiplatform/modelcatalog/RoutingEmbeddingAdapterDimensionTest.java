package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.RoutingEmbeddingAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamEmbeddingAdapter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登记维度与上游返回的维度不符时**必须整批失败**（{@code #348}）。
 *
 * <h3>★ 这条测的是"维度错了会不会静默写进去"</h3>
 *
 * <p>写入端的表名是按<strong>返回向量的长度</strong>拼的（{@code vec_embedding_<维度>}），所以"登记 1024、实际返回 768"会落进 768
 * 表，写完一路无错：走查 47/47、检索有结果，只是这批向量与已有向量永远互相看不见。 实测过：{@code #347} 清理后 {@code vec_embedding_768} 从 0 涨到
 * 4、再涨到 8，每一轮走查多 4 行。
 *
 * <p>★ 为什么断言"整批抛"而不是"过滤掉坏的"：调用方是逐条 upsert 的，抛在返回之前才能保证一条都没写进去。 半批写进去会留下两份向量空间并存的状态 —— 比全不写更难查。
 *
 * <p>★ 反向两条一起写在这里：登记与返回一致时必须**照常通过**（否则修出来的是一堵不允许任何模型入库的墙）， 以及登记维度为空时只 WARN 不拦（"没填"不等于"填错了"）。
 */
class RoutingEmbeddingAdapterDimensionTest {

  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-4000-8000-000000000348");

  private static LocalOnnxEmbedding localServing(String servedName) {
    LocalOnnxEmbedding local = mock(LocalOnnxEmbedding.class);
    when(local.serves(anyString()))
        .thenAnswer(inv -> servedName.equalsIgnoreCase(inv.getArgument(0)));
    when(local.servedModelName()).thenReturn(servedName);
    when(local.ready()).thenReturn(true);
    return local;
  }

  private static UpstreamEmbeddingAdapter upstreamReturning(int dims) {
    UpstreamEmbeddingAdapter upstream = mock(UpstreamEmbeddingAdapter.class);
    when(upstream.embed(any(), any())).thenReturn(List.of(new float[dims]));
    return upstream;
  }

  private static RoutingEmbeddingAdapter.ModelIdentityPort declaring(
      String baseModel, Integer dims) {
    RoutingEmbeddingAdapter.ModelIdentityPort identities =
        mock(RoutingEmbeddingAdapter.ModelIdentityPort.class);
    when(identities.baseModelOf(any())).thenReturn(baseModel);
    when(identities.dimensionOf(any())).thenReturn(dims);
    return identities;
  }

  @Test
  @DisplayName("★ 登记 1024、上游返回 768 ⇒ 整批失败，消息里同时点名登记值与实际值")
  void aShorterUpstreamVectorThanDeclaredFailsLoudly() {
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(
            localServing("multilingual-e5-small"),
            upstreamReturning(768),
            declaring("bge-m3", 1024));

    BusinessException error =
        catchThrowableOfType(
            () -> adapter.embed(MODEL_ID, List.of("一段中文", "另一段")), BusinessException.class);

    assertThat(error).isNotNull();
    // ★ 消息给「谁错了」（模型 + 登记值 + 实际值），hint 给「为什么危险」—— 分开断言，免得把 hint 混进
    //   message 也算过
    assertThat(error.getMessage()).contains("bge-m3").contains("1024").contains("768");
    assertThat(error.hint()).contains("vec_embedding_");
  }

  @Test
  @DisplayName("登记 1024、上游也返回 1024 ⇒ 照常通过（修完不能变成「谁都不许入库」）")
  void matchingDimensionsPass() {
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(
            localServing("multilingual-e5-small"),
            upstreamReturning(1024),
            declaring("bge-m3", 1024));

    List<float[]> vectors = adapter.embed(MODEL_ID, List.of("一段中文"));

    assertThat(vectors).hasSize(1);
    assertThat(vectors.get(0)).hasSize(1024);
  }

  @Test
  @DisplayName("本地分支同样要比对：登记 384、本地返回 512 ⇒ 失败")
  void theLocalBranchIsCheckedToo() {
    LocalOnnxEmbedding local = localServing("bge-small-zh-v1.5");
    when(local.embed(any())).thenReturn(List.of(new float[512]));
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(
            local, upstreamReturning(768), declaring("bge-small-zh-v1.5", 384));

    assertThatThrownBy(() -> adapter.embed(MODEL_ID, List.of("一段中文")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("384")
        .hasMessageContaining("512");
  }

  @Test
  @DisplayName("登记维度是空的 ⇒ 只 WARN 不拦（「没填」不等于「填错了」）")
  void anUnknownDeclaredDimensionOnlyWarns() {
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(
            localServing("multilingual-e5-small"),
            upstreamReturning(768),
            declaring("bge-m3", null));

    assertThatCode(() -> adapter.embed(MODEL_ID, List.of("一段中文"))).doesNotThrowAnyException();
  }
}
