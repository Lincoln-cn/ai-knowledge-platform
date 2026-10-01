package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbedding;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量表的写入适配器（{@code vec_embedding_<维度>}）。
 *
 * <p>★ 为什么用原生 SQL 而不是 JPA：向量列是 pgvector 的 {@code vector} 类型，而项目里<strong>没有</strong> pgvector 的 Java
 * 类型库（依赖选型是 {@code 09} 的事，不在这里顺手加）。用字符串强转 （{@code cast(? as vector)}，字形 {@code
 * [1,2,3]}）就能写，且<strong>表形仍然与框架一致</strong> —— 将来源用 {@code PgVectorStore} 时只需要换写入方，不用动表和已写的数据。
 *
 * <p>★ 幂等 upsert：{@code insert … on conflict (id) do update}（{@code 03 §3.3}）。用冲突子句而不是
 * "先查再写"，是因为后者在并发下会两个都写、或一个报唯一键冲突 —— 幂等要求的是<strong>无论投几次结果一致</strong>。
 */
@Component
public class JdbcSegmentEmbeddingAdapter implements SegmentEmbeddingRepository {

  /**
   * 向量表维度 —— 表名 {@code vec_embedding_768} 里那个数。
   *
   * <p>★ 维度是<strong>列类型</strong>（{@code vector(N)}），一张表只能装一个维度（{@code 02}）。 换维度是换表，不是改列。
   */
  static final int TABLE_DIMENSION = 768;

  private static final String TABLE = "vec_embedding_" + TABLE_DIMENSION;

  private final JdbcTemplate jdbc;

  public JdbcSegmentEmbeddingAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(SegmentEmbedding embedding) {
    if (embedding.dimension() != TABLE_DIMENSION) {
      // ★ 明确失败，不静默截断/补零：补零会让"这份向量是假的"变成查不出来的事
      throw new IllegalArgumentException(
          "向量维度不匹配：模型返回 "
              + embedding.dimension()
              + " 维，而向量表 "
              + TABLE
              + " 只接受 "
              + TABLE_DIMENSION
              + " 维（换维度是换表、不是改列，见 V16 迁移注释）");
    }
    jdbc.update(
        "insert into "
            + TABLE
            + " (id, content, metadata, embedding) values (?, ?, cast(? as json), cast(? as"
            + " vector)) on conflict (id) do update set content = excluded.content, metadata ="
            + " excluded.metadata, embedding = excluded.embedding",
        embedding.segmentId(),
        embedding.content(),
        metadataJson(embedding),
        toVectorLiteral(embedding.vector()));
  }

  @Override
  public Optional<SegmentEmbedding> findById(UUID segmentId) {
    var rows =
        jdbc.query(
            "select id, content, metadata::text as metadata, embedding::text as embedding"
                + " from "
                + TABLE
                + " where id = ?",
            (rs, i) ->
                new Object[] {
                  rs.getObject("id", UUID.class),
                  rs.getString("content"),
                  rs.getString("metadata"),
                  rs.getString("embedding")
                },
            segmentId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    String metadata = (String) row[2];
    return Optional.of(
        new SegmentEmbedding(
            (UUID) row[0],
            uuidField(metadata, "kb_id"),
            uuidField(metadata, "document_id"),
            uuidField(metadata, "model_id"),
            (String) row[1],
            parseVectorLiteral((String) row[3])));
  }

  @Override
  public long countByDocumentId(UUID documentId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from " + TABLE + " where (metadata::jsonb ->> 'document_id') = ?",
            Long.class,
            documentId.toString());
    return count == null ? 0 : count;
  }

  @Override
  public void deleteBySegmentId(UUID segmentId) {
    jdbc.update("delete from " + TABLE + " where id = ?", segmentId);
  }

  @Override
  public void deleteByDocumentId(UUID documentId) {
    jdbc.update(
        "delete from " + TABLE + " where (metadata::jsonb ->> 'document_id') = ?",
        documentId.toString());
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * metadata 是"随行记录的追溯信息"（{@code 02}）：模型 id 与维度必须进得去，否则换模型后无法判断哪些向量是旧的。
   *
   * <p>手拼 JSON 而不是引 ObjectMapper：字段就这四个、全是可控值（UUID 与整数），没有转义风险。
   */
  private static String metadataJson(SegmentEmbedding e) {
    return "{\"kb_id\":\"%s\",\"document_id\":\"%s\",\"model_id\":\"%s\",\"dimension\":%d}"
        .formatted(e.kbId(), e.documentId(), e.modelId(), e.dimension());
  }

  /** pgvector 的文本字形：{@code [0.1,0.2,…]}；浮点用最小表示，避免超长字符串。 */
  static String toVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
    sb.append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(vector[i]));
    }
    return sb.append(']').toString();
  }

  static float[] parseVectorLiteral(String literal) {
    String body = literal.trim();
    if (body.startsWith("[")) {
      body = body.substring(1);
    }
    if (body.endsWith("]")) {
      body = body.substring(0, body.length() - 1);
    }
    if (body.isEmpty()) {
      return new float[0];
    }
    String[] parts = body.split(",");
    float[] vector = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      vector[i] = Float.parseFloat(parts[i].trim());
    }
    return vector;
  }

  /** 从 metadata JSON 里取 uuid 字段 —— 用正则而不是 JSON 解析器，见 {@link #metadataJson} 的理由。 */
  private static UUID uuidField(String metadata, String field) {
    var m =
        java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(metadata == null ? "" : metadata);
    return m.find() ? UUID.fromString(m.group(1)) : null;
  }

  /** 便于测试与日志：表名。 */
  static String tableName() {
    return TABLE;
  }
}
