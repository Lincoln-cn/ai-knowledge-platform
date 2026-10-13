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
 *   <li>{@link #load} —— 命中的展示信息（标题/正文/文档名/库名），组装引用用。
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
   */
  Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds);

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
    // ★ 默认实现是"明确拒绝"：这一条路带着**显式的密级上限**，而一个没写它的实现若静默返回
    //   全部候选，就等于"密级过滤没生效" —— 那是一条不会报错的越权。
    throw new UnsupportedOperationException("这个 SegmentSearchPort 实现不支持显式密级上限的列举");
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
   * @param kbIds 允许的库范围
   * @param limit 候选上限
   */
  record TextQuery(String question, List<UUID> kbIds, int limit) {}

  /**
   * 相似度检索的请求。
   *
   * @param dimension 目标向量维度（决定查哪张表）
   * @param vector 问题向量
   * @param kbIds 允许的库范围（已由 {@link #visibleKbIds} 过过一遍可见性）
   * @param limit 候选上限
   */
  record VectorQuery(int dimension, float[] vector, List<UUID> kbIds, int limit) {}

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
   * @param title 分段标题（{@code 12 §3.4} 推出来的那个）
   * @param content ★ 是 {@code content} 而不是 {@code search_text} —— 引用展示必须给干净原文 （{@code 12 §4.2} 纪律
   *     1）
   */
  record Card(UUID segmentId, String title, String content, String docName, String kbName) {}
}
