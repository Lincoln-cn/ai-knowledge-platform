package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 给记忆写向量（设计册 §3.3：向量存 {@code cv_memory.embedding}，不进 {@code vec_embedding_384}）。
 *
 * <p>★ 为什么单独一个组件而不是塞进 {@link cn.lincoln.aiplatform.conversation.application.MemoryService}：
 * 向量是<b>索引物</b>，不是记忆本身的状态 —— 写维度、选模型、失败怎么办，这些只跟"以后能不能按相关性排" 有关，跟"这条记忆对不对"无关。混在一起会让记忆的写入路径依赖
 * embedding 模型是否可用， 而模型不可用时记忆仍然该能写（{@code embedding} 列可空就是为这个）。
 *
 * <p>★ 失败必须吞掉并只打 WARN：一次向量化失败不该让已经抽出来的记忆丢失。 代价是"这条记忆暂时只能靠重要度被召回"，而这正是 {@code embedding} 可空时要保住的语义。
 */
@Component
public class MemoryEmbeddingWriter {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryEmbeddingWriter.class);

  /** 当前可用的向量模型（{@code kind = 'EMBEDDING' and enabled'}，按名称取第一个）。 */
  private static final String PICK_MODEL =
      "select id from ai_model where kind = 'EMBEDDING' and enabled = true order by name limit 1";

  private final JdbcTemplate jdbc;
  private final MemoryRepositoryAdapter adapter;
  private EmbeddingPort embeddingPort;
  private UUID cachedModelId;

  /**
   * @param jdbc 用于挑默认向量模型（不引模型目录的仓储：这里只读一列，多一条依赖路径不划算）
   * @param adapter 负责把向量文本写回 {@code cv_memory.embedding}
   */
  public MemoryEmbeddingWriter(JdbcTemplate jdbc, MemoryRepositoryAdapter adapter) {
    this.jdbc = jdbc;
    this.adapter = adapter;
  }

  /**
   * ★ setter 可选注入：{@code EmbeddingPort} 只在装配了向量模型时存在， 而"没装向量模型"是完全合法的一种部署（记忆仍能用，只是不按相关性排）。
   *
   * @param embeddingPort 向量化端口（空 = 没有向量能力）
   */
  @Autowired(required = false)
  public void setEmbeddingPort(EmbeddingPort embeddingPort) {
    this.embeddingPort = embeddingPort;
  }

  /**
   * 给一条记忆写向量。
   *
   * @param memoryId 记忆 id
   * @param content 记忆正文
   * @return 是否真的写进去了（{@code false} 表示这条以后只能按重要度排）
   */
  public boolean write(UUID memoryId, String content) {
    if (embeddingPort == null || content == null || content.isBlank()) {
      return false;
    }
    Optional<UUID> modelId = modelId();
    if (modelId.isEmpty()) {
      LOG.warn("记忆向量化跳过：库里没有启用中的 EMBEDDING 模型，这条记忆以后只按重要度参与召回");
      return false;
    }
    try {
      List<float[]> embedded = embeddingPort.embed(modelId.get(), List.of(content));
      if (embedded == null || embedded.isEmpty() || embedded.getFirst() == null) {
        LOG.warn("记忆向量化失败：模型 {} 没有返回向量（记忆 {} 仍可用，只是不参与相似度排序）", modelId.get(), memoryId);
        return false;
      }
      adapter.writeEmbedding(memoryId, toVectorLiteral(embedded.getFirst()));
      return true;
    } catch (RuntimeException e) {
      /* ★ 记忆已经写进表了，向量没写上不是"这次记忆丢了"，只是这一条少了一路排序信号。 */
      LOG.warn("记忆向量化异常，已忽略（记忆 {} 仍可用，只是不参与相似度排序）：{}", memoryId, e.toString());
      return false;
    }
  }

  private Optional<UUID> modelId() {
    if (cachedModelId != null) {
      return Optional.of(cachedModelId);
    }
    List<UUID> found = jdbc.queryForList(PICK_MODEL, UUID.class);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    cachedModelId = found.getFirst();
    return Optional.of(cachedModelId);
  }

  /**
   * 当前用来算相似度的向量模型。
   *
   * @return 模型 id（没有启用的 EMBEDDING 模型时为空）
   */
  public Optional<UUID> currentModelId() {
    return modelId();
  }

  /**
   * 批量读回向量（只读有向量的那些）。
   *
   * <p>★ 用原生 SQL 读 {@code embedding::text} 而不是让 JPA 映射 {@code vector} 类型：
   * 实体故意不映射这一列（它是索引物，不是领域状态），为读它给实体加一个字段， 等于把"向量化失败"变成"实体加载失败"。
   *
   * @param ids 记忆 id
   * @return id → 向量（没有向量的那条不在结果里）
   */
  public Map<UUID, float[]> loadVectors(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    try {
      List<Map.Entry<UUID, float[]>> rows =
          jdbc.query(
              "select id, embedding::text as v from cv_memory where id = any (?) and embedding is"
                  + " not null",
              (rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), parse(rs.getString("v"))),
              ids.toArray(new UUID[0]));
      Map<UUID, float[]> out = new java.util.HashMap<>(rows.size());
      rows.forEach(e -> out.put(e.getKey(), e.getValue()));
      return out;
    } catch (RuntimeException e) {
      LOG.warn("读记忆向量失败，本次退回按重要度排序：{}", e.toString());
      return Map.of();
    }
  }

  /**
   * 把 pgvector 的文本形式解析成数组。
   *
   * @param text 形如 {@code [0.1,0.2]}
   * @return 向量
   */
  static float[] parse(String text) {
    if (text == null || text.length() < 2) {
      return new float[0];
    }
    String body = text.substring(1, text.length() - 1).trim();
    if (body.isEmpty()) {
      return new float[0];
    }
    String[] parts = body.split(",");
    float[] out = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = Float.parseFloat(parts[i].trim());
    }
    return out;
  }

  /**
   * 转 pgvector 的字面量形式（{@code [0.1,0.2,…]}）。
   *
   * <p>★ 不用 {@code Arrays.toString}：它给出的是 {@code [0.1, 0.2]}（带空格）， pgvector 接受这种写法，但同一个值在日志与 SQL
   * 里会有两种形态，排查时容易误判"写进去的东西不一样"。
   *
   * @param vector 向量
   * @return 字面量
   */
  static String toVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(vector[i]);
    }
    return sb.append(']').toString();
  }
}
