package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 向量化端口（第 8 周 · 0.3.0）—— 对着**真实桩服务**验，不是对着 mock 验。
 *
 * <p>★ 这里最关键的一条不是"能不能拿到向量"，而是<strong>顺序对齐</strong>：返回的第 i 个向量必须对应 第 i
 * 段文本。向量与文本错配不会报错，只会让检索返回「别的内容」的邻居 —— 而"某条文本的向量与它自己 单独向量化的结果一致"正好能钉住这件事（桩服务是确定性的，同一文本永远同一向量）。
 *
 * <p>★ 第二条：上游不可达必须是<strong>可读原因 + 正确错误码</strong>（{@code UPSTREAM_UNAVAILABLE}）， 不是 400 ——
 * 用户改不动请求，下一步是查服务或重试。
 *
 * <p>需要桩服务：{@code python backend/tools/openai_stub.py}（不在就跳过，不假绿）。
 */
@SpringBootTest
@Testcontainers
class UpstreamEmbeddingAdapterTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  /** 桩服务的 OpenAI 兼容入口（base 末尾要带 /v1：{@code UpstreamGateway} 会拼 /embeddings）。 */
  private static final String STUB_API_BASE =
      System.getenv().getOrDefault("AI_STUB_ROOT", "http://localhost:11434") + "/v1";

  @Autowired EmbeddingPort embeddingPort;
  @Autowired ModelRepository models;

  @BeforeAll
  static void requireStub() {
    assumeTrue(
        reachable(), "桩服务不在 " + STUB_API_BASE + " ⇒ 跳过。启动：python backend/tools/openai_stub.py");
  }

  // ────────────────────────── 1 · 顺序与维度 ──────────────────────────

  /** ★ 批量向量化：条数一致、维度一致、**顺序对齐**。 */
  @Test
  void 批量向量化条数与顺序都要对齐() {
    UUID modelId = saveEmbeddingModel(STUB_API_BASE);
    List<String> texts = List.of("合同订立前应完成主体资格审查。", "住宿标准为每人每天五百元。", "同一句话。", "同一句话。");

    List<float[]> vectors = embeddingPort.embed(modelId, texts);

    assertThat(vectors).as("条数必须与入参一致").hasSize(texts.size());
    assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(768));

    // ★ 顺序：第 2 条文本单独向量化，必须与批量结果里的第 2 条**逐值相等**
    List<float[]> single = embeddingPort.embed(modelId, List.of(texts.get(1)));
    assertThat(vectors.get(1))
        .as("批量结果的第 1 个下标必须对应第 1 段文本 —— 错配会让检索返回别的内容的邻居")
        .isEqualTo(single.get(0));
    assertThat(vectors.get(0)).isNotEqualTo(vectors.get(1));
  }

  /** 空批次是合法的空活：直接返回空，不打上游（{@code UpstreamGateway} 在空入参时就短路了）。 */
  @Test
  void 空批次直接返回空() {
    UUID modelId = saveEmbeddingModel(STUB_API_BASE);

    assertThat(embeddingPort.embed(modelId, List.of())).isEmpty();
  }

  // ────────────────────────── 2 · 失败要可读且码要对 ──────────────────────────

  /** ★ 上游不可达 ⇒ {@code UPSTREAM_UNAVAILABLE} + 可读原因 + 下一步建议。 */
  @Test
  void 上游不可达给可读原因() {
    UUID modelId = saveEmbeddingModel("http://127.0.0.1:1/v1"); // 1 号端口必然连不上

    assertThatThrownBy(() -> embeddingPort.embed(modelId, List.of("任意文本")))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.code())
                  .as("上游不可用不是「参数不合法」—— 用户改不动请求")
                  .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
              assertThat(be.getMessage()).isNotBlank();
              assertThat(be.hint()).as("下一步建议是一等字段（08 §4.1）").isNotBlank();
            });
  }

  /** ★ 对话模型被当向量模型用 ⇒ 明确拒绝（拿回来的不是向量，早拒比晚错好）。 */
  @Test
  void 非向量模型必须明确拒绝() {
    Model chat =
        models.save(
            new Model(
                UUID.randomUUID(),
                "对话模型-" + shortId(),
                ModelKind.CHAT,
                Supplier.LOCAL_INFERENCE,
                "qwen2.5:7b",
                STUB_API_BASE,
                null,
                null,
                32768,
                null,
                ThinkingMode.NONE,
                ToolsMode.UNSUPPORTED,
                false,
                "向量化端口测试用",
                UUID.randomUUID()));

    assertThatThrownBy(() -> embeddingPort.embed(chat.id(), List.of("任意文本")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不是向量模型");
  }

  /** 模型不存在 ⇒ 404 语义的可读拒绝。 */
  @Test
  void 模型不存在给可读原因() {
    assertThatThrownBy(() -> embeddingPort.embed(UUID.randomUUID(), List.of("任意文本")))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 落一个向量模型行；维度按桩服务实测的 768 声明（它接受 768 维向量）。 */
  private UUID saveEmbeddingModel(String apiBase) {
    Model model =
        models.save(
            new Model(
                UUID.randomUUID(),
                "向量模型-" + shortId(),
                ModelKind.EMBEDDING,
                Supplier.LOCAL_INFERENCE,
                "bge-m3",
                apiBase,
                null,
                null,
                null,
                768,
                ThinkingMode.NONE,
                ToolsMode.UNSUPPORTED,
                false,
                "向量化端口测试用",
                UUID.randomUUID()));
    return model.id();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static boolean reachable() {
    try {
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(2))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(STUB_API_BASE + "/models"))
                      .timeout(Duration.ofSeconds(3))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      return response.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }
}
