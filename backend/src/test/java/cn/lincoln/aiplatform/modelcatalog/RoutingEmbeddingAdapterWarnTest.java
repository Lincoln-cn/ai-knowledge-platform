package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.LocalOnnxEmbedding;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.RoutingEmbeddingAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamEmbeddingAdapter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 路由走错路时**必须留痕**（{@code #305} 缺口 A）。
 *
 * <p>★ 这条测的不是"能不能向量化"，而是"走错路时看不看得见"： {@code app.embedding.local.model-name} 默认是 {@code
 * multilingual-e5-small}，而用户完全可能在模型行里填 {@code bge-small-zh-v1.5}（磁盘上那套文件也真的在）。此时路由按**名字**判定"不归本地" ⇒
 * 静默走上游， 上游回来的是另一种维度的向量 ⇒ **检索照样有结果、只是全部不对**，而日志里一个字都没有。
 *
 * <p>本类用 Logback 的 {@link ListAppender} 直接抓 `RoutingEmbeddingAdapter` 打的日志 —— 这比"读源码里有没有那句话"
 * 强：后者在重构后仍然"绿"，而前者在 WARN 被删掉的那一刻就红。
 */
class RoutingEmbeddingAdapterWarnTest {

  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-4000-8000-000000000305");

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void captureWarnings() {
    logger = (Logger) LoggerFactory.getLogger(RoutingEmbeddingAdapter.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseWarnings() {
    logger.detachAppender(appender);
  }

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

  private static RoutingEmbeddingAdapter.ModelIdentityPort naming(String baseModel) {
    return modelId -> baseModel;
  }

  private List<String> warnings() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Test
  @DisplayName("模型文件在、名字对不上 ⇒ 打一行 WARN，并把「该改成什么」写进消息")
  void aModelWhoseFilesAreOnDiskButWhoseNameDiffersIsLoud() {
    LocalOnnxEmbedding local = localServing("multilingual-e5-small");
    when(local.hasModelFilesFor("bge-small-zh-v1.5")).thenReturn(true);
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(local, upstreamReturning(768), naming("bge-small-zh-v1.5"));

    adapter.embed(MODEL_ID, List.of("一段中文"));

    assertThat(warnings()).as("走错路时至少要在日志里看见一行 —— 否则「看起来在做、实际没生效」无从发现").hasSize(1);
    assertThat(warnings().get(0))
        .contains("bge-small-zh-v1.5")
        .contains("multilingual-e5-small")
        .contains("app.embedding.local.model-name");
  }

  @Test
  @DisplayName("同一名字只打一次（一份 70 段的文档会调用 70 次，不能刷屏）")
  void theSameWarningIsOnlyPrintedOnce() {
    LocalOnnxEmbedding local = localServing("multilingual-e5-small");
    when(local.hasModelFilesFor("bge-small-zh-v1.5")).thenReturn(true);
    UpstreamEmbeddingAdapter upstream = upstreamReturning(768);
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(local, upstream, naming("bge-small-zh-v1.5"));

    adapter.embed(MODEL_ID, List.of("甲"));
    adapter.embed(MODEL_ID, List.of("乙"));
    adapter.embed(MODEL_ID, List.of("丙"));

    assertThat(warnings()).as("同一名字重复调用的 WARN 必须是 1 行").hasSize(1);
    verify(upstream, times(3)).embed(any(), any());
  }

  @Test
  @DisplayName("文件不在磁盘上（比如上游模型）⇒ 不打扰（那种「走错路」是正常的）")
  void aNameWithNoLocalFilesIsSilent() {
    LocalOnnxEmbedding local = localServing("multilingual-e5-small");
    when(local.hasModelFilesFor(anyString())).thenReturn(false);
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(local, upstreamReturning(1024), naming("bge-m3"));

    adapter.embed(MODEL_ID, List.of("一段中文"));

    assertThat(warnings()).as("上游模型本来就走上游，不该报 WARN").isEmpty();
  }

  @Test
  @DisplayName("名字就是本地服务的那个 ⇒ 走本地、不打 WARN")
  void theServedNameGoesLocalQuietly() {
    LocalOnnxEmbedding local = localServing("multilingual-e5-small");
    when(local.embed(any())).thenReturn(List.of(new float[384]));
    RoutingEmbeddingAdapter adapter =
        new RoutingEmbeddingAdapter(local, upstreamReturning(768), naming("multilingual-e5-small"));

    List<float[]> vectors = adapter.embed(MODEL_ID, List.of("一段中文"));

    assertThat(vectors).hasSize(1);
    assertThat(warnings()).isEmpty();
  }
}
