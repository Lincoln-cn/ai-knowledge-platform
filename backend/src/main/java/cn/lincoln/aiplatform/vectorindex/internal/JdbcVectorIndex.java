package cn.lincoln.aiplatform.vectorindex.internal;

import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量索引的实现（{@code vectorindex}）—— <strong>表名、表形、维度路由的唯一权威</strong>。
 *
 * <h3>表形从哪来</h3>
 *
 * <p>{@code V16__knowledge_vec_embedding.sql} 建的是 {@code vec_embedding_768}，列名照 Spring AI 的 {@code
 * PgVectorStore}（{@code id / content / metadata / embedding}，列名在框架源码里硬编码 ⇒
 * 不能改）。本类建新维度的表时<strong>逐字照抄那份形状</strong>，含 HNSW 索引及其参数 （{@code m=16, ef_construction=64}）——
 * 否则"换个维度的库"会比 768 那张表慢， 而慢的原因藏在索引参数里，极难查。
 *
 * <h3>★ 维度路由（issue #5）</h3>
 *
 * <p>维度是列类型（{@code vector(N)}）⇒ 一张表只能装一个维度。此前 {@code knowledge} 里硬编码了 {@code vec_embedding_768}
 * 并在维度不符时抛错 —— 行为是"明确失败"（对），但结果是 <strong>换模型就只能报错</strong>（缺路由）。现在：写按 {@code entry.vector().length}
 * 路由、 读按 id 跨表找，两边都不需要调用方知道表名。
 *
 * <h3>跨表找 id 为什么可以这么写</h3>
 *
 * <p>{@code segmentId} 是主键且一个分段只会在<strong>一张</strong>表里有向量 （同一时刻一个库一个向量模型）⇒ 逐表查、命中即返回是正确的。而"有哪些向量表"
 * 从 {@code information_schema} 读（不是硬编码的维度列表）—— 维度表的集合本来就是运行时长出来的。
 */
@Component
public class JdbcVectorIndex implements VectorIndexPort {

  /** 表名只由它拼出来。★ 正则只允许数字：维度是从模型来的外部值，不能有任何拼接进 SQL 的可能。 */
  private static final Pattern TABLE_NAME = Pattern.compile("vec_embedding_\\d{1,5}");

  private static final Pattern DIGITS = Pattern.compile("\\d{1,5}");

  private final JdbcTemplate jdbc;

  public JdbcVectorIndex(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String tableFor(int dimension) {
    if (dimension <= 0 || !DIGITS.matcher(String.valueOf(dimension)).matches()) {
      throw new IllegalArgumentException("向量维度非法：" + dimension + "（表名由维度拼出，只接受正整数）");
    }
    return "vec_embedding_" + dimension;
  }

  @Override
  public void ensureTable(int dimension) {
    String table = tableFor(dimension);
    jdbc.execute(
        "create table if not exists "
            + table
            + " (id uuid primary key, content text not null, metadata json, embedding vector("
            + dimension
            + ") not null)");
    // ★ 索引单独一句：`create table if not exists` 命中已存在的表时不会补索引，
    //   而"表在、索引不在"是最阴的一种状态 —— 查询照样出结果，只是全表扫。
    jdbc.execute(
        "create index if not exists idx_vec_"
            + dimension
            + "_hnsw on "
            + table
            + " using hnsw (embedding vector_cosine_ops) with (m = 16, ef_construction = 64)");
    // ★ 与 V16 一致：按 metadata 里的 kb_id 建表达式索引（限定库检索/过滤时用）
    jdbc.execute(
        "create index if not exists idx_vec_"
            + dimension
            + "_kb on "
            + table
            + " ((metadata::jsonb ->> 'kb_id'))");
  }

  @Override
  public void upsert(Entry entry) {
    int dimension = entry.vector() == null ? 0 : entry.vector().length;
    if (dimension <= 0) {
      // ★ 明确失败，不静默截断/补零：补零会让"这份向量是假的"变成查不出来的事
      throw new IllegalArgumentException("向量为空：无法确定该进哪张表（分段 " + entry.segmentId() + "）");
    }
    String table = tableFor(dimension);
    ensureTable(dimension);
    jdbc.update(
        "insert into "
            + table
            + " (id, content, metadata, embedding) values (?, ?, cast(? as json), cast(? as"
            + " vector)) on conflict (id) do update set content = excluded.content, metadata ="
            + " excluded.metadata, embedding = excluded.embedding",
        entry.segmentId(),
        entry.content(),
        metadataJson(entry, dimension),
        toVectorLiteral(entry.vector()));
  }

  @Override
  public Optional<Entry> findById(UUID segmentId) {
    for (String table : existingTables()) {
      List<Object[]> rows =
          jdbc.query(
              "select id, content, metadata::text as metadata, embedding::text as embedding"
                  + " from "
                  + table
                  + " where id = ?",
              (rs, i) ->
                  new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getString("content"),
                    rs.getString("metadata"),
                    rs.getString("embedding")
                  },
              segmentId);
      if (!rows.isEmpty()) {
        Object[] row = rows.get(0);
        String metadata = (String) row[2];
        return Optional.of(
            new Entry(
                (UUID) row[0],
                uuidField(metadata, "kb_id"),
                uuidField(metadata, "document_id"),
                uuidField(metadata, "model_id"),
                (String) row[1],
                parseVectorLiteral((String) row[3])));
      }
    }
    return Optional.empty();
  }

  @Override
  public long countByDocument(UUID documentId) {
    long total = 0;
    for (String table : existingTables()) {
      Long count =
          jdbc.queryForObject(
              "select count(*) from " + table + " where (metadata::jsonb ->> 'document_id') = ?",
              Long.class,
              documentId.toString());
      total += count == null ? 0 : count;
    }
    return total;
  }

  @Override
  public void deleteById(UUID segmentId) {
    for (String table : existingTables()) {
      jdbc.update("delete from " + table + " where id = ?", segmentId);
    }
  }

  @Override
  public void deleteByDocument(UUID documentId) {
    for (String table : existingTables()) {
      jdbc.update(
          "delete from " + table + " where (metadata::jsonb ->> 'document_id') = ?",
          documentId.toString());
    }
  }

  @Override
  public List<Hit> search(Query query) {
    if (query.limit() <= 0) {
      return List.of();
    }
    String table = tableFor(query.dimension());
    // ★ 读路径**绝不建表**：检索是只读事务（@Transactional(readOnly = true)），
    //   在里面跑 DDL 会被 PG 直接拒绝（"cannot execute CREATE TABLE in a read-only transaction"）。
    //   而语义上也更对：表不存在 = 这个维度还没有任何向量 ⇒ **空结果**，不是错误。
    //   （建表只发生在写入路径 —— 那时确实有向量要落。）
    if (!tableExists(table)) {
      return List.of();
    }

    List<UUID> kbIds = query.kbIds() == null ? List.of() : query.kbIds();
    StringBuilder sql =
        new StringBuilder(
            "select id, embedding <=> cast(? as vector) as distance from " + table + " where true");
    List<Object> args = new ArrayList<>();
    args.add(toVectorLiteral(query.vector()));
    if (!kbIds.isEmpty()) {
      // ★ 逐值占位符而不是 array 参数：JdbcTemplate 会把数组参数当"多个参数"展开，
      //   而 `= any(?)` 需要真正的数组 —— 踩过这个坑，故显式拼占位符。
      sql.append(" and (metadata::jsonb ->> 'kb_id') in (")
          .append(placeholders(kbIds.size()))
          .append(")");
      kbIds.forEach(id -> args.add(id.toString()));
    }
    sql.append(" order by distance limit ?");
    args.add(query.limit());

    return jdbc.query(
        sql.toString(),
        (rs, i) -> new Hit(rs.getObject("id", UUID.class), rs.getDouble("distance")),
        args.toArray());
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 表在不在（{@code to_regclass} 会按 search_path 解析，不需要 information_schema 的字符串比较）。 */
  private boolean tableExists(String table) {
    Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
    return Boolean.TRUE.equals(exists);
  }

  /**
   * 库里现存的向量表（形如 {@code vec_embedding_<维度>}）。
   *
   * <p>★ 不硬编码维度清单：维度表的集合是**运行时长出来的**（换模型就多一张）。 只认符合命名规则的表名 —— 别的表（哪怕名字像）不碰。
   */
  private List<String> existingTables() {
    List<String> names =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = current_schema()"
                + " and table_name like 'vec\\_embedding\\_%'",
            String.class);
    List<String> tables = new ArrayList<>();
    for (String name : names) {
      if (TABLE_NAME.matcher(name).matches()) {
        tables.add(name);
      }
    }
    return tables;
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  /**
   * metadata 是"随行记录的追溯信息"（{@code 02}）：模型 id 与维度必须进得去， 否则换模型后无法判断哪些向量是旧的（也就无法"只重算过期的那部分"）。
   *
   * <p>字段就这几个、全是可控值（UUID 与整数）—— 与 V16 那边写入的字形一字不差， 否则存量数据的读取路径会与新写入的分裂。
   */
  private static String metadataJson(Entry entry, int dimension) {
    return "{\"kb_id\":\"%s\",\"document_id\":\"%s\",\"model_id\":\"%s\",\"dimension\":%d}"
        .formatted(entry.kbId(), entry.documentId(), entry.modelId(), dimension);
  }

  /** pgvector 的文本字形：{@code [0.1,0.2,…]}。浮点用最小表示，避免超长字符串。 */
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
    String body = literal == null ? "" : literal.trim();
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

  /** 从 metadata JSON 里取 uuid 字段 —— 用正则而不是 JSON 解析器，理由见 {@link #metadataJson}。 */
  private static UUID uuidField(String metadata, String field) {
    var m =
        Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(metadata == null ? "" : metadata);
    return m.find() ? UUID.fromString(m.group(1)) : null;
  }
}
