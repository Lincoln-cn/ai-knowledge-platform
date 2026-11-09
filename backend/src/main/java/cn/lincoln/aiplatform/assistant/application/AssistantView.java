package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.platform.access.Grant;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 应用的对外形状（契约 {@code frontend/src/api/assistants.ts} 的 {@code Assistant}）。
 *
 * <p>★ {@code @JsonInclude(NON_NULL)}：契约里 {@code modelId} / {@code modelName} / {@code
 * satisfaction} 等是 <strong>可选</strong>字段，而"可选"在 JSON 里应当表现为<strong>字段不出现</strong>，不是出现一个 {@code
 * null} —— 前端 {@code v-model} 与"未配置"的判断都按"有没有这个字段"写。与 {@code ModelView} / {@code UserView} 同一处理。
 *
 * <h3>★ 本批刻意<strong>不下发</strong>的字段</h3>
 *
 * <p><b>{@code workflow}</b>（编排应用摘要 {@code WorkflowBrief}）：它要读 {@code workflow} 模块的流程定义，
 * 而那个模块目前只有一个 {@code package-info}（编排执行引擎属 V1.0 范围）。<strong>下发 {@code {nodeCount: 0}} 是编造</strong>
 * —— 它会让列表卡片显示"0 个节点"，而真相是"没有画布"。字段不出现才是诚实的。
 *
 * <p>★ {@code grants}（共享授权）<strong>已下发</strong>：共享写子项落地后，共享对话框要按它回填已选主体， 而 {@code
 * ScopeFilter.canSee} 也读同一份数据。空数组 = 真的没共享给任何人（不是"还没接"）。
 *
 * <p>★ {@code favorited} 是<strong>当前请求者</strong>的收藏状态，不是应用自身的属性： 同一份配置对 A 是 {@code true}、对 B 是
 * {@code false}。它由服务层用 {@code AssistantFavoriteRepository} 按 {@code (assistantId, me)} 查出来再传进来 ——
 * 视图不查库，这是本项目的一贯分工。
 *
 * @param modelName 模型显示名；查不到时为 null ⇒ 字段不出现（列表卡片显示不出"哪个模型"，与"没绑模型"是两件事，由 {@code modelId} 区分）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantView(
    UUID id,
    String name,
    String icon,
    String description,
    String type,
    boolean builtin,
    boolean published,
    boolean favorited,
    UUID modelId,
    String modelName,
    List<UUID> kbIds,
    String rolePrompt,
    List<UUID> promptIds,
    RetrievalView retrieval,
    boolean multiTurn,
    boolean thinkingEnabled,
    String prologue,
    List<String> quickQuestions,
    long callCount,
    long tokenCount,
    BigDecimal satisfaction,
    UUID ownerId,
    String visibility,
    List<Grant> grants,
    List<String> tags,
    Instant createdAt) {

  /**
   * 检索参数（契约 {@code RetrievalParams}）。
   *
   * <p>★ 三项查询理解参数这里<strong>总是</strong>下发（不省略）：那是知识库那侧"老数据没有这三列 ⇒ GET 回默认值而不是省略" 的教训 ——
   * 省略会让前端回落到自己的硬编码值，而两边一旦不同，界面显示 3、实际按别的值检索。
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  /**
   * 检索参数的外部形状。
   *
   * <p>★ 后五项是 {@link Integer} / {@link Boolean} 而**不是**基本类型（`#294` 的教训；`#283` 的多跳两项与 `#297` 的查询理解三项
   * 各是同一批反序列化风险）：导入端点会把 外部 JSON 反序列化成这个类型，而杰克逊 3 默认开着 {@code FAIL_ON_NULL_FOR_PRIMITIVES} ——
   * 旧版本导出的包里没有这些字段 ⇒ {@code null} ⇒ {@code Cannot map `null` into type `boolean`}， 整包被拒成
   * 400「请求体不是合法 JSON」。 前四项一直是基本类型却没出过事，只因为**它们从来都有值**（老契约就有）；新增项在旧包里必然缺席。
   *
   * <p>⇒ 缺省即"用默认值"（查询理解 3 / 开 / 3；多跳关、2 轮），由紧凑构造器归一，与库里的列默认值一致。
   */
  public record RetrievalView(
      String mode,
      double threshold,
      int topN,
      String noRefStrategy,
      Integer queryCount,
      Boolean decomposeEnabled,
      Integer maxSubQuestions,
      Boolean multiHopEnabled,
      Integer maxHops,
      Boolean rerankListwise) {

    /**
     * ★ 缺省归一：旧包没有这几项时按库里的列默认值补齐（查询理解 3 / 开 / 3；多跳关、2 轮）。
     *
     * <p>★ {@code rerankListwise} <b>不</b>归一成 {@code false}：它的三档语义里 {@code null} 表示"跟随全局" （用户
     * 2026-11-08），归一会让"没配过"变成"显式关"，全局开关一开就再也打开不了 —— 这与 {@code #283} 要消灭的"配了不生效"是同一族问题。
     */
    public RetrievalView {
      queryCount = queryCount == null ? 3 : queryCount;
      decomposeEnabled = decomposeEnabled == null ? Boolean.TRUE : decomposeEnabled;
      maxSubQuestions = maxSubQuestions == null ? 3 : maxSubQuestions;
      multiHopEnabled = multiHopEnabled != null && multiHopEnabled;
      maxHops = maxHops == null ? 2 : maxHops;
    }

    /**
     * 旧签名的构造器（{@code #283} 时期，9 参）。
     *
     * @param mode 检索模式
     * @param threshold 相似度阈值
     * @param topN 引用分段 Top
     * @param noRefStrategy 无引用策略
     * @param queryCount 查询理解条数
     * @param decomposeEnabled 是否拆子问题
     * @param maxSubQuestions 子问题数上限
     * @param multiHopEnabled 多跳开关
     * @param maxHops 多跳轮数
     */
    public RetrievalView(
        String mode,
        double threshold,
        int topN,
        String noRefStrategy,
        Integer queryCount,
        Boolean decomposeEnabled,
        Integer maxSubQuestions,
        Boolean multiHopEnabled,
        Integer maxHops) {
      this(
          mode,
          threshold,
          topN,
          noRefStrategy,
          queryCount,
          decomposeEnabled,
          maxSubQuestions,
          multiHopEnabled,
          maxHops,
          null);
    }
  }

  /**
   * 由实体组装。
   *
   * @param modelName 由调用方查好（{@code ModelStatusPort}）—— 视图层不查库，这是本项目的一贯分工
   * @param favorited 当前请求者是否收藏了它（同样由调用方查好；列表走批量查询，单条走 {@code exists}）
   */
  public static AssistantView from(Assistant assistant, String modelName, boolean favorited) {
    return new AssistantView(
        assistant.id(),
        assistant.name(),
        assistant.icon(),
        assistant.description(),
        assistant.type().name(),
        assistant.builtin(),
        assistant.published(),
        favorited,
        assistant.modelId(),
        modelName,
        copyOf(assistant.kbIds()),
        assistant.rolePrompt(),
        copyOf(assistant.promptIds()),
        new RetrievalView(
            assistant.retrievalMode().name(),
            assistant.retrievalThreshold().doubleValue(),
            assistant.retrievalTopN(),
            assistant.retrievalNoRefStrategy().name(),
            assistant.retrievalQueryCount(),
            assistant.retrievalDecomposeEnabled(),
            assistant.retrievalMaxSubQuestions(),
            assistant.retrievalMultiHopEnabled(),
            assistant.retrievalMaxHops(),
            assistant.retrievalRerankListwise()),
        assistant.multiTurn(),
        assistant.thinkingEnabled(),
        assistant.prologue(),
        assistant.quickQuestions(),
        assistant.callCount(),
        assistant.tokenCount(),
        assistant.satisfaction(),
        assistant.ownerId(),
        assistant.visibility().name(),
        assistant.grants(),
        copyOf(assistant.tags()),
        assistant.createdAt());
  }

  /**
   * 把领域对象上的集合**复制出来**，而不是把它的引用塞进视图。
   *
   * <p>★ 这不是防御性编程的洁癖，是一个真踩过的 500：{@code Assistant.kbIds/promptIds/tags} 是 JPA 的懒加载集合，而视图是 record ——
   * 它的字段要到 <strong>Jackson 序列化</strong>时才被读取，那时 {@code @Transactional} 方法的会话早已关闭，于是抛 {@code
   * LazyInitializationException: Cannot lazily initialize collection ... (no session)}。
   *
   * <p>症状特别容易误判：同一个实体在「创建 / 更新」这类先写过集合的路径上不炸（写的时候集合已被 初始化），只在「从模板 instantiate
   * 出来再读」这类干净会话上炸，看起来像"模板功能坏了"而不是 "视图层持有懒加载引用"。
   *
   * <p>复制发生在 {@code from(...)} 被调用的那一刻 —— 也就是事务内 —— 所以它同时也是一次 显式初始化。{@code null}
   * 统一归一成空列表：视图里两个"没有"（null 与空）对前端没有区别， 而对序列化有区别。
   */
  private static <T> List<T> copyOf(List<T> source) {
    return source == null ? List.of() : List.copyOf(source);
  }
}
