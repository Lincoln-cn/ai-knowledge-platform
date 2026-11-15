package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import cn.lincoln.aiplatform.conversation.domain.UserProfile;
import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import java.util.ArrayList;
import java.util.List;
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

  public MemoryRetriever(
      MemoryRepository memories, MemoryService memoryService, MemorySettingsStore settings) {
    this.memories = memories;
    this.memoryService = memoryService;
    this.settings = settings;
  }

  /**
   * 召回一次。
   *
   * <p>★ 这里吞掉所有异常并返回空的 {@link Recall}：一次记忆库抖动不应该让用户的问题答不出来。 失败会打
   * WARN，<strong>并带上一句"这次没带记忆"</strong> —— 不写日志的话， "模型这次答得不对"会被归因成模型不好，而不是"记忆没取到"。
   *
   * @param userId 用户
   * @param conversationId 当前会话（可为 {@code null}：新建会话时还没有 id）
   * @return 召回结果（永不为 {@code null}）
   */
  public Recall recall(UUID userId, UUID conversationId) {
    if (userId == null) {
      return Recall.empty("没有用户，跳过召回");
    }
    try {
      MemorySettingsView current = settings.load();
      if (!current.enabled()) {
        return Recall.empty("记忆总开关是关的");
      }
      List<Memory> persona =
          memories.findRecalls(
              userId, MemoryLevel.PERSONA, null, current.minImportance(), PERSONA_LIMIT);
      List<Memory> user =
          memories.findRecalls(
              userId, MemoryLevel.USER, null, current.minImportance(), current.recallLimit());
      List<Memory> cross =
          memories.findRecalls(
              userId,
              MemoryLevel.CROSS_SESSION,
              null,
              current.minImportance(),
              current.recallLimit());
      List<Memory> conversation =
          conversationId == null
              ? List.of()
              : memories.findRecalls(
                  userId,
                  MemoryLevel.CONVERSATION,
                  conversationId,
                  current.minImportance(),
                  current.recallLimit());
      UserProfile profile =
          current.injectProfile()
              ? memoryService.profileOf(userId)
              : UserProfile.empty(userId, null);
      return build(current, persona, user, cross, conversation, profile);
    } catch (RuntimeException e) {
      LOG.warn("记忆召回失败，本次对话不带记忆：{}", e.toString());
      return Recall.empty("召回异常：" + e.getClass().getSimpleName());
    }
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
      UserProfile profile) {
    List<Memory> picked = new ArrayList<>();
    picked.addAll(persona);
    picked.addAll(user);
    picked.addAll(cross);
    picked.addAll(conversation);
    if (picked.size() > current.recallLimit()) {
      picked = picked.subList(0, current.recallLimit());
    }
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
