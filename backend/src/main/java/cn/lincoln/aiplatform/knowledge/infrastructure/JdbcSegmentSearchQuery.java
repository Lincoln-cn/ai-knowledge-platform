package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
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
 * <h3>★★ 安全谓词只有一份（第 11 周 · 0.3.3；第 17 周扩成三域）</h3>
 *
 * <p>可见性与密级合成 {@link #SECURITY_PREDICATE}，<strong>四条查询（全文 / 向量 / 候选复判 / 显式密级列举） 用的都是它</strong>。
 * 这不是代码复用上的讲究，而是 {@code 13 §三} 闸门②的硬要求：<em>"只给一路加过滤条件"</em> 是本项目最贵的病，
 * 而合成的常量让"漏一路"从"忘了补一行"变成"得先把这两行拆开"。
 *
 * <h3>★★ 三域也长在同一个谓词里（第 17 周 · {@code #31} / {@code 13 §11.3}）</h3>
 *
 * <p>{@code 13 §11.3} 的原话是"一个谓词，不是三个分支"，所以域谓词**不是**三个 {@code if}，而是 {@link #SECURITY_PREDICATE}
 * 里的一段 SQL：
 *
 * <pre>
 *   域(s) = ( s.scope_kind = 'KB'           and s.scope_id in (:sharedKbIds) )   ← 共用库（可见性已判过）
 *        or ( s.scope_kind = 'KB'           and s.scope_id = :personalKbId )     ← 我的个人库（隐式档）
 *        or ( s.scope_kind = 'CONVERSATION' and s.scope_id = :conversationId
 *                                           and s.owner_id = :ownerId )          ← 会话临时（本人 + 本会话）
 * </pre>
 *
 * <p>★ 三支的绑定值来自 {@link RetrievalScope}，而它的解析只有一处（{@link #retrievalScope}）。 ★ 为什么过滤读的是 {@code
 * s.}（分段）那一侧的**冗余列**而不是 {@code d.}（文档）那一侧： {@code 13 §6.4} 的三条 —— 见 {@code kb_segment.scope_kind}
 * 的列注释。
 *
 * <h3>★★ 一条内连接陷阱（本批最容易长出来的静默洞）</h3>
 *
 * <p>原先是 {@code join kb_knowledge_base k on k.id = s.kb_id}。会话临时的分段<strong>没有库行</strong> （{@code 13
 * §11.2}）⇒ 这条**内连接会把它们全部丢掉**，而症状只是"附件搜不到"（不报错）。 ⇒ 现在改成 {@code left join}，并把库的启用位判据收窄到"库域才判"（见
 * {@link #VISIBLE_PREDICATE}）。
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
   * 启用位判定 —— 三级启用位（库 / 文档 / 分段）各自"启用且未软删"。
   *
   * <p>★ 它单独存在只是为了可读；<strong>任何查询都不许只用它</strong> —— 必须用 {@link #SECURITY_PREDICATE}。 只带它、不带密级条件，就是
   * {@code 13 §三} 点名的那个静默漏洞。
   *
   * <p>★★ <strong>库那一级只对库域判</strong>：会话临时的分段没有 {@code kb_knowledge_base} 行 （{@code 13 §11.2}），左连接下
   * {@code k.enabled} 是 NULL —— 若写成 {@code k.enabled and …}， 会话附件会在 WHERE 里被静默滤掉（{@code FALSE or
   * NULL} 那条三值逻辑正是这里要盯的）。
   */
  private static final String VISIBLE_PREDICATE =
      " s.enabled and d.enabled and d.deleted_at is null"
          + " and (s.scope_kind = 'CONVERSATION' or (k.enabled and k.deleted_at is null))";

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
   * ★★ <strong>安全谓词 = 可见性 + 三域 + 密级，四条查询用的都是这一份</strong> （{@code 13 §三} 闸门②：pre-filter，两路同谓词；{@code
   * 13 §11.3}：一个谓词，不是三个分支）。
   *
   * <p>★ 它是一个**常量**而不是"四条 SQL 各拼一段"：任何第二条手写 {@code classification_rank} / {@code scope_kind} 的 SQL
   * 都意味着"两处写法迟早会漂"，而那正是本项目最贵的病。
   *
   * <p>★ 里面唯一的 {@code %s} 是共用库那一支的 {@code in (?,?,…)} —— 它随调用变化的只有**占位符个数**， 所以整条谓词仍是"一个常量 +
   * 一次替换"，见 {@link #securityPredicate(int)}。
   *
   * <p>★ 它**以条件开头**（不带 {@code where}、也不带 {@code and}）：四条路的形态不同 —— 两条把它接在既有条件之后（{@code …
   * and}），两条把它直接接在 {@code where} 之后。 ★ 这一条必须写下来：本批第一次就是把 {@code " and"} 焊在常量头上， 于是 {@code " where"
   * + 常量} 拼出了 {@code where and …}（真栈直接 SQL 语法错）—— 它是**报错**的那一类，比静默错好，但白跑了一次真栈。
   */
  private static final String SECURITY_PREDICATE =
      VISIBLE_PREDICATE
          + " and ("
          + "%s"
          + " or (s.scope_kind = 'KB' and s.scope_id = cast(? as uuid))"
          + " or (s.scope_kind = 'CONVERSATION' and s.scope_id = cast(? as uuid)"
          + " and s.owner_id = cast(? as uuid)))"
          + CLASSIFICATION_PREDICATE;

  private static final String JOINS =
      " from kb_segment s"
          + " join kb_document d on d.id = s.document_id"
          // ★ 左连接（见类注释的内连接陷阱）：会话临时的分段没有库行
          + " left join kb_knowledge_base k on k.id = s.kb_id";

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
   * 把 {@link #SECURITY_PREDICATE} 里那一个 {@code %s} 换成共用库那一支。
   *
   * <p>★ 两个刻意的写法：
   *
   * <ol>
   *   <li><strong>共用库为空时那一支写成 {@code false}</strong>：{@code scope_id in ()} 在 SQL 里是
   *       <strong>语法错误</strong> —— "没有共用库域"必须是"这一支恒假"，而不是一条会炸的 SQL （会话里只传了附件、没有绑定任何库，是一种合法情形）；
   *   <li><strong>{@code cast(? as uuid)}</strong>：个人库/会话域"没开"时传的是 {@code null}， 而不带类型的 {@code ?}
   *       会让 PG 报 {@code could not determine data type of parameter}； 加上 cast 之后 {@code null}
   *       在**三值逻辑**下让那一支恒假 —— 正是"没开"该有的语义。
   * </ol>
   *
   * @param sharedKbCount 共用库那一支要绑几个 id；{@code 0} ⇒ 那一支恒假
   */
  private static String securityPredicate(int sharedKbCount) {
    String sharedKb =
        sharedKbCount == 0
            ? "false"
            : "(s.scope_kind = 'KB' and s.scope_id in (" + placeholders(sharedKbCount) + "))";
    return SECURITY_PREDICATE.formatted(sharedKb);
  }

  /**
   * 谓词的绑定值 —— ★ <strong>顺序必须与 {@link #securityPredicate(int)} 里的 {@code ?} 逐个对齐</strong> （共用库 id 列表
   * → 个人库 → 会话 → 传输者 → 密级上限）。
   *
   * <p>★ 与 {@link #securityPredicate(int)} 是**成对**的两件事，所以它们挨着放：分开之后 "改了条件没改参数"会变成一次 SQL 参数错位 ——
   * 那种红会红在"看得见的内容不见了" （而不是"越权出现了"），最难查的一类。
   */
  private static void bindSecurityPredicate(
      List<Object> args, RetrievalScope scope, int clearanceRank) {
    args.addAll(scope.sharedKbIds());
    args.add(scope.personalKbId());
    args.add(scope.conversationId());
    args.add(scope.ownerId());
    args.add(clearanceRank);
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

  /**
   * ★★ 解析本次检索的域（{@code 13 §11.3} 三支的绑定值）—— <strong>域的解析只有这一处</strong>。
   *
   * <p>三件事在这里一次做完，调用方不需要（也不许）自己拼：
   *
   * <ol>
   *   <li>共用库那一支：过 {@link #visibleKbIds}（可见性判定唯一的那一处）；
   *   <li>个人库那一支：{@code kind = 'PERSONAL' and owner_id = 我} 的那一行（{@code 13 §11.2}）—— ★ 惰性创建还没落（随第
   *       17 周），所以"还没有个人库"就是 {@code null} = 这一档不开；
   *   <li>传输者：从<strong>当前范围</strong>取（{@link ScopeFilter#me()}），不由调用方传 ——
   *       让调用方传这个参数等于把"用谁的身份检索会话附件"变成一个可以填错的入参。
   * </ol>
   *
   * <p>★ 它与 {@code 07 §十一} 的"解析只能发生在构造范围的时候"是同一条纪律。
   */
  @Override
  public RetrievalScope retrievalScope(
      Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
    List<UUID> sharedKbIds = visibleKbIds(requested);
    UUID me = accessScope.currentScope().me();
    UUID personalKbId = includePersonal ? personalKbId(me) : null;
    return new RetrievalScope(sharedKbIds, personalKbId, conversationId, me);
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
        || query.scope() == null
        || query.scope().isEmpty()
        || query.limit() <= 0) {
      return List.of();
    }
    List<String> lexemes = lexemesOf(query.question());
    if (lexemes.isEmpty()) {
      // 问题里一个可用词元都没有（例如整句都是标点）⇒ 明确返回空，而不是拼一个必然报错的 tsquery
      return List.of();
    }
    String tsquery = toOrQuery(lexemes);
    RetrievalScope scope = query.scope();

    List<Object> args = new ArrayList<>();
    args.add(tsquery);
    args.add(String.join("\n", lexemes));
    args.add(lexemes.size());
    // ★ 绑定值的位置约定：其余参数 → 三域 → 密级 → limit
    bindSecurityPredicate(args, scope, clearanceRank());
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
            + securityPredicate(scope.sharedKbIds().size())
            // ★ 同分时按 id 兜底排序：没有它，闸门会在同分样本上偶发红，而那种红会被误当成"检索坏了"
            + " order by ts_rank(s.content_tsv, q.tq) desc, s.id"
            + " limit ?";

    return jdbc.query(
        sql,
        (rs, i) -> new Scored(rs.getObject("segment_id", UUID.class), rs.getDouble("coverage")),
        args.toArray());
  }

  /**
   * 以<strong>显式的密级上限</strong>列出可见的分段（第 16 周 · 0.4.4；第 17 周加域感知重载）。
   *
   * <p>★★ 它的全部意义是"<strong>换一个上限、谓词一个字不改</strong>"：用的仍然是 {@link #SECURITY_PREDICATE}（可见性 + 三域 +
   * 密级），只把绑定值换成入参。
   *
   * <p>★ 为什么不能让调用方自己拼一条：那就是同一段安全谓词的<strong>第二处实现</strong>， 而"只给一路加过滤条件"正是 {@code 13 §三}
   * 闸门②点名的、本项目最贵的病。
   *
   * <p>★ {@code ceiling == null} ⇒ 直接返回空（fail-closed）：与 {@link #clearanceRank()} 在"取不到身份"时返回 {@code
   * -1} 是同一条口径 —— 没有上限就是什么都看不见， 不是什么都看得见。
   */
  @Override
  public List<Scored> visibleSegments(
      RetrievalScope scope, ClassificationLevel ceiling, int limit) {
    if (scope == null || scope.isEmpty() || ceiling == null || limit <= 0) {
      return List.of();
    }
    List<Object> args = new ArrayList<>();
    // ★ 绑定值的位置约定与另外三条路逐字一致：三域 → 密级 → limit
    bindSecurityPredicate(args, scope, ceiling.ordinal());
    args.add(limit);
    // ★ 排序必须确定：没有它，同一个问题两次访问可以返回不同的子集，而"可见集合收窄"那条判据
    //   会在 limit 截断处偶发红 —— 那种红会被误当成"密级过滤坏了"
    String sql =
        "select s.id"
            + JOINS
            + " where"
            + securityPredicate(scope.sharedKbIds().size())
            + " order by s.id"
            + " limit ?";
    return jdbc.query(
        sql, (rs, i) -> new Scored(rs.getObject("id", UUID.class), 1.0), args.toArray());
  }

  @Override
  public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
    if (candidateIds == null || candidateIds.isEmpty() || scope == null || scope.isEmpty()) {
      return Set.of();
    }
    List<UUID> candidates = List.copyOf(candidateIds);
    List<Object> args = new ArrayList<>(candidates);
    // ★ 绑定值的位置约定与另外三条路逐字一致：候选 id → 三域 → 密级
    bindSecurityPredicate(args, scope, clearanceRank());
    String sql =
        "select s.id"
            + JOINS
            + " where s.id in ("
            + placeholders(candidates.size())
            + ")"
            + " and"
            + securityPredicate(scope.sharedKbIds().size());
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
        || query.scope() == null
        || query.scope().isEmpty()) {
      return List.of();
    }
    String table = vectorIndex.tableFor(query.dimension());
    if (!tableExists(table)) {
      // 这个维度还没有任何向量 ⇒ **空结果**，不是错误（也不建表：读路径不跑 DDL）
      return List.of();
    }
    // ★ 打开迭代扫描：高选择性过滤下自动多扫几轮（PG 16 + pgvector 0.8+）
    jdbc.execute("set local hnsw.iterative_scan = strict_order");
    RetrievalScope scope = query.scope();

    List<Object> args = new ArrayList<>();
    args.add(toVectorLiteral(query.vector()));
    // ★ 绑定值的位置约定与另外三条路逐字一致：向量字面量 → 三域 → 密级 → (可选 model_id) → limit
    bindSecurityPredicate(args, scope, clearanceRank());
    if (query.embeddingModelId() != null) {
      args.add(query.embeddingModelId().toString());
    }
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
            + " left join kb_knowledge_base k on k.id = s.kb_id"
            + " where"
            + securityPredicate(scope.sharedKbIds().size())
            + (query.embeddingModelId() != null
                ? " and (v.metadata::jsonb ->> 'model_id') = ?"
                : "")
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
    // ★★ 深链与"来源类型"的那两列（第 17 周补）：{@code s.document_id} 与 {@code k.id}。
    //   查询语义/谓词一个字没改 —— 要的两列本来就在已 join 的两张表上（{@code kb_segment.document_id}
    //   是它的列、{@code kb_knowledge_base} 早就左连接着），所以这是"把已有的事实读出来"，
    //   不是"新增一条产品决定"。★ 会话域那一行天然是 NULL（左连接下没有库行）—— 那正是前端的判据。
    String sql =
        "select s.id as segment_id, s.title as title, s.content as content,"
            + " s.document_id as document_id, d.name as doc_name,"
            + " k.id as kb_id, k.name as kb_name,"
            // ★ 密级（#27）：库里那一列是 V22 的非空列（强制继承所属文档），所以这不是"新增一条产品决定"，
            //   而是"把已有的事实读出来" —— 与上面那两列（document_id / kb_id）完全同一个形状。
            //   会话密级的装配点靠它算"本轮引用集合的最高密级"。
            + " s.classification as classification"
            + JOINS
            + " where s.id in ("
            + placeholders(ids.size())
            + ")";
    // ★ 这里**不加**安全谓词：调用方刚刚用 visibleAmong 判过，这只是一次"取展示信息"。
    //   重复判一次不算错，但会让"看不见的分段"与"已被删的分段"给出同样的症状，反而难查。
    // ★★ 会话域的行**没有库名**（左连接下 k 为 NULL ⇒ kbName 为 NULL），而契约的 SourceRef 要求
    //   kbName 非空。★ 这一格**不许在这里补**：Card 的两个分量必须都来自**真实的列**（见 Card 的
    //   javadoc），在端口出口补一个假值会让"这一行到底有没有库"当场消失，下游再也分不出
    //   "会话域的引用"与"名字叫那个固定串的知识库"。产品口径（2026-10-15 已裁定）由装配点按
    //   `kb_id` 是否为空来落 —— 常量只有一处（platform.retrieval.SourceLabels），消费点是
    //   `ChatService#retrieve` / `#kbNameOf`（本端口在 knowledge，那是 conversation 那一侧的类，
    //   跨模块的 javadoc 链接解析不了，所以这里只写名字）。
    //   ★ 而同一行的 {@code kb_id} 是 NULL 这件事**不是**展示口径：它是前端结构性判据的那一半（见 Card）。
    return jdbc.query(
        sql,
        (rs, i) ->
            new Card(
                rs.getObject("segment_id", UUID.class),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("doc_name"),
                rs.getString("kb_name"),
                rs.getObject("document_id", UUID.class),
                rs.getObject("kb_id", UUID.class),
                // ★ 列是 varchar(16) 上的机器码（14 册 §2.1）⇒ 显式取值再转枚举：
                //   靠驱动自动映射会在"取值不在枚举里"时给出一个含糊的错，那样反而难查
                ClassificationLevel.valueOf(rs.getString("classification"))),
        ids.toArray());
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 我的个人库 id（{@code 13 §11.2}：{@code kind = 'PERSONAL' and owner_id = 我} 的那一行）。
   *
   * <p>★ 惰性创建（首次上传时建）随第 17 周，所以"还没有"是正常状态 ⇒ 返回 {@code null} = 个人库那一档不开（与 {@code 13 §11.4}
   * 的默认一致：个人库**默认不参与**）。
   *
   * <p>★ 每人至多一行由库层的 {@code uq_kb_personal_owner} 保证；这里不去"猜哪一行"——
   * 多行时那是一条**已经坏了**的数据，而唯一索引让它在写入那一刻就红过。
   */
  private UUID personalKbId(UUID me) {
    if (me == null) {
      return null;
    }
    List<UUID> found =
        jdbc.queryForList(
            "select id from kb_knowledge_base"
                + " where kind = 'PERSONAL' and owner_id = ? and deleted_at is null",
            UUID.class,
            me);
    return found.isEmpty() ? null : found.getFirst();
  }

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
