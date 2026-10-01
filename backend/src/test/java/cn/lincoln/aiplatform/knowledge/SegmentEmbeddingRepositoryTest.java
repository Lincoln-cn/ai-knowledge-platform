package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbedding;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 分段向量的写入（第 8 周 · 0.3.0 · 判据 ③）—— <strong>同 segmentId 重复写必须覆盖</strong>。
 *
 * <p>★ 为什么这条是硬要求（{@code 03 §3.3}）：模块间是「至少一次投递」，重投的事件与用户重复点击都会走到写入路径。
 * 「靠调用方只写一次」保证不住，所以幂等必须落在<strong>表的主键 + 冲突子句</strong>上： {@code id = segmentId} ⇒ {@code on conflict
 * (id) do update}。 写错成"先查再插"的话，并发下要么两条都写、要么一条报唯一键冲突 —— 幂等要求的是"无论投几次结果一致"。
 *
 * <p>★ 第二条纪律：<strong>维度不匹配必须明确失败</strong>。表名带维度后缀（{@code vector(N)} 是列类型）， 补零或截断能让写入"成功"，但那份向量是假的
 * —— 而且查不出来。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class SegmentEmbeddingRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  private static final int DIM = 768;

  @Autowired SegmentEmbeddingRepository embeddings;
  @Autowired JdbcTemplate jdbc;

  // ────────────────────────── 1 · 幂等 upsert ──────────────────────────

  /** ★ 同一个 segmentId 写两次 ⇒ 表里仍是一行，且内容是第二次的。 */
  @Test
  void 同一个分段重复写是覆盖不是追加() {
    UUID segmentId = UUID.randomUUID();
    embeddings.upsert(embedding(segmentId, UUID.randomUUID(), "第一版正文", 0.1f));
    embeddings.upsert(embedding(segmentId, UUID.randomUUID(), "第二版正文", 0.9f));

    assertThat(rowCount(segmentId)).as("重投/重复点击不该产生第二行").isEqualTo(1);
    SegmentEmbedding loaded = embeddings.findById(segmentId).orElseThrow();
    assertThat(loaded.content()).isEqualTo("第二版正文");
    assertThat(loaded.vector()[0]).isEqualTo(0.9f);
    assertThat(loaded.dimension()).isEqualTo(DIM);
  }

  /** ★ 维度与向量表不一致 ⇒ 明确失败，且消息里带实际维度（不许静默补零/截断）。 */
  @Test
  void 维度不匹配必须明确失败() {
    UUID segmentId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();

    assertThatThrownBy(() -> embeddings.upsert(embedding(segmentId, docId, "x", 0.1f, 1024)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1024")
        .hasMessageContaining(String.valueOf(DIM));

    assertThat(embeddings.findById(segmentId)).as("失败的写入不许留下半条记录").isEmpty();
    assertThat(embeddings.countByDocumentId(docId)).isZero();
  }

  // ────────────────────────── 2 · 可追溯：模型与维度随行 ──────────────────────────

  /**
   * ★ {@code model_id} / {@code dimension} 必须随行落进 metadata —— 换模型后
   * 「哪些向量是旧模型生成的、需要重灌」必须是一个<strong>可执行的查询</strong>（{@code 02} 的原话）， 而不是靠记忆。
   */
  @Test
  void 元数据里记录了模型与维度() {
    UUID segmentId = UUID.randomUUID();
    UUID modelId = UUID.randomUUID();
    UUID kbId = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    embeddings.upsert(
        new SegmentEmbedding(segmentId, kbId, docId, modelId, "正文", vector(DIM, 0.25f)));

    String metadata =
        jdbc.queryForObject(
            "select metadata::text from vec_embedding_" + DIM + " where id = ?",
            String.class,
            segmentId);
    assertThat(metadata)
        .as("metadata 是「这份向量是谁生成的」的唯一载体")
        .contains(modelId.toString())
        .contains(kbId.toString())
        .contains(docId.toString())
        .contains("\"dimension\"");

    SegmentEmbedding loaded = embeddings.findById(segmentId).orElseThrow();
    assertThat(loaded.modelId()).isEqualTo(modelId);
    assertThat(loaded.kbId()).isEqualTo(kbId);
  }

  /** ★ 表形必须与 Spring AI {@code PgVectorStore} 硬编码的列名一致 —— 这是"将来接框架检索不用改表"的根据。 */
  @Test
  void 表形与框架硬编码列名一致() {
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = ?",
            String.class,
            "vec_embedding_" + DIM);
    assertThat(columns).contains("id", "content", "metadata", "embedding");
  }

  // ────────────────────────── 3 · 删除（分段删了，向量不能留） ──────────────────────────

  /** ★ 删分段必须连带删向量：否则<strong>被删掉的分段仍然能被检索到</strong> —— 界面不显示、检索却答得出来，是最难查的一种"看起来是对的"。 */
  @Test
  void 删分段与删文档都要把向量带走() {
    UUID docId = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    embeddings.upsert(
        new SegmentEmbedding(
            a, UUID.randomUUID(), docId, UUID.randomUUID(), "A", vector(DIM, 0.1f)));
    embeddings.upsert(
        new SegmentEmbedding(
            b, UUID.randomUUID(), docId, UUID.randomUUID(), "B", vector(DIM, 0.2f)));
    assertThat(embeddings.countByDocumentId(docId)).isEqualTo(2);

    embeddings.deleteBySegmentId(a);
    assertThat(embeddings.findById(a)).isEmpty();
    assertThat(embeddings.countByDocumentId(docId)).isEqualTo(1);

    embeddings.deleteByDocumentId(docId);
    assertThat(embeddings.countByDocumentId(docId)).isZero();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static SegmentEmbedding embedding(
      UUID segmentId, UUID docId, String content, float first) {
    return embedding(segmentId, docId, content, first, DIM);
  }

  private static SegmentEmbedding embedding(
      UUID segmentId, UUID docId, String content, float first, int dim) {
    return new SegmentEmbedding(
        segmentId, UUID.randomUUID(), docId, UUID.randomUUID(), content, vector(dim, first));
  }

  private static float[] vector(int dim, float first) {
    float[] v = new float[dim];
    v[0] = first;
    for (int i = 1; i < dim; i++) {
      v[i] = 0.001f * i;
    }
    return v;
  }

  private long rowCount(UUID segmentId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from vec_embedding_" + DIM + " where id = ?", Long.class, segmentId);
    return count == null ? 0 : count;
  }
}
