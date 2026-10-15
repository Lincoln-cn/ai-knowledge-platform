package cn.lincoln.aiplatform.platform.retrieval;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 分段检索端口（跨模块 SPI）—— 由 {@code knowledge} 实现（它拥有 {@code kb_segment} 与 {@code content_tsv}），{@code
 * retrieval} 消费。
 *
 * <h3>为什么是这四个方法</h3>
 *
 * <p>检索要的三件事都在"分段属于谁、能不能被看见"这一侧（{@code 07 §3.3}：可见性判定**只在一处**）：
 *
 * <ol>
 *   <li>{@link #visibleKbIds} —— 先问"我能检索哪些库"，后面所有查询都收敛到这个 id 集合；
 *   <li>{@link #fullText} —— 全文那一路（{@code content_tsv} + 词命中）；
 *   <li>{@link #vectorSearch} —— 向量那一路（★ 密级与三级过滤与 {@code <=>} 同一条 SQL）；
 *   <li>{@link #visibleAmong} —— ★ <strong>两路同谓词</strong>：向量路拿回来的候选也要过同一套判定 （{@code 13}
 *       五道闸门之二：只给一路加条件就是"看着能过滤"的静默漏洞）；
 *   <li>{@link #load} —— 命中的展示信息（标题/正文/文档名/库名 + 文档 id/库 id），组装引用用。
 * </ol>
 *
 * <p>★ <strong>不在这个端口里的东西</strong>：排序融合（那是 {@code retrieval} 的策略）、 以及"表叫什么、表形是什么"（那是 {@code
 * platform.vector.VectorIndexPort} 的事，这里只用它拿表名）。 端口提供"带业务谓词的事实"，策略留在检索模块 —— 这条分工就是 {@code 01 §3.3}
 * 说的 "向量的存取是基础设施能力、检索的策略是业务能力"。
 */
public interface SegmentSearchPort {

  /**
   * 当前用户可见的知识库 id（★ 可见性判定只在这里做，别处不许再实现一份）。
   *
   * @param requested 调用方想检索的库；<strong>空</strong> = 全部可见的
   * @return 过滤后的 id（保序）；调用方拿到的每一个都确定可见
   */
  List<UUID> visibleKbIds(Collection<UUID> requested);

  /**
   * 解析本次检索的<strong>域</strong>（{@code 13 §11.3} 三支的绑定值）—— ★ 第 17 周新增，域的解析**只有这一处**。
   *
   * <h3>为什么"解析"要单独一个方法（而不是让调用方自己拼 {@link RetrievalScope}）</h3>
   *
   * <p>三支的绑定值来自**三个不同的地方**，而其中两个不许由调用方猜：
   *
   * <ol>
   *   <li>共用库：要过可见性判定（{@link #visibleKbIds}）—— 它与"这个库存在吗"是两件事；
   *   <li>个人库：是"{@code kind = 'PERSONAL' and owner_id = 我} 的那一行"（{@code 13 §11.2}）——
   *       调用方手里只有一个开关（"加不加我的资料"），**没有**那个 id；
   *   <li>★ {@code ownerId}（会话域那一支的传输者）：只能从**当前范围**取（{@code AccessScopePort}）。
   *       让调用方传这个参数等于把"用谁的身份检索会话附件"变成一个可以填错的入参。
   * </ol>
   *
   * <p>★ 与 {@code 07 §十一} 的"解析只能发生在构造范围的时候"是同一条纪律：范围/域在**一处**构造好， 下游只消费。
   *
   * <p>★★ <strong>公开面（{@code asSubject}）必须传 {@code includePersonal = false}</strong>： {@code 13
   * §11.2} ③ 写明公开面的域**只有**共用库 + 该会话的会话临时 ⇒ 把个人库并进去 = 访客按 "作者的文档"检索（{@code #31} 点名的那个静默越权）。
   *
   * @param requested 调用方想检索的库；<strong>空</strong> = 全部可见的
   * @param conversationId 本次会话 id；命中测试 / 管理面传 {@code null}
   * @param includePersonal 是否把个人库计入本次检索域（「加上我的资料」开关，缺省 <strong>false</strong>）
   */
  default RetrievalScope retrievalScope(
      Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
    // ★ 默认实现是"明确拒绝"而不是"只按共用库解析"：后者会静默丢掉会话域与个人域 ——
    //   症状是"会话附件搜不到"（不报错，只是少看见），而那正是 #31 要防的那一类。
    throw new UnsupportedOperationException("这个 SegmentSearchPort 实现不支持域的解析（retrievalScope）");
  }

  /**
   * 每个库用的向量模型 id（库 id → 模型 id）。
   *
   * <p>★ 用途有两件：把问题向量化、以及**决定查哪张维度表**。★ 按库给而不是给一个集合， 是因为「不传 kbIds =
   * 全部可见」时<strong>极可能跨多个模型</strong>（开发库里实测就有 5 个）——
   * 那时正确的做法是<strong>按模型分组、各查各的表、再融合</strong>，而不是拒绝、也不是随便挑一个。
   *
   * <p>返回空表示这些库都不存在或都不可见。
   */
  Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds);

  /**
   * 全文检索那一路。
   *
   * <p>★ 只返回<strong>命中的</strong>（{@code content_tsv @@ 查询}）并按词命中质量降序 ——
   * 它<strong>不</strong>做阈值过滤：阈值的量纲是"相似度"（{@code 10 §5.4} 说它要随向量模型校准）， 拿它去卡无界的 {@code ts_rank}
   * 会让全文模式永远空。每条路有自己的准入判据，融合交给 RRF。
   */
  List<Scored> fullText(TextQuery query);

  /**
   * ★ 两路同谓词：把候选 id 过一遍<strong>同一条</strong>可见性判定 （库启用 + 文档启用未删 + 分段启用）。
   *
   * <p>传进来的 kbIds 是"允许的范围"（{@link #visibleKbIds} 的结果）；不在范围内的候选直接丢弃 —— 双保险，因为向量路的元数据过滤是"优化"，不是判据。
   *
   * <p>★ 这个重载（只带共用库域）保留是为了既有调用点不必全改；它的语义与 {@code visibleAmong(candidateIds,
   * RetrievalScope.shared(kbIds))} 逐字相同。
   */
  default Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
    return visibleAmong(candidateIds, RetrievalScope.shared(kbIds));
  }

  /**
   * ★★ 域感知的复核（{@code 13 §11.3} 的同一个谓词，只换一个域）。
   *
   * <p>它的意义与 {@link #visibleAmong(Collection, Collection)} 完全一样，区别只是"允许的范围"从 "一组库 id"升级成"{@link
   * RetrievalScope 三个域}"。★ 会话临时的分段 {@code kb_id} 是空的， 只按库 id
   * 复核会把它们<strong>全部筛掉</strong>（而那是静默的：检索明明命中了，结果却少几条）。
   */
  default Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
    // ★ 与 retrievalScope 同一条理由：默认实现明确拒绝，而不是退化成"只按共用库判" ——
    //   退化之后会话域/个人域的分段会在这一步被静默丢掉。
    throw new UnsupportedOperationException("这个 SegmentSearchPort 实现不支持域感知的候选复核");
  }

  /**
   * 以<strong>显式的密级上限</strong>列出可见的分段（第 16 周 · 0.4.4 新增）。
   *
   * <h3>它为什么必须存在</h3>
   *
   * <p>免登录面（公开凭证）要"用链接自己的<strong>冻结密级</strong>重新过滤一遍" （{@code 13 §5.2} 第 ② 级）。而另外两个入口（{@link
   * #fullText} / {@link #vectorSearch}）的密级上限 都取自<strong>当前范围</strong> —— 那是"谁在问"决定的正确答案，但公开面需要的是一条
   * <strong>被链接冻结</strong>的值。没有这个方法，调用方只能自己拼一条 SQL —— 那就是同一段安全谓词的 <strong>第二处实现</strong>，而 {@code
   * 13 §三} 闸门②要防的正是它。
   *
   * <p>★ 实现必须用与另外两条路<strong>同一份</strong>安全谓词（可见性 + 密级），只把密级上限换成入参 —— 本方法的全部意义就是"换一个上限、谓词一个字不改"。
   *
   * @param kbIds 允许的库范围（已由 {@link #visibleKbIds} 过过一遍可见性）
   * @param ceiling ★ 密级上限。它与资源的 {@code classification_rank} 比的是"不高于它"； 不传（{@code null}）⇒
   *     <strong>什么都不返回</strong>（fail-closed，与"取不到身份"同一条口径）
   * @param limit 候选上限
   */
  default List<Scored> visibleSegments(
      Collection<UUID> kbIds, ClassificationLevel ceiling, int limit) {
    // ★ 只带共用库域的重载（既有调用点）；语义 = RetrievalScope.shared(kbIds)
    return visibleSegments(RetrievalScope.shared(kbIds), ceiling, limit);
  }

  /**
   * 域感知的版本（第 17 周）：{@code 13 §11.2} ③ 说公开面的域是"共用库 + **该会话的会话临时**" —— 只带库 id
   * 的那个重载表达不了会话域，而"忘了带会话域"的症状正是<strong>附件的引用一条都出不来</strong> （{@code #31} 判据 2 的一半）。
   *
   * @param scope ★ 检索域。公开面<b>必须</b>用 {@code includePersonal = false} 解析出来的那个
   */
  default List<Scored> visibleSegments(
      RetrievalScope scope, ClassificationLevel ceiling, int limit) {
    // ★ 默认实现是"明确拒绝"：这一条路带着**显式的密级上限**，而一个没写它的实现若静默返回
    //   全部候选，就等于"密级过滤没生效" —— 那是一条不会报错的越权。
    throw new UnsupportedOperationException("这个 SegmentSearchPort 实现不支持域感知的显式密级列举");
  }

  /** 命中分段的展示信息（引用组装用）。顺序不保证，调用方按 segmentId 索引。 */
  List<Card> load(Collection<UUID> segmentIds);

  /**
   * 向量那一路 —— ★ <strong>密级与三级过滤都在这一条 SQL 里</strong>（{@code 13 §三} 硬要求： pre-filter，不是"查完再筛"）。
   *
   * <p>★ 为什么向量检索挂在这个端口上、而不在 {@code vectorindex}： 密级住在 {@code kb_document}、启用位散在三张表里 —— 要让 planner
   * 把这些谓词 **合并进向量扫描**，它们就必须与 {@code embedding <=> ?} 出现在**同一条 SQL** 里。 而按 {@code vectorindex}
   * 的模块声明（"向量表读写、HNSW 索引维护、批量写入与进度回写"）， 它<strong>不认识</strong>文档与知识库 —— 所以"带业务谓词的相似度检索"属于拥有这些表的这一侧。
   * 表名与表形仍然只由 {@code platform.vector.VectorIndexPort} 提供（不重复一份）。
   *
   * <p>★ 为什么必须在这一周做完（{@code 13 §五} 的原话）：数据范围与密级的过滤 <strong>与检索查询是同一处</strong> ——
   * 晚做就要改所有查询，而改的那一次最可能漏掉其中一条路。
   */
  List<Scored> vectorSearch(VectorQuery query);

  /**
   * 全文检索的请求。
   *
   * @param question 用户问题原文（分词的权威是库里的 {@code chinese_zh} 配置，**不在 Java 侧再分一次**）
   * @param scope ★ 检索域（{@code 13 §11.3} 的三支）。★ 它<b>同时</b>是"允许的库范围"与"个人库/会话域开不开" ——
   *     三者必须一起表达，分开传就有"漏一支"的空间
   * @param limit 候选上限
   */
  record TextQuery(String question, RetrievalScope scope, int limit) {

    /**
     * 只带共用库域的重载 —— 给"命中的库范围就是一个 id 列表"的调用点（命中测试 / 应用绑定 / 公开面预览 / 大量既有测试夹具）。语义与 {@code scope =
     * RetrievalScope.shared(kbIds)} 逐字相同。
     */
    public TextQuery(String question, List<UUID> kbIds, int limit) {
      this(question, RetrievalScope.shared(kbIds), limit);
    }

    /** 共用库那一支的库 id（既有读法的兼容入口，等价于 {@code scope.sharedKbIds()}）。 */
    public List<UUID> kbIds() {
      return scope.sharedKbIds();
    }
  }

  /**
   * 相似度检索的请求。
   *
   * @param dimension 目标向量维度（决定查哪张表）
   * @param vector 问题向量
   * @param scope ★ 检索域（已由 {@link #retrievalScope} 解析过）。★ 向量路按模型分组时用 {@link
   *     RetrievalScope#withinShared} 收窄共用库那一支，其余两域照搬
   * @param limit 候选上限
   */
  record VectorQuery(int dimension, float[] vector, RetrievalScope scope, int limit) {

    /** 只带共用库域的重载（语义同上）。 */
    public VectorQuery(int dimension, float[] vector, List<UUID> kbIds, int limit) {
      this(dimension, vector, RetrievalScope.shared(kbIds), limit);
    }

    /** 共用库那一支的库 id（既有读法的兼容入口）。 */
    public List<UUID> kbIds() {
      return scope.sharedKbIds();
    }
  }

  /**
   * 一路检索出来的候选。
   *
   * @param score 该路自己的分数量纲：向量路是余弦相似度、全文路是"查询词命中覆盖率"。 ★ 两者都落在 0–1，是为了界面上那个阈值滑块在三种模式下都说得通
   *     （但**过滤与否**由各路自己决定，融合只看 rank）。
   */
  record Scored(UUID segmentId, double score) {}

  /**
   * 命中分段的展示信息。
   *
   * <h3>★★ 后两个分量是"引用卡片按来源类型切换标签"与深链的载体（第 17 周补）</h3>
   *
   * <p>它们此前**不在这个类型里**，于是装配点只能给每一条引用都传 {@code null}（{@code ChatService#retrieve}）—— 后果有两条，而且都不报错：
   *
   * <ol>
   *   <li>{@code kbId} 缺失是前端"知识库引用 vs 会话域引用"那条<strong>结构性</strong>判据的判据本身 （缺失 = 会话域）⇒
   *       生产链路上<strong>连知识库引用也没有它</strong>，界面上每条库引用都被读成「来源：」；
   *   <li>{@code documentId} 缺失让引用深链拼不出 query ⇒ 点了没反应（{@code #91}）。
   * </ol>
   *
   * <p>★ 所以这两个分量必须来自**真实的行**（{@code JdbcSegmentSearchQuery} 的 select），不许在装配点补默认值： "给所有引用都补一个
   * kbId"会把上面那条判据变成恒真，而它正是前端的判据。★ 这里写 {@code JdbcSegmentSearchQuery} 而不是链接：本端口在 {@code
   * platform}，那个类在 {@code knowledge} —— 跨模块的 javadoc 链接解析不了。
   *
   * @param title 分段标题（{@code 12 §3.4} 推出来的那个）。★ 库里这一列**可空**（{@code V15}）⇒ 读取侧用 {@code docName}
   *     兜底（{@code ChatService#retrieve}）
   * @param content ★ 是 {@code content} 而不是 {@code search_text} —— 引用展示必须给干净原文 （{@code 12 §4.2} 纪律
   *     1）
   * @param documentId 所属文档 id（{@code kb_document.id}）—— 引用深链的载体。★ 两个域都有值：会话临时的分段虽然没有库行，
   *     但**有文档行**（{@code 13 §11.2}）
   * @param kbId 所属知识库 id（{@code kb_knowledge_base.id}）。★★ <strong>会话临时的分段这里是 {@code null}</strong>
   *     —— 那一行的 {@code kb_id} 为空（{@code 13 §11.2}），左连接下拿不到库行。这正是前端那条结构性判据（缺失 =
   *     会话域）成立的地方，所以<strong>绝不许</strong>兜底成空串、占位串或"最近的一个库"。
   */
  record Card(
      UUID segmentId,
      String title,
      String content,
      String docName,
      String kbName,
      UUID documentId,
      UUID kbId) {}
}
