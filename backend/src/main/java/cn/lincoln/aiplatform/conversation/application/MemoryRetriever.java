package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import cn.lincoln.aiplatform.conversation.domain.UserProfile;
import cn.lincoln.aiplatform.conversation.infrastructure.MemoryEmbeddingWriter;
import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运行时分层召回（{@code 0.9.0 · #296}，规则见 {@code 15-长期记忆设计.md §6}）。
 *
 * <h3>★ 四层各取什么、为什么</h3>
 *
 * <table>
 *   <tr><th>层</th><th>查询</th><th>上限</th><th>理由</th></tr>
 *   <tr><td>PERSONA</td><td>按重要度取全部</td><td>3</td><td>人格化记忆本来就少，多取只会重复</td></tr>
 *   <tr><td>USER</td><td>按重要度取</td><td>recallLimit</td><td>跨会话的稳定事实，最该出现</td></tr>
 *   <tr><td>CROSS_SESSION</td><td>按重要度取</td><td>recallLimit</td><td>同上，但更偏向"这件事的来龙去脉"</td></tr>
 *   <tr><td>CONVERSATION</td><td>只取当前会话</td><td>recallLimit</td><td>★ 绝不跨会话：把上一个会话的细节混进来是最像"记得"的错误</td></tr>
 * </table>
 *
 * <h3>★ 三条不变量</h3>
 *
 * <ol>
 *   <li><strong>记忆段排在消息窗口之前</strong>：模型先看到"我记得的"，再用窗口里的对话修正；
 *   <li><strong>合计不超过 {@code recallLimit}</strong>：四层各自都取满会把窗口挤爆；
 *   <li><strong>任何失败都不阻断对话</strong>：记忆是增强项，取不到就当作这个人没有记忆。
 * </ol>
 */
@Component
public class MemoryRetriever {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryRetriever.class);

  /** 人格化记忆的注入条数上限。 */
  private static final int PERSONA_LIMIT = 3;

  /** 记忆段的字数预算上限（字符）。 */
  private static final int MAX_BLOCK_CHARS = 1200;

  private final MemoryRepository memories;
  private final MemoryService memoryService;
  private final MemorySettingsStore settings;
  private MemoryEmbeddingWriter vectors;
  private EmbeddingPort embeddingPort;

  public MemoryRetriever(
      MemoryRepository memories, MemoryService memoryService, MemorySettingsStore settings) {
    this.memories = memories;
    this.memoryService = memoryService;
    this.settings = settings;
  }

  /**
   * ★ setter 可选注入：向量读取只在"要按相似度排序"时才需要，而排序本身是可选的增强 （没有向量的记忆仍按重要度参与）。用构造器参数会逼所有脱离容器的测试都提供一份。
   *
   * @param vectors 向量读取器（空 = 不按相似度排序）
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setVectors(MemoryEmbeddingWriter vectors) {
    this.vectors = vectors;
  }

  /**
   * ★ setter 可选注入：没装向量模型时"记忆不参与相似度排序"，这是合法部署而不是错误。
   *
   * @param embeddingPort 向量化端口（空 = 只用重要度与层权重排序）
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setEmbeddingPort(EmbeddingPort embeddingPort) {
    this.embeddingPort = embeddingPort;
  }

  /**
   * 召回一次。
   *
   * <p>★ 这里吞掉所有异常并返回空的 {@link Recall}：一次记忆库抖动不应该让用户的问题答不出来。 失败会打
   * WARN，<strong>并带上一句"这次没带记忆"</strong> —— 不写日志的话， "模型这次答得不对"会被归因成模型不好，而不是"记忆没取到"。
   *
   * @param userId 用户
   * @param conversationId 当前会话（可为 {@code null}：新建会话时还没有 id）
   * @param question 这次的问题（用于跨会话层的相似度排序；空则可跳过向量这一步）
   * @return 召回结果（永不为 {@code null}）
   */
  public Recall recall(UUID userId, UUID conversationId, String question) {
    if (userId == null) {
      return Recall.empty("没有用户，跳过召回");
    }
    try {
      MemorySettingsView current = settings.load();
      if (!current.enabled()) {
        return Recall.empty("记忆总开关是关的");
      }
      /* ★ 一次取回四层（旧实现是四次单层查询，其中三次今天查的是必然空的结果）。 */
      List<Memory> rows =
          memories.findRecallsAcrossLevels(
              userId, conversationId, current.minImportance(), recalleableBudget(current));
      List<Memory> persona =
          rows.stream().filter(m -> m.level() == MemoryLevel.PERSONA).limit(PERSONA_LIMIT).toList();
      List<Memory> user = rows.stream().filter(m -> m.level() == MemoryLevel.USER).toList();
      List<Memory> cross =
          rows.stream().filter(m -> m.level() == MemoryLevel.CROSS_SESSION).toList();
      List<Memory> conversation =
          rows.stream().filter(m -> m.level() == MemoryLevel.CONVERSATION).toList();
      UserProfile profile =
          current.injectProfile()
              ? memoryService.profileOf(userId)
              : UserProfile.empty(userId, null);
      Map<UUID, Float> relevance = relevanceOf(rows, question);
      return build(current, persona, user, cross, conversation, profile, relevance);
    } catch (RuntimeException e) {
      LOG.warn("记忆召回失败，本次对话不带记忆：{}", e.toString());
      return Recall.empty("召回异常：" + e.getClass().getSimpleName());
    }
  }

  /**
   * 候选记忆与这次问题的相似度（只对<strong>有向量</strong>的那批算）。
   *
   * <p>★ 只有跨会话层真正需要它：用户与人格化是"恒定背景"（每次都在，相似度没有意义）， 会话层只放约定（条数极少且已按会话过滤）。设计册 §6.2 的原话是"这一层最依赖相关"。
   *
   * <p>★ 任何一步失败都退化成空表（= 全部按重要度排），<b>不抛也不重试</b>： 召回是每轮对话都要走的路，为了一路排序信号去多等一次模型或重试一次数据库不值得。
   *
   * @param rows 候选
   * @param question 问题
   * @return 记忆 id → 余弦相似度（-1..1）
   */
  private Map<UUID, Float> relevanceOf(List<Memory> rows, String question) {
    if (embeddingPort == null || vectors == null || question == null || question.isBlank()) {
      return Map.of();
    }
    List<Memory> crossOnly =
        rows.stream().filter(m -> m.level() == MemoryLevel.CROSS_SESSION).toList();
    if (crossOnly.isEmpty()) {
      return Map.of();
    }
    try {
      Map<UUID, float[]> stored = vectors.loadVectors(ids(crossOnly));
      if (stored.isEmpty()) {
        return Map.of();
      }
      Optional<UUID> modelId = vectors.currentModelId();
      if (modelId.isEmpty()) {
        return Map.of();
      }
      List<float[]> embedded = embeddingPort.embed(modelId.get(), List.of(question));
      if (embedded == null || embedded.isEmpty() || embedded.getFirst() == null) {
        return Map.of();
      }
      float[] q = embedded.getFirst();
      Map<UUID, Float> scores = new java.util.HashMap<>();
      stored.forEach((id, v) -> scores.put(id, cosine(q, v)));
      return scores;
    } catch (RuntimeException e) {
      LOG.warn("记忆相似度排序失败，本次退回按重要度排序：{}", e.toString());
      return Map.of();
    }
  }

  private static List<UUID> ids(List<Memory> rows) {
    return rows.stream().map(Memory::id).toList();
  }

  /**
   * ★ 两个向量长度不同时返回 0 而不是抛：维度不一致说明换了模型（列上写死 384）， 那是配置问题，不该让一次对话失败 —— 只是这一条退到按重要度参与。
   *
   * @param a 向量
   * @param b 向量
   * @return 余弦相似度（-1..1；任一为零向量时返回 0）
   */
  static float cosine(float[] a, float[] b) {
    if (a == null || b == null || a.length == 0 || a.length != b.length) {
      return 0f;
    }
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    if (na == 0 || nb == 0) {
      return 0f;
    }
    return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
  }

  /**
   * 一次查询要取多少条。
   *
   * <p>★ 四层各取 {@code recallLimit} 条（再加上人格化的 {@link #PERSONA_LIMIT}）：
   * 合并成一条语句之后，条数上限也必须在<strong>同一条语句里</strong>给足， 否则"上层截断"会变成"随机少了某一层的记忆"—— 那种错误看起来像模型记性不好。
   *
   * @param current 当前设定
   * @return 条数上限
   */
  private static int recalleableBudget(MemorySettingsView current) {
    return current.recallLimit() * 3 + PERSONA_LIMIT;
  }

  /**
   * 把四层结果拼成一段文本。
   *
   * @param current 设定
   * @param persona 人格化记忆
   * @param user 用户级记忆
   * @param cross 跨会话记忆
   * @param conversation 会话级记忆
   * @param profile 画像
   * @return 召回结果
   */
  private Recall build(
      MemorySettingsView current,
      List<Memory> persona,
      List<Memory> user,
      List<Memory> cross,
      List<Memory> conversation,
      UserProfile profile,
      Map<UUID, Float> relevance) {
    List<Memory> candidates = new ArrayList<>();
    candidates.addAll(persona);
    candidates.addAll(user);
    candidates.addAll(cross);
    candidates.addAll(conversation);
    /* ★ 按「层权重 × (重要度 + 相似度加分)」排序后再截到 recallLimit（设计册 §6.2 的合计上限规则）。
     *
     * 上一版是"先按层拼接、再截前 N 条" —— 人格化那 3 条排在前面，于是 recallLimit=5 时
     * 会话细节几乎永远进不来。而这个错误看起来完全正常：注入的记忆段确实有条数，只是
     * 全是"这个人是谁"这种恒定背景，从来不是"这次用得上"的那几条。 */
    List<Memory> picked =
        candidates.stream()
            .sorted(
                java.util.Comparator.comparingDouble((Memory m) -> score(m, relevance)).reversed())
            .limit(current.recallLimit())
            .toList();
    /* ★ 命中计数在召回之后写：它衡量的是"这条记忆被用上了"，而不是"被查出来了"。 */
    List<UUID> used = picked.stream().map(Memory::id).toList();

    StringBuilder sb = new StringBuilder();
    if (profile.hasContent()) {
      sb.append("关于这个人：").append(profile.injectableText()).append('\n');
    }
    if (!picked.isEmpty()) {
      sb.append("你记得的事：\n");
      for (Memory m : picked) {
        sb.append("- ").append(m.content()).append('\n');
      }
    }
    String block = sb.toString().trim();
    boolean truncated = false;
    if (block.length() > MAX_BLOCK_CHARS) {
      block = block.substring(0, MAX_BLOCK_CHARS) + "\n（记忆太多，已截断）";
      truncated = true;
    }
    return new Recall(block, used, truncated, null);
  }

  /** 相似度在排序分里占几倍的重要度。 */
  private static final double RELEVANCE_WEIGHT = 2.0;

  /**
   * 一条记忆的排序分。
   *
   * <p>★ 层权重不是"哪层更重要"，而是"哪层这次更用得上"：用户与人格化是<strong>恒定背景</strong>
   * （每次都在，价值随重复下降），会话与跨会话是<strong>这次用得上</strong>（错过就没有）。 设计册 §6.2 的原话是"恒定背景占了配额，会话细节就进不来"。
   *
   * <p>★ 相似度只作<strong>加分</strong>不作基准：没有向量的那条（或向量模型不可用）仍能靠重要度被召回， 这正是 {@code embedding} 列可空要保住的语义
   * —— 否则"向量化失败"会变成"这条记忆消失"。
   *
   * @param memory 记忆
   * @param relevance 相似度（可能为空表）
   * @return 分值（越大越先注入）
   */
  private static double score(Memory memory, Map<UUID, Float> relevance) {
    double weight =
        switch (memory.level()) {
          case CONVERSATION, CROSS_SESSION -> 1.5;
          default -> 1.0;
        };
    float cosine = relevance.getOrDefault(memory.id(), 0f);
    return weight * (memory.importance() + RELEVANCE_WEIGHT * cosine);
  }

  /**
   * 召回结果。
   *
   * @param block 要注入的文本（空串表示这次没有可用记忆）
   * @param usedMemoryIds 被用上的记忆（用于命中计数）
   * @param truncated 是否因字数预算被截断
   * @param skipReason 跳过原因（正常召回时为 {@code null}；界面与日志都要能看见"为什么这次没带记忆"）
   */
  public record Recall(
      String block, List<UUID> usedMemoryIds, boolean truncated, String skipReason) {

    /**
     * @param reason 原因
     * @return 一条空结果
     */
    public static Recall empty(String reason) {
      return new Recall("", List.of(), false, reason);
    }

    /**
     * @return 是否有可注入内容
     */
    public boolean present() {
      return !block.isEmpty();
    }
  }
}
