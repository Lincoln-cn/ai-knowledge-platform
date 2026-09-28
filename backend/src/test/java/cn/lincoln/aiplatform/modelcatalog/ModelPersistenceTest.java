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

/**
 * {@code ai_model} 落库与迁移（第 4 周 · 0.1.3 测试项 ③ ④）。
 *
 * <p>★ 起<strong>真的 PostgreSQL</strong>（与 {@code PermissionCatalogTest} 同一口径）： Flyway 真跑一遍
 * V8、Hibernate {@code ddl-auto: validate} 真校验一遍实体 —— 这两条是"表和聚合对不对得上"的最后一道闸， mock 数据库测不出来。
 *
 * <p>★ 枚举中文值往返一致：{@code supplier}/{@code status} 在库、代码、契约三处是同一个字面量， 本测试把「写进去是中文、读出来还是中文」钉死 ——
 * 中间一旦有人加了 Converter，这里会红。
 */
@SpringBootTest
@Testcontainers
class ModelPersistenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  @Autowired ModelRepository models;
  @Autowired ApiKeyCipher apiKeyCipher;
  @Autowired JdbcTemplate jdbc;

  /** 每条用例前清空表 —— Testcontainers 的容器是类级共享的， 不清的话用例之间会互相看到对方的行，"count = 2" 这类断言会随执行顺序时红时绿。 */
  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from ai_model");
  }

  private static Model chatModel() {
    return new Model(
        UUID.randomUUID(),
        "本地对话模型",
        ModelKind.chat,
        Supplier.本地推理,
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
        ModelKind.embedding,
        Supplier.外部API,
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

  @Test
  void 枚举中文值往返一致() {
    Model model = chatModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://localhost:11434/v1 超时");
    models.save(model);

    Model reloaded = models.findById(model.id()).orElseThrow();

    assertThat(reloaded.supplier()).isEqualTo(Supplier.本地推理);
    assertThat(reloaded.status()).isEqualTo(ModelStatus.加载失败);
    assertThat(reloaded.kind()).isEqualTo(ModelKind.chat);
    assertThat(reloaded.thinking()).isEqualTo(ThinkingMode.TOGGLEABLE);
    assertThat(reloaded.tools()).isEqualTo(ToolsMode.SUPPORTED);
    assertThat(reloaded.loadError()).contains("超时");

    // ★ 库里存的就是中文字面量本身（不是序号、不是英文码）。
    //   id 传 UUID 对象，传 toString() 会撞上 PG 的 uuid = varchar 类型错误
    String rawStatus =
        jdbc.queryForObject("select status from ai_model where id = ?", String.class, model.id());
    String rawSupplier =
        jdbc.queryForObject("select supplier from ai_model where id = ?", String.class, model.id());
    assertThat(rawStatus).isEqualTo("加载失败");
    assertThat(rawSupplier).isEqualTo("本地推理");
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
    assertThat(reloaded.status()).isEqualTo(ModelStatus.已加载);
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

    assertThat(models.countSearch(ModelKind.chat, null)).isEqualTo(2);
    assertThat(models.countSearch(ModelKind.embedding, null)).isEqualTo(1);
    assertThat(models.countSearch(null, ModelStatus.未加载)).isEqualTo(1);
    assertThat(models.countSearch(null, ModelStatus.已加载)).isEqualTo(2);

    assertThat(models.search(ModelKind.embedding, null, 0, 10)).hasSize(1);
    assertThat(models.search(ModelKind.chat, ModelStatus.已加载, 0, 10)).hasSize(2);
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
                        + " updated_at) values (?, ?, 'chat', '本地推理', 'qwen3:8b', '未加载', false,"
                        + " 'NONE', 'UNSUPPORTED', false, 32768, 768, now(), now())",
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
                        + " updated_at) values (?, ?, 'embedding', '外部API', 'bge-m3', '未加载', false,"
                        + " 'NONE', 'UNSUPPORTED', false, 8192, 1024, now(), now())",
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
                        + " values (?, ?, 'chat', '随便写的', 'qwen3:8b', '未加载', false,"
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
