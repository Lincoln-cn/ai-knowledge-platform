package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.QaRecallPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 问答对召回实现（{@link QaRecallPort} 在 {@code knowledge} 侧的实现）。
 *
 * <h3>★★ 为什么不用 {@code plainto_tsquery} 判"命中"</h3>
 *
 * <p>实测（开发库 {@code aip-postgres}，{@code chinese_zh} 配置）：
 *
 * <pre>
 *   to_tsvector('chinese_zh', '财务资产部负责哪些预算事项')
 *     → 事项 | 负责 | 财务 | 资产 | 部 | 预算        ← 机构名被切碎，这是分词器行为，不是 bug
 *   plainto_tsquery('chinese_zh', '预算科负责什么')   ← **AND 语义**
 *     @@ to_tsvector('chinese_zh', '预算科的职责范围')   → false
 * </pre>
 *
 * <p>也就是说：{@code plainto_tsquery} 要求"问句的<strong>每一个</strong>词都出现在标定问法里"， 而同一件事的两种说法必然差一两个词 ⇒
 * 它会漏掉几乎所有真实命中。所以这里分两步：
 *
 * <ol>
 *   <li><strong>粗筛走索引</strong>：{@code question_tsv @@ to_tsquery('chinese_zh', 'w1 | w2 | …')} ——
 *       OR 语义（有一个词交集就进候选），能用上 {@code idx_qa_pair_question_tsv}；
 *   <li><strong>精排算 F1</strong>：词元集合的调和平均，见 {@link #similarity}。
 * </ol>
 *
 * <p>★ 为什么不直接把 {@code ts_rank} 当相似度：实测 {@code ts_rank} 对一次"完全相同的问法"只给 <strong>0.7856</strong> ——
 * 它没有归一到 0..1，把它当阈值用就必须为每一种问句长度配一个魔数， 而"阈值 0.6"会因此变成"阈值 0.6 除以某个忘了写的系数"。
 *
 * <h3>★ 密级上限由这里自己取，不由调用方传</h3>
 *
 * <p>见 {@link QaRecallPort} 的类注释：一个能被漏传的越权闸门等于没有闸门。未认证 ⇒ {@code maxClassification == null} ⇒ rank
 * {@code -1} ⇒ 谓词恒假 ⇒ 空结果（fail-closed）。
 */
@Component
public class JdbcQaRecallAdapter implements QaRecallPort {

  /** 候选粗筛上限：词元多的问句会撞上大半个库，粗筛不设上限等于把排序压力交给内存。 */
  private static final int CANDIDATE_LIMIT = 50;

  /**
   * 词元 → 候选的 SQL。
   *
   * <p>★ 三处细节都不要省：
   *
   * <ul>
   *   <li>{@code k.enabled and k.deleted_at is null}：库停用 / 删除之后，它的问答对也不许再被召回 ——
   *       这与分段检索的口径必须一致（{@code JdbcSegmentSearchQuery} 的 {@code BASE} 谓词）；
   *   <li>{@code q.classification_rank <= ?}：密级 pre-filter 必须在<strong>同一条 SQL</strong>里 （{@code 13
   *       §三} 闸门②），不能"查出来再过滤"；
   *   <li>返回 {@code toks} 而不是在 Java 里再调一次 {@code to_tsvector}：分词配置只有一处 （库里的 {@code
   *       chinese_zh}），Java 侧不许自己再实现一份"怎么切词"。
   * </ul>
   */
  private static final String CANDIDATE_SQL =
      """
      select q.id,
             q.question,
             q.answer,
             q.segment_id,
             q.direct_answer,
             q.classification,
             tsvector_to_array(to_tsvector('chinese_zh', q.question)) as toks
        from kb_qa_pair q
        join kb_knowledge_base k on k.id = q.kb_id
       where q.deleted_at is null
         and k.deleted_at is null
         and k.enabled
         and q.kb_id in (%s)
         and q.classification_rank <= ?
         and q.question_tsv @@ to_tsquery('chinese_zh', ?)
       order by q.created_at desc
       limit ?
      """;

  private final JdbcTemplate jdbc;
  private final AccessScopePort accessScope;

  public JdbcQaRecallAdapter(JdbcTemplate jdbc, AccessScopePort accessScope) {
    this.jdbc = jdbc;
    this.accessScope = accessScope;
  }

  @Override
  public List<Match> recall(Query query) {
    if (query == null || query.question() == null || query.question().isBlank()) {
      return List.of();
    }
    if (query.kbIds() == null || query.kbIds().isEmpty()) {
      // ★ 空域不是"全部可见"：那是 fail-open。空域按定义就是没有可检索的库。
      return List.of();
    }

    List<String> tokens = tokensOf(query.question());
    if (tokens.isEmpty()) {
      // 全是停用词 / 标点 ⇒ 没有可匹配的东西。返回空而不是"退回全表"。
      return List.of();
    }

    int limit = Math.min(Math.max(1, query.limit()), CANDIDATE_LIMIT);
    String placeholders =
        String.join(", ", java.util.Collections.nCopies(query.kbIds().size(), "?"));
    List<Object> args = new ArrayList<>(query.kbIds().size() + 3);
    args.addAll(query.kbIds());
    args.add(clearanceRank());
    args.add(orQuery(tokens));
    args.add(limit);

    List<Match> hits = new ArrayList<>();
    jdbc.query(
        String.format(CANDIDATE_SQL, placeholders),
        rs -> {
          Set<String> pairTokens =
              new LinkedHashSet<>(List.of((String[]) rs.getArray("toks").getArray()));
          double score = similarity(tokens, pairTokens);
          if (score < query.minScore()) {
            return;
          }
          UUID segmentId = (UUID) rs.getObject("segment_id");
          hits.add(
              new Match(
                  (UUID) rs.getObject("id"),
                  rs.getString("question"),
                  rs.getString("answer"),
                  segmentId,
                  rs.getBoolean("direct_answer"),
                  ClassificationLevel.valueOf(rs.getString("classification")),
                  score));
        },
        args.toArray());

    hits.sort(Comparator.comparingDouble(Match::score).reversed());
    return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
  }

  /**
   * 问句的词元（走库里的 {@code chinese_zh} 分词）—— 与 {@code question_tsv} 生成列<strong>同一个配置</strong>。
   *
   * <p>★ 不在 Java 里自己切词：那样会有两套分词口径，而"库里按 A 切、Java 按 B 切"的症状是 召回率莫名偏低，且没有任何一处报错。
   */
  private List<String> tokensOf(String question) {
    // ★ 不能用 queryForObject(..., String[].class)：tsvector_to_array 在 pgjdbc 里给的是 PgArray，
    //   Spring 的标量转换认不了它 => TypeMismatchDataAccess（本条第一版就是这么红的）。
    //   也不许自己拆数组字面量：词元里可能带逗号与空格。
    return jdbc.query(
        "select tsvector_to_array(to_tsvector('chinese_zh', ?)) as toks",
        rs -> {
          List<String> out = new ArrayList<>();
          while (rs.next()) {
            java.sql.Array array = rs.getArray("toks");
            if (array != null) {
              out.addAll(List.of((String[]) array.getArray()));
            }
          }
          return out;
        },
        question);
  }

  /**
   * 词元集合 → {@code tsquery} 的 OR 形式（{@code 'w1' | 'w2' | …}）。
   *
   * <p>★ 每个词元都用单引号包成短语并转义内部单引号：词元来自用户问句，直接拼进 {@code to_tsquery} 的参数是注入面（{@code :} / {@code &} /
   * {@code |} / {@code !} 都是 tsquery 的语法字符）。 包成 {@code '…'} 之后它们一律按字面量处理。
   */
  private static String orQuery(List<String> tokens) {
    StringBuilder sql = new StringBuilder();
    for (String token : tokens) {
      if (!sql.isEmpty()) {
        sql.append(" | ");
      }
      sql.append('\'').append(token.replace("'", "''")).append('\'');
    }
    return sql.toString();
  }

  /**
   * 词元集合的 F1（precision 与 recall 的调和平均）。
   *
   * <p>为什么用 F1 而不是"交了几个词"：
   *
   * <ul>
   *   <li>只数交集 ⇒ 长问句天然占便宜（"财务资产部负责哪些预算事项"能与任何提到"财务"的问答对都有 1 个交集）；
   *   <li>只算 precision（交集 / 标定问法的词数）⇒ 只要标定问法长，短问句也能拿高分；
   *   <li>F1 两边都压住：{@code '预算科负责什么'}（3 词）与 {@code '预算科的职责范围'}（4 词）交集 2 词 ⇒ P=0.5、R=0.67、F1≈0.57 ——
   *       低于默认阈值 0.6，于是<strong>不直答</strong>， 这是刻意的：同一件事的两种说法值得召回，但不值得跳过模型。
   * </ul>
   *
   * @param questionTokens 问句词元（★ 不能为空，调用方已判）
   * @param pairTokens 标定问法的词元
   * @return 0..1；任一集合为空 ⇒ 0
   */
  static double similarity(Collection<String> questionTokens, Collection<String> pairTokens) {
    if (questionTokens == null
        || pairTokens == null
        || questionTokens.isEmpty()
        || pairTokens.isEmpty()) {
      return 0d;
    }
    Set<String> q = new HashSet<>(questionTokens);
    Set<String> t = new HashSet<>(pairTokens);
    Set<String> shared = new HashSet<>(q);
    shared.retainAll(t);
    if (shared.isEmpty()) {
      return 0d;
    }
    double precision = (double) shared.size() / t.size();
    double recall = (double) shared.size() / q.size();
    return 2 * precision * recall / (precision + recall);
  }

  /** 当前用户的密级上限 → {@code classification_rank} 同口径整数；未认证 ⇒ -1（谓词恒假）。 */
  private int clearanceRank() {
    ClassificationLevel clearance = accessScope.currentScope().maxClassification();
    return clearance == null ? -1 : clearance.ordinal();
  }
}
