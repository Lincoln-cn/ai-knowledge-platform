package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ChatModelAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamEmbeddingAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #85}）：<strong>出站正文里的个人信息被原样发出去 —— 当前没有任何 PII 处理</strong>。
 *
 * <h3>它断言的是"当前没做这件事"，所以它将来必须被刻意改掉</h3>
 *
 * <p>出站方向有<strong>两件</strong>事，本项目只认了第一件：
 *
 * <ul>
 *   <li>{@code #72} 管<strong>"发不发 / 发给谁"</strong>（容器边界：密级 × 模型供应商）；
 *   <li>★ {@code #85} 管<strong>"发出去的是什么"</strong>（内容边界：正文里的 PII）。
 * </ul>
 *
 * <p>两者<strong>正交</strong>，而混为一谈的后果是本项目的头号病（{@code silent-failure}）：
 * <em>"以为做了外发闸门就安全了"</em>。所以这条断言要证明的是那句最容易被忽略的话 —— <strong>密级低的文档完全可能含身份证号</strong>，而它今天会被逐字发给上游。
 *
 * <h3>★ 读数：模型真正收到的那串 JSON</h3>
 *
 * <p>用一个记录型 {@link UpstreamGateway} 把 <strong>Jackson 序列化之后的请求体</strong>留下来。
 * 这样这条断言测的是"实际发出去的东西"，而不是"某个字段名看起来像不像做了脱敏"—— 任何脱敏（替换 / 掩码 / 删除 / 送到另一路去处理）都会让这段正文不再逐字出现。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring、不需要桩模型</strong>（假网关不发 HTTP）。
 */
class OutboundPiiPassthroughTest {

  /** 一段"公开"文档里完全可能出现的正文：姓名 + 身份证号 + 手机号。 */
  private static final String PII_TEXT = "经办人：张伟，身份证号 110105199003074512，联系电话 13800138000，请核对后归档。";

  /** 上游真正收到的消息内容（不是我们以为发出去的内容）。 */
  private final RecordingGateway gateway = new RecordingGateway();

  @Test
  void assertOutboundBodyIsSentWithoutPiiRedaction() {
    // ① 先跑一遍**对话**出口（今天生产上用得最多的那一路）
    UUID chatId = UUID.randomUUID();
    Model chat = chatModel(chatId);
    ChatModelAdapter chatAdapter = new ChatModelAdapter(new FakeModels(chat), null, gateway);
    gateway.response = "收到";

    chatAdapter.complete(chatId, PII_TEXT, Duration.ofSeconds(5));

    String outbound = gateway.lastPayload();
    assertThat(gateway.sentUpstream).as("夹具前提：必须真的走到了「把请求体交给上游」这一步（否则下面的断言是空跑）").isTrue();
    assertThat(outbound)
        .as(
            """
            ★ 出站请求体里逐字躺着身份证号与手机号 —— 这就是"没有 PII 出站处理"的读数本身。
            若这一条红了，说明出站链路上开始有 PII 处理了：那是 #85 的位置（13 新增的那一节写的位置与理由）
            被填上。★ 那一刻必须同时做三件事：① 13 写清"检测到之后是拦、是替换还是打标"；
            ② 与 #72 的分工仍然要写清（一个管能不能发、一个管发的是什么）；
            ③ 把本断言改成"它必须**不**出现"。
            实际发出去的请求体：%s\
            """,
            outbound)
        .contains("110105199003074512")
        .contains("13800138000");

    // ② 顺带钉住第二个出口：**向量化**那条路走的是同一份文本（同一段正文，同一条边界）
    UUID embeddingId = UUID.randomUUID();
    Model embedding = embeddingModel(embeddingId);
    UpstreamEmbeddingAdapter embeddingAdapter =
        new UpstreamEmbeddingAdapter(new FakeModels(embedding), null, gateway);
    gateway.response = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}";

    embeddingAdapter.embed(embeddingId, List.of(PII_TEXT));

    assertThat(gateway.lastPayload())
        .as(
            """
            ★ 向量化这一路同样把正文原样发出去。两条出口都不过滤 ⇒ 只在对话侧加一道网是不够的
            （#85 出站链路的"可插预处理点"必须覆盖两处，否则又是一条"看着做了、实际漏了"的路）。%s\
            """,
            gateway.lastPayload())
        .contains("110105199003074512");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 记录型网关：把**序列化之后的请求体**留下来，不真的发 HTTP。 */
  private static final class RecordingGateway extends UpstreamGateway {

    private final tools.jackson.databind.ObjectMapper json =
        new tools.jackson.databind.ObjectMapper();

    String response = "收到";
    String payload;
    boolean sentUpstream;

    RecordingGateway() {
      super("http://localhost:9/v1", "test-key");
    }

    @Override
    public String chatCompletion(
        String model, String apiBase, String apiKey, String prompt, Duration timeout) {
      // ★ 复刻生产网关构造请求体的那一步（UpstreamGateway.chatCompletion 用的是同一个 Map 形状 +
      //   同一个序列化器）。★ 必须走序列化器而不是手拼字符串：手拼出来的"JSON"在含引号/换行时
      //   根本不是合法 JSON，那样读到的就不是上游会收到的东西。
      payload =
          json.writeValueAsString(
              java.util.Map.of(
                  "model",
                  model,
                  "messages",
                  List.of(java.util.Map.of("role", "user", "content", prompt))));
      sentUpstream = true;
      return response;
    }

    @Override
    public List<float[]> embed(String model, List<String> texts, String apiBase, String apiKey) {
      payload = json.writeValueAsString(java.util.Map.of("model", model, "input", texts));
      sentUpstream = true;
      return List.of(new float[] {0.1f, 0.2f});
    }

    String lastPayload() {
      return payload == null ? "(没有发出去任何请求体)" : payload;
    }
  }

  /** 只回答"这个 id 的模型"的假仓储。 */
  private static final class FakeModels implements ModelRepository {

    private final Model model;

    FakeModels(Model model) {
      this.model = model;
    }

    @Override
    public Optional<Model> findById(UUID id) {
      return model.id().equals(id) ? Optional.of(model) : Optional.empty();
    }

    @Override
    public Model save(Model model) {
      throw new UnsupportedOperationException("本测试不落库");
    }

    @Override
    public void delete(Model model) {
      throw new UnsupportedOperationException("本测试不落库");
    }

    @Override
    public List<Model> search(
        String keyword, ModelKind kind, ModelStatus status, int offset, int limit) {
      return model.kind() == kind && model.status() == status ? List.of(model) : List.of();
    }

    @Override
    public long countSearch(String keyword, ModelKind kind, ModelStatus status) {
      return 0;
    }
  }

  private static Model chatModel(UUID id) {
    return loaded(id, ModelKind.CHAT, null, 8_192);
  }

  private static Model embeddingModel(UUID id) {
    return loaded(id, ModelKind.EMBEDDING, 1_024, null);
  }

  private static Model loaded(UUID id, ModelKind kind, Integer dimension, Integer contextWindow) {
    Model model =
        new Model(
            id,
            "夹具模型",
            kind,
            Supplier.LOCAL_INFERENCE,
            "qwen2.5:1.5b",
            "http://localhost:11434/v1",
            null,
            null,
            contextWindow,
            dimension,
            null,
            null,
            false,
            null,
            null);
    model.startLoad();
    model.markLoaded();
    return model;
  }
}
