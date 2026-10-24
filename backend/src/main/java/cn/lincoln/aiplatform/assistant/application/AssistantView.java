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
  public record RetrievalView(
      String mode,
      double threshold,
      int topN,
      String noRefStrategy,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions) {}

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
        assistant.kbIds(),
        assistant.rolePrompt(),
        assistant.promptIds(),
        new RetrievalView(
            assistant.retrievalMode().name(),
            assistant.retrievalThreshold().doubleValue(),
            assistant.retrievalTopN(),
            assistant.retrievalNoRefStrategy().name(),
            assistant.retrievalQueryCount(),
            assistant.retrievalDecomposeEnabled(),
            assistant.retrievalMaxSubQuestions()),
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
        assistant.tags(),
        assistant.createdAt());
  }
}
