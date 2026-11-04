package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.application.ModelReference;
import cn.lincoln.aiplatform.modelcatalog.application.ModelReferencePort;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 引用面读数的 JDBC 实现（{@code #300}）。
 *
 * <p>★ 为什么是直查 SQL、而不是调 {@code AssistantService} / {@code KnowledgeBaseService}：{@code modelcatalog}
 * 的模块边界只允许依赖 {@code platform}（{@code package-info.java} + {@code ModuleBoundaryTest}），
 * 而引用落在三个别的模块的表里。反向注册（让每个模块告诉模型"我引用你了"）会把依赖箭头掉个头。 这里只读、只取名字与计数 —— 直查比引三层服务干净，与 {@code
 * JdbcModelIdentityAdapter} 是同一个判断。
 *
 * <p>★ 三层判定都在 SQL 里，不在 Java 里：软删（{@code deleted_at is null}）与"未删文档"必须与在线路径的谓词一致，
 * 否则守卫会数出一批<strong>页面根本看不见</strong>的引用，让人对着一个空列表删不掉东西。
 *
 * <p>★ 文档没有独立的向量模型列（见 {@link ModelReference} 的类注释）：它是"库的文档"， 所以文档计数走 {@code kb_document ⋈
 * kb_knowledge_base}，谓词落在库的 {@code embedding_model_id} 上。
 */
@Component
public class JdbcModelReferencePort implements ModelReferencePort {

  private final JdbcTemplate jdbc;

  public JdbcModelReferencePort(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ModelReference ofModel(UUID modelId) {
    List<ModelReference.Named> apps =
        named(
            "select id, name from as_assistant"
                + " where model_id = ? and deleted_at is null order by name limit ?",
            modelId);
    List<ModelReference.Named> kbs =
        named(
            "select id, name from kb_knowledge_base"
                + " where embedding_model_id = ? and deleted_at is null order by name limit ?",
            modelId);
    long documents = documentsOf("=?", modelId);
    return new ModelReference(apps, kbs, documents, defaultKeys("=?", modelId), 0, List.of());
  }

  @Override
  public ModelReference ofProvider(UUID providerId) {
    long models = count("select count(*) from ai_model where provider_id = ?", providerId);
    if (models == 0) {
      return ModelReference.NONE;
    }
    List<ModelReference.Named> modelNames = modelsOfProvider(providerId, ModelReference.MAX_NAMES);
    String modelSubquery = "(select id from ai_model where provider_id = ?)";
    List<ModelReference.Named> apps =
        named(
            "select id, name from as_assistant where model_id in "
                + modelSubquery
                + " and deleted_at is null order by name limit ?",
            providerId);
    List<ModelReference.Named> kbs =
        named(
            "select id, name from kb_knowledge_base where embedding_model_id in "
                + modelSubquery
                + " and deleted_at is null order by name limit ?",
            providerId);
    long documents = documentsOf("in " + modelSubquery, providerId);
    List<String> defaultKeys = defaultKeys("in " + modelSubquery, providerId);
    return new ModelReference(
        apps,
        kbs,
        documents,
        defaultKeys,
        models,
        modelNames.stream().map(ModelReference.Named::name).toList());
  }

  @Override
  public Map<UUID, Long> countsByModel(Collection<UUID> modelIds) {
    if (modelIds == null || modelIds.isEmpty()) {
      return Map.of();
    }
    // ★ 用一次 union all 数完四类引用：分四条 SQL 再在 Java 里合并，多了三次往返与一份容易漏掉其一的合并代码。
    //   参数是一个逗号分隔的 uuid 串，走 string_to_array —— 避免为 each 调用 createArrayOf，也避免拼 SQL 字面量。
    String ids = modelIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    String unioned =
        "select model_id from as_assistant"
            + " where deleted_at is null and model_id = any(string_to_array(?, ',')::uuid[])"
            + " union all"
            + " select embedding_model_id from kb_knowledge_base"
            + " where deleted_at is null"
            + " and embedding_model_id = any(string_to_array(?, ',')::uuid[])"
            + " union all"
            + " select b.embedding_model_id from kb_document d"
            + " join kb_knowledge_base b on b.id = d.kb_id"
            + " where d.deleted_at is null and b.deleted_at is null"
            + " and b.embedding_model_id = any(string_to_array(?, ',')::uuid[])"
            + " union all"
            + " select default_model_id from cv_chat_settings"
            + " where default_model_id = any(string_to_array(?, ',')::uuid[])";
    Map<UUID, Long> counts = new LinkedHashMap<>();
    jdbc.query(
        "select model_id, count(*) as refs from (" + unioned + ") refs group by model_id",
        rs -> {
          counts.put(rs.getObject("model_id", UUID.class), rs.getLong("refs"));
        },
        ids,
        ids,
        ids,
        ids);
    return counts;
  }

  @Override
  public List<ModelReference.Named> modelsOfProvider(UUID providerId, int limit) {
    return jdbc.query(
        "select id, name from ai_model where provider_id = ? order by name limit ?",
        (rs, rowNum) ->
            new ModelReference.Named(rs.getObject("id", UUID.class), rs.getString("name")),
        providerId,
        limit);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 具名引用（应用 / 知识库）。
   *
   * @param sql 形如 {@code select id, name from … where <谓词> and deleted_at is null …}，末位参数是 limit
   * @param predicateValue 谓词右值（固定 {@code =?} 时不传 {@code any(...)}）
   */
  private List<ModelReference.Named> named(String sql, Object predicateValue) {
    return jdbc.query(
        sql,
        (rs, rowNum) ->
            new ModelReference.Named(rs.getObject("id", UUID.class), rs.getString("name")),
        predicateValue,
        ModelReference.MAX_NAMES);
  }

  /** 这些库下的未删文档数（{@code predicate} 作用在 {@code b.embedding_model_id} 上）。 */
  private long documentsOf(String predicate, Object value) {
    return count(
        "select count(*) from kb_document d join kb_knowledge_base b on b.id = d.kb_id"
            + " where b.embedding_model_id "
            + predicate
            + " and b.deleted_at is null and d.deleted_at is null",
        value);
  }

  /**
   * 平台默认键：今天只有 {@code cv_chat_settings.default_model_id} 一处（issue 里写的 {@code
   * platform_settings.chat.model} 在库里不存在；"平台默认向量模型"还没落地，所以没有 embedding 键可报）。
   */
  private List<String> defaultKeys(String predicate, Object value) {
    long rows =
        count("select count(*) from cv_chat_settings where default_model_id " + predicate, value);
    return rows > 0 ? List.of("cv_chat_settings.default_model_id") : List.of();
  }

  private long count(String sql, Object value) {
    Long found = jdbc.queryForObject(sql, Long.class, value);
    return found == null ? 0L : found;
  }
}
