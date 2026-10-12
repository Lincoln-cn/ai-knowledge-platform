package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code ai_model} 落库与迁移（第 4 周 · 0.1.3 测试项 ③ ④）。
 *
 * <p>★ 起<strong>真的 PostgreSQL</strong>（与 {@code PermissionCatalogTest} 同一口径）： Flyway 真跑一遍
 * V8/V11、Hibernate {@code ddl-auto: validate} 真校验一遍实体 —— 这两条是"表和聚合对不对得上"的最后一道闸， mock 数据库测不出来。
 *
 * <p>★ 枚举机器码往返一致：{@code supplier}/{@code status}/{@code kind} 在库、代码、契约三处是同一个 SCREAMING_SNAKE_CASE
 * 机器码（如 {@code LOAD_FAILED}）—— 本测试把「写进去是新码、读出来还是新码」钉死， 并直接 JDBC 断言<strong>库存的就是新码</strong>（避免有人加
 * {@code @JsonValue} 又把中文写回去）。
 */
@SpringBootTest
@Testcontainers
class ModelPersistenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired ModelRepository models;
  @Autowired ApiKeyCipher apiKeyCipher;
  @Autowired JdbcTemplate jdbc;

  /** Jackson 2 的 ObjectMapper 本地构造即可 —— Boot 4 的自动配置暴露的是 Jackson 3 的 JsonMapper，不注入。 */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 每条用例前清空表 —— Testcontainers 的容器是类级共享的， 不清的话用例之间会互相看到对方的行，"count = 2" 这类断言会随执行顺序时红时绿。 */
  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from ai_model");
  }

  private static Model chatModel() {
    return new Model(
        UUID.randomUUID(),
        "本地对话模型",
        ModelKind.CHAT,
        Supplier.LOCAL_INFERENCE,
        "qwen3:8b",
        null,
        null,
        null,
        32768,
        null,
        ThinkingMode.TOGGLEABLE,
        ToolsMode.SUPPORTED,
        false,
        "第 4 周测试用",
        UUID.randomUUID());
  }

  private static Model embeddingModel(String apiBase, String cipher, String iv) {
    return new Model(
        UUID.randomUUID(),
        "外部向量模型",
        ModelKind.EMBEDDING,
        Supplier.EXTERNAL_API,
        "bge-m3",
        apiBase,
        cipher,
        iv,
        null,
        1024,
        ThinkingMode.NONE,
        ToolsMode.UNSUPPORTED,
        true,
        null,
        UUID.randomUUID());
  }

  @Test
  void 迁移已执行且Hibernate校验通过() {
    // 起得来这个上下文本身就证明：Flyway V8 跑完 + ddl-auto: validate 通过（实体与表对得上）
    Integer count = jdbc.queryForObject("select count(*) from ai_model", Integer.class);
    assertThat(count).isNotNull();
  }

  /**
   * ★ 枚举机器码往返一致 + **库里存的就是新码**。
   *
   * <p>钉两件事：① JPA 读写不夹 Converter；② 直接 JDBC 查出来的原始值就是 SCREAMING_SNAKE_CASE 机器码 （如 {@code
   * LOAD_FAILED} / {@code LOCAL_INFERENCE}）—— 避免有人加了 {@code @JsonValue} 又把中文写回去。
   */
  @Test
  void 枚举机器码往返一致且库里存新码() {
    Model model = chatModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://localhost:11434/v1 超时");
    models.save(model);

    Model reloaded = models.findById(model.id()).orElseThrow();

    assertThat(reloaded.supplier()).isEqualTo(Supplier.LOCAL_INFERENCE);
    assertThat(reloaded.status()).isEqualTo(ModelStatus.LOAD_FAILED);
    assertThat(reloaded.kind()).isEqualTo(ModelKind.CHAT);
    assertThat(reloaded.thinking()).isEqualTo(ThinkingMode.TOGGLEABLE);
    assertThat(reloaded.tools()).isEqualTo(ToolsMode.SUPPORTED);
    assertThat(reloaded.loadError()).contains("超时");

    // ★ 库里存的就是新码本身（不是中文、不是序号）。
    //   id 传 UUID 对象，传 toString() 会撞上 PG 的 uuid = varchar 类型错误
    String rawStatus =
        jdbc.queryForObject("select status from ai_model where id = ?", String.class, model.id());
    String rawSupplier =
        jdbc.queryForObject("select supplier from ai_model where id = ?", String.class, model.id());
    String rawKind =
        jdbc.queryForObject("select kind from ai_model where id = ?", String.class, model.id());
    assertThat(rawStatus).isEqualTo("LOAD_FAILED");
    assertThat(rawSupplier).isEqualTo("LOCAL_INFERENCE");
    assertThat(rawKind).isEqualTo("CHAT");
  }

  /**
   * ★ JSON 序列化值也是新码 —— 避免有人加了 {@code @JsonValue} 又把中文写回去。
   *
   * <p>枚举的 {@code name()} 就是序列化值（Jackson 默认行为）。若有人给枚举加 {@code @JsonValue} 返回中文，这条会红。
   */
  @Test
  void JSON序列化值是新码不是中文() throws Exception {
    assertThat(objectMapper.writeValueAsString(ModelStatus.LOAD_FAILED))
        .contains("\"LOAD_FAILED\"");
    assertThat(objectMapper.writeValueAsString(ModelStatus.NOT_LOADED)).contains("\"NOT_LOADED\"");
    assertThat(objectMapper.writeValueAsString(ModelStatus.LOADING)).contains("\"LOADING\"");
    assertThat(objectMapper.writeValueAsString(ModelStatus.LOADED)).contains("\"LOADED\"");

    assertThat(objectMapper.writeValueAsString(Supplier.LOCAL_INFERENCE))
        .contains("\"LOCAL_INFERENCE\"");
    assertThat(objectMapper.writeValueAsString(Supplier.EXTERNAL_API)).contains("\"EXTERNAL_API\"");

    assertThat(objectMapper.writeValueAsString(ModelKind.CHAT)).contains("\"CHAT\"");
    assertThat(objectMapper.writeValueAsString(ModelKind.EMBEDDING)).contains("\"EMBEDDING\"");

    // ★ 反序列化也是新码（契约收发两端一致）
    assertThat(objectMapper.readValue("\"LOAD_FAILED\"", ModelStatus.class))
        .isEqualTo(ModelStatus.LOAD_FAILED);
  }

  @Test
  void 加密后的apiKey往返一致且库里不是明文() {
    String plaintextKey = "sk-very-secret-model-api-key-123456";
    ApiKeyCipher.Sealed sealed = apiKeyCipher.encrypt(plaintextKey);
    Model model =
        embeddingModel("https://api.example.com/v1", sealed.cipherBase64(), sealed.ivBase64());
    models.save(model);

    Model reloaded = models.findById(model.id()).orElseThrow();
    assertThat(apiKeyCipher.decrypt(reloaded.apiKeyCipher(), reloaded.apiKeyIv()))
        .isEqualTo(plaintextKey);

    // ★ 库里不得出现明文（等保：明文入库是硬伤）
    String stored =
        jdbc.queryForObject(
            "select api_key_cipher from ai_model where id = ?", String.class, model.id());
    assertThat(stored).isNotEqualTo(plaintextKey).doesNotContain(plaintextKey);
  }

  @Test
  void 状态机走通后落库_已加载且enabled为true() {
    Model model = chatModel();
    model.startLoad();
    model.markLoaded();
    models.save(model);

    Model reloaded = models.findById(model.id()).orElseThrow();
    assertThat(reloaded.status()).isEqualTo(ModelStatus.LOADED);
    assertThat(reloaded.enabled()).isTrue();
    assertThat(reloaded.loadError()).isNull();
  }

  @Test
  void 分页筛选拼进查询_kind与status() {
    // 造 2 条 chat + 1 条 embedding
    for (int i = 0; i < 2; i++) {
      Model m = chatModel();
      m.startLoad();
      m.markLoaded();
      models.save(m);
    }
    Model emb = embeddingModel(null, null, null);
    models.save(emb);

    assertThat(models.countSearch(null, ModelKind.CHAT, null)).isEqualTo(2);
    assertThat(models.countSearch(null, ModelKind.EMBEDDING, null)).isEqualTo(1);
    assertThat(models.countSearch(null, null, ModelStatus.NOT_LOADED)).isEqualTo(1);
    assertThat(models.countSearch(null, null, ModelStatus.LOADED)).isEqualTo(2);

    assertThat(models.search(null, ModelKind.EMBEDDING, null, 0, 10)).hasSize(1);
    assertThat(models.search(null, ModelKind.CHAT, ModelStatus.LOADED, 0, 10)).hasSize(2);
  }

  // ────────────────────────── dimension / contextWindow 的 null 语义（库层也钉） ──────────────────────────

  @Test
  void 对话模型的dimension在库层也放不进去() {
    // 绕过领域校验直接走 SQL —— 钉 check 约束 chk_ai_model_chat_dimension
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into ai_model (id, name, kind, supplier, base_model, status, enabled,"
                        + " thinking, tools, builtin, context_window, dimension, created_at,"
                        + " updated_at) values (?, ?, 'CHAT', 'LOCAL_INFERENCE', 'qwen3:8b',"
                        + " 'NOT_LOADED', false, 'NONE', 'UNSUPPORTED', false, 32768, 768, now(),"
                        + " now())",
                    id,
                    "非法对话模型"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 向量模型的contextWindow在库层也放不进去() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into ai_model (id, name, kind, supplier, base_model, status, enabled,"
                        + " thinking, tools, builtin, context_window, dimension, created_at,"
                        + " updated_at) values (?, ?, 'EMBEDDING', 'EXTERNAL_API', 'bge-m3',"
                        + " 'NOT_LOADED', false, 'NONE', 'UNSUPPORTED', false, 8192, 1024, now(),"
                        + " now())",
                    id,
                    "非法向量模型"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 非法枚举值在库层进不来() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into ai_model (id, name, kind, supplier, base_model, status, enabled,"
                        + " thinking, tools, builtin, created_at, updated_at)"
                        + " values (?, ?, 'CHAT', '随便写的', 'qwen3:8b', 'NOT_LOADED', false,"
                        + " 'NONE', 'UNSUPPORTED', false, now(), now())",
                    id,
                    "非法 supplier"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 删除走clearEnable后行可以删掉() {
    Model model = chatModel();
    model.startLoad();
    model.markLoaded();
    models.save(model);
    assertThat(model.enabled()).isTrue();

    model.clearEnable();
    models.save(model);
    assertThat(models.findById(model.id()).orElseThrow().enabled()).isFalse();

    Model reloaded = models.findById(model.id()).orElseThrow();
    models.delete(reloaded);
    assertThat(models.findById(model.id())).isEmpty();
  }
}
