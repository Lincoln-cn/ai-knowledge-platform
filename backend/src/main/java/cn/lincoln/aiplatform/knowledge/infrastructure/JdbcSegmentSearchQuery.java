package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link SegmentSearchPort} 的实现（{@code knowledge} 侧）—— 全文与向量两路 + 安全谓词 + 命中详情。
 *
 * <h3>★ 查询串怎么拼（这一段有一条实测拦下来的坑）</h3>
 *
 * <p>分词权威是库里的 {@code chinese_zh} 配置（{@code V19} 建的那条）， <strong>不在 Java
 * 侧再分一次</strong>：入库时怎么分、检索时怎么分必须是同一套， 一旦漂移，症状是"明明有这段文字却搜不到"，而两边单测都绿。
 *
 * <p>★★ <strong>不许把用户问题整串丢给 {@code to_tsquery}</strong>。实测（PG 16.15 + zhparser 2.4）：
 *
 * <pre>
 *   to_tsquery('chinese_zh', '住宿标准是多少')  →  '住宿' &lt;-&gt; '标准' &lt;-&gt; '是'     ← 短语查询！
 *   to_tsquery('chinese_zh', '''住宿'' | ''标准''') →  '住宿' | '标准'            ← 我们要的
 * </pre>
 *
 * <p>第一行的形态很要命：{@code <->} 要求几个词**在文档里相邻**，于是"住宿标准是多少" 只能命中"里面逐字写着住宿标准是多少"的句子 —— 中文问答里几乎永远为空，
 * 而且它<strong>不报错</strong>。原因值得记：{@code to_tsquery} 把**一个 token 归一化成多个词元**时 用 {@code <->} 连接（这是 PG
 * 的既定行为，不是 zhparser 的怪癖）。
 *
 * <p>所以流程是两步：① 先问库要真词元（{@code tsvector_to_array(to_tsvector('chinese_zh', ?))}）； ② 用这些词元拼
 * <strong>OR</strong> 查询。用 OR 而不是 AND 也是刻意的 —— AND 语义下"住宿标准是多少"里那个「是」会让一份含「住宿标准」的合同一条都不命中。
 *
 * <h3>★ 安全谓词只有一份（第 11 周 · 0.3.3）</h3>
 *
 * <p>可见性与密级合成 {@link #SECURITY_PREDICATE}，<strong>三条查询（全文 / 向量 / 候选复判）用的都是它</strong>。
 * 这不是代码复用上的讲究，而是 {@code 13 §三} 闸门②的硬要求：<em>"只给一路加过滤条件"</em> 是本项目最贵的病，
 * 而合成的常量让"漏一路"从"忘了补一行"变成"得先把这两行拆开"。
 *
 * <h3>★ 阈值不在这条路上</h3>
 *
 * <p>返回的是"命中的"、按 {@code ts_rank} 降序，<strong>不做阈值过滤</strong>。 理由：阈值的量纲是"相似度"（{@code 10
 * §5.4}：它要随向量模型校准），而 {@code ts_rank} 无界 —— 拿 0–1 的阈值去卡它，全文模式会**永远为空**，而它恰恰是专有名词/编号类问题的唯一出路 （{@code
 * 03 §2.1} 的表）。每条路有自己的准入判据，融合交给 RRF。
 *
 * <h3>分数量纲</h3>
 *
 * <p>{@code coverage = 命中的查询词数 / 查询词数} ∈ (0,1] —— 界面上那个 0–1 的分数与阈值滑块
 * 在三种模式下都说得通（意思是"这次查询的词命中了几成"）。排序仍用 {@code ts_rank}（更专业）： <strong>排序看 rank、展示看 coverage</strong>。
 */
@Component
public class JdbcSegmentSearchQuery implements SegmentSearchPort {

  /** 只放行纯字词词元（中文字 / 字母 / 数字）。含 {@code | & ! ( ) :} 的一律丢弃。 */
  private static final Pattern SAFE_LEXEME = Pattern.compile("[\\p{IsHan}\\p{Alnum}]+");

  /** "不传 kbIds = 全部可见"时的上限。★ 不是分页，是一道"别把全库拉进内存"的闸。 */
  private static final int VISIBLE_KB_LIMIT = 500;

  /**
   * 可见性判定 —— 三级启用位（库 / 文档 / 分段）各自"启用且未软删"。
   *
   * <p>★ 它单独存在只是为了可读；<strong>任何查询都不许只用它</strong> —— 必须用 {@link #SECURITY_PREDICATE}。 只带它、不带密级条件，就是
   * {@code 13 §三} 点名的那个静默漏洞。
   */
  private static final String VISIBLE_PREDICATE =
      " s.enabled and d.enabled and d.deleted_at is null and k.enabled and k.deleted_at is null";

  /**
   * 密级谓词（第 11 周 · 0.3.3）—— 「**不高于我的 clearance**」。
   *
   * <p>★ 比的是 {@code s.classification_rank}（{@code V22} 在 {@code kb_segment} 上的生成列）， 不是密级字符串 ——
   * 字符串比较会把 {@code 'CONFIDENTIAL' &lt; 'INTERNAL'} 判成真（字典序！）， 那是一次**静默的越权**。
   *
   * <p>★ 为什么是分段那一列而不是文档那一列（{@code 13 §6.4}）：过滤必须能在 {@code kb_segment} 上 直接生效 —— {@code content_tsv}
   * 与 {@code kb_id} 都在它上面，两条检索路都在它上面过滤。 谓词只长在文档表上时，"两路同谓词"就退化成"两处 join 写法必须一致"，而那种一致没人能保证。
   */
  private static final String CLASSIFICATION_PREDICATE = " and s.classification_rank <= ?";

  /**
   * ★★ <strong>安全谓词 = 可见性 + 密级，三条查询用的都是这一份</strong>（{@code 13 §三} 闸门②：pre-filter，两路同谓词）。
   *
   * <p>把两者<strong>合成一个常量</strong>是刻意的结构性选择：只给一路加过滤条件（本项目最贵的病） 在这里变成"得先把这两行拆开"，而不是"忘了在另一处补一行"。
   * 三条查询（全文 / 向量 / 候选复判）全部引用它 —— 数量对不上就是评审时一眼能看出的东西。
   *
   * <p>★ 绑定值的位置约定：{@code ?} 出现在 {@link #JOINS} <strong>之后</strong>， 所以每条 SQL 的参数顺序都是「其余参数 → 密级 →
   * kbId 列表 → limit」。
   */
  private static final String SECURITY_PREDICATE = VISIBLE_PREDICATE + CLASSIFICATION_PREDICATE;

  private static final String JOINS =
      " from kb_segment s"
          + " join kb_document d on d.id = s.document_id"
          + " join kb_knowledge_base k on k.id = s.kb_id";

  private final JdbcTemplate jdbc;
  private final KnowledgeBaseRepository bases;
  private final AccessScopePort accessScope;
  private final VectorIndexPort vectorIndex;

  public JdbcSegmentSearchQuery(
      JdbcTemplate jdbc,
      KnowledgeBaseRepository bases,
      AccessScopePort accessScope,
      VectorIndexPort vectorIndex) {
    this.jdbc = jdbc;
    this.bases = bases;
    this.accessScope = accessScope;
    this.vectorIndex = vectorIndex;
  }

  /**
   * 当前用户的密级上限，转成与 {@code classification_rank} 同口径的整数（枚举 ordinal）。
   *
   * <p>★ 没有密级上限（未认证：{@link ScopeFilter#nobody()} 的 {@code maxClassification == null}） 返回
   * <strong>-1</strong>：所有密级的 rank 都 ≥ 0，于是谓词恒假 ⇒ <strong>一条都不会返回</strong>。 这就是 fail-closed
   * ——"取不到身份"必须表现为"什么都看不见"，而不是"什么都看得见"。
   *
   * <p>★ 这里<strong>不抛异常</strong>：未认证的检索请求本来就该得到空结果（{@code 07 §3.3} 硬规矩 1： 不给"存在性"信号）。抛 500
   * 反而把这个分支变成一次会被上报的异常，那是另一类噪声。
   */
  private int clearanceRank() {
    ClassificationLevel clearance = accessScope.currentScope().maxClassification();
    return clearance == null ? -1 : clearance.ordinal();
  }

  @Override
  public List<UUID> visibleKbIds(Collection<UUID> requested) {
    List<KnowledgeBase> candidates;
    if (requested == null || requested.isEmpty()) {
      // ★ 不传 ⇒ 全部可见：走仓储的**范围查询**（08 §4.4.3 纪律 1：范围拼进查询），
      //   而不是"查全表再筛" —— 后者会随着库变多而先慢后错。
      ScopeFilter scope = accessScope.currentScope();
      candidates = bases.search(scope, null, true, false, null, 0, VISIBLE_KB_LIMIT);
    } else {
      candidates = new ArrayList<>();
      for (UUID id : new LinkedHashSet<>(requested)) {
        bases.findById(id).ifPresent(candidates::add);
      }
    }
    List<UUID> visible = new ArrayList<>();
    for (KnowledgeBase base : candidates) {
      // ★ 可见性判定只在这里做（07 §3.3 硬规矩 1）：看不见的库既不进检索、也不给"存在性"信号
      if (base.enabled() && accessScope.canSee(base)) {
        visible.add(base.id());
      }
    }
    return visible;
  }

  @Override
  public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = List.copyOf(kbIds);
    Map<UUID, UUID> byKb = new LinkedHashMap<>();
    jdbc.query(
        "select id, embedding_model_id from kb_knowledge_base"
            + " where deleted_at is null and id in ("
            + placeholders(ids.size())
            + ")",
        (rs) -> {
          byKb.put(rs.getObject("id", UUID.class), rs.getObject("embedding_model_id", UUID.class));
        },
        ids.toArray());
    return byKb;
  }

  @Override
  public List<Scored> fullText(TextQuery query) {
    if (query == null
        || query.question() == null
        || query.question().isBlank()
        || query.kbIds() == null
        || query.kbIds().isEmpty()
        || query.limit() <= 0) {
      return List.of();
    }
    List<String> lexemes = lexemesOf(query.question());
    if (lexemes.isEmpty()) {
      // 问题里一个可用词元都没有（例如整句都是标点）⇒ 明确返回空，而不是拼一个必然报错的 tsquery
      return List.of();
    }
    String tsquery = toOrQuery(lexemes);

    List<Object> args = new ArrayList<>();
    args.add(tsquery);
    args.add(String.join("\n", lexemes));
    args.add(lexemes.size());
    args.add(clearanceRank()); // ★ 密级谓词的绑定值（与 SECURITY_PREDICATE 里的 ? 位置对应）
    args.addAll(query.kbIds());
    args.add(Math.max(1, query.limit()));

    String sql =
        "with q as (select to_tsquery('chinese_zh', ?) as tq,"
            + " string_to_array(cast(? as text), E'\\n') as lex)"
            + " select s.id as segment_id,"
            // coverage = 查询词里有几个在这条分段里出现了（分子数查询词、分母是查询词数）
            + " (select count(*) from unnest(q.lex) tok where tok = any"
            + " (tsvector_to_array(s.content_tsv)))::float   / ? as coverage"
            + JOINS
            + " cross join q"
            + " where s.content_tsv @@ q.tq"
            + " and"
            + SECURITY_PREDICATE
            + " and s.kb_id in ("
            + placeholders(query.kbIds().size())
            + ")"
            // ★ 同分时按 id 兜底排序：没有它，闸门会在同分样本上偶发红，而那种红会被误当成"检索坏了"
            + " order by ts_rank(s.content_tsv, q.tq) desc, s.id"
            + " limit ?";

    return jdbc.query(
        sql,
        (rs, i) -> new Scored(rs.getObject("segment_id", UUID.class), rs.getDouble("coverage")),
        args.toArray());
  }

  @Override
  public Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
    if (candidateIds == null || candidateIds.isEmpty() || kbIds == null || kbIds.isEmpty()) {
      return Set.of();
    }
    List<UUID> candidates = List.copyOf(candidateIds);
    List<Object> args = new ArrayList<>(candidates);
    args.add(clearanceRank()); // ★ 密级谓词的绑定值（与 SECURITY_PREDICATE 里的 ? 位置对应）
    args.addAll(kbIds);
    String sql =
        "select s.id"
            + JOINS
            + " where s.id in ("
            + placeholders(candidates.size())
            + ")"
            + " and"
            + SECURITY_PREDICATE
            + " and s.kb_id in ("
            + placeholders(kbIds.size())
            + ")";
    return new LinkedHashSet<>(jdbc.queryForList(sql, UUID.class, args.toArray()));
  }

  /**
   * 向量那一路 —— ★ 密级与三级过滤**都在这一条 SQL 里**（pre-filter，不是查完再筛）。
   *
   * <p>★ 这一路挂在这个端口上、而不在 {@code vectorindex}：密级住在 {@code kb_segment}、启用位散在三张表里 —— 要让 planner 把这些谓词
   * <strong>合并进向量扫描</strong>，它们就必须与 {@code embedding <=> ?} 出现在 <strong>同一条 SQL</strong> 里。而按
   * {@code vectorindex} 的模块声明（"向量表读写、HNSW 索引维护、批量写入与进度回写"）， 它<strong>不认识</strong>文档与知识库 ——
   * 所以"带业务谓词的相似度检索"属于拥有这些表的这一侧。 表名与表形仍然只由 {@link VectorIndexPort} 提供（不重复一份）。
   *
   * <p>★ 为什么表名要问 {@link VectorIndexPort} 而不是写死：维度是列类型（换模型换表，issue #5）。
   *
   * <p>★★ {@code set local hnsw.iterative_scan = strict_order}：HNSW 是**近似**索引， 默认扫到 {@code
   * ef_search} 条就停 —— 高选择性过滤（"绝大部分内容是机密的"）下会出现 **过滤后为空**，而库里明明有能看的内容。`strict_order` 让它在需要时继续多扫，
   * 且仍按真正的距离顺序返回（{@code 13 §5} 的第二道）。
   */
  @Override
  public List<Scored> vectorSearch(VectorQuery query) {
    if (query == null
        || query.limit() <= 0
        || query.vector() == null
        || query.vector().length == 0
        || query.kbIds() == null
        || query.kbIds().isEmpty()) {
      return List.of();
    }
    String table = vectorIndex.tableFor(query.dimension());
    if (!tableExists(table)) {
      // 这个维度还没有任何向量 ⇒ **空结果**，不是错误（也不建表：读路径不跑 DDL）
      return List.of();
    }
    // ★ 打开迭代扫描：高选择性过滤下自动多扫几轮（PG 16 + pgvector 0.8+）
    jdbc.execute("set local hnsw.iterative_scan = strict_order");

    List<Object> args = new ArrayList<>();
    args.add(toVectorLiteral(query.vector()));
    args.add(clearanceRank()); // ★ 密级谓词的绑定值（与 SECURITY_PREDICATE 里的 ? 位置对应）
    args.addAll(query.kbIds());
    args.add(Math.max(1, query.limit()));

    // ★ 向量表写在最前 + order by distance limit：这是"top-N 按距离"的规范形状，
    //   也是让 planner 走 HNSW 索引扫描（而不是全表 join 后排序）的前提。
    String sql =
        "select s.id as segment_id, (v.embedding <=> cast(? as vector)) as distance"
            + " from "
            + table
            + " v"
            + " join kb_segment s on s.id = v.id"
            + " join kb_document d on d.id = s.document_id"
            + " join kb_knowledge_base k on k.id = s.kb_id"
            + " where"
            + SECURITY_PREDICATE
            + " and s.kb_id in ("
            + placeholders(query.kbIds().size())
            + ")"
            + " order by distance"
            + " limit ?";

    return jdbc.query(
        sql,
        (rs, i) ->
            // 余弦距离 → 相似度：同一个向量是 0（→1），正交是 1（→0）。界面上那个阈值就是它。
            new Scored(rs.getObject("segment_id", UUID.class), 1.0 - rs.getDouble("distance")),
        args.toArray());
  }

  /** 表在不在（{@code to_regclass} 按 search_path 解析；不存在就别发查询，否则 PG 直接报错）。 */
  private boolean tableExists(String table) {
    Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
    return Boolean.TRUE.equals(exists);
  }

  /** pgvector 的文本字形：{@code [0.1,0.2,…]}（与写入侧同一种字形，同一个库同一个类型）。 */
  private static String toVectorLiteral(float[] vector) {
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

  @Override
  public List<Card> load(Collection<UUID> segmentIds) {
    if (segmentIds == null || segmentIds.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = List.copyOf(segmentIds);
    String sql =
        "select s.id as segment_id, s.title as title, s.content as content,"
            + " d.name as doc_name, k.name as kb_name"
            + JOINS
            + " where s.id in ("
            + placeholders(ids.size())
            + ")";
    // ★ 这里**不加**安全谓词：调用方刚刚用 visibleAmong 判过，这只是一次"取展示信息"。
    //   重复判一次不算错，但会让"看不见的分段"与"已被删的分段"给出同样的症状，反而难查。
    return jdbc.query(
        sql,
        (rs, i) ->
            new Card(
                rs.getObject("segment_id", UUID.class),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("doc_name"),
                rs.getString("kb_name")),
        ids.toArray());
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 交给库去分词，拿回真词元（★ 分词权威只有一处：{@code chinese_zh} 配置）。 */
  private List<String> lexemesOf(String question) {
    String joined =
        jdbc.queryForObject(
            "select array_to_string(tsvector_to_array(to_tsvector('chinese_zh', ?)), E'\\n')",
            String.class,
            question);
    if (joined == null || joined.isBlank()) {
      return List.of();
    }
    return joined
        .lines()
        .map(String::trim)
        .filter(lexeme -> !lexeme.isEmpty() && SAFE_LEXEME.matcher(lexeme).matches())
        .distinct()
        .toList();
  }

  /**
   * 词元 → OR 查询（{@code 'a' | 'b'}）。
   *
   * <p>★ 每个词元都加单引号：即使某个词元碰巧含操作符也不会被当语法执行（实测引号形式可用： {@code to_tsquery('chinese_zh','''住宿'' |
   * ''标准''')} ⇒ {@code '住宿' | '标准'}）。
   */
  private static String toOrQuery(List<String> lexemes) {
    return lexemes.stream()
        .map(lexeme -> "'" + lexeme.replace("'", "''") + "'")
        .collect(Collectors.joining(" | "));
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }
}
