package cn.lincoln.aiplatform.conversation.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ★★ 三层继承的**唯一**解析处：应用 → 通用会话 → 全局 yaml 兜底。
 *
 * <h3>为什么必须只有一个解析处</h3>
 *
 * <p>"某次对话用什么 idle / 心跳 / 是否流式"必须能被回答**一次**。若对话链路、日志、界面 各自算一遍，就会出现"界面显示 240s、实际跑 60s"这类**静默不一致** ——
 * 而它不会报错， 只会让人在超时后怀疑模型。
 *
 * <h3>本批的边界（读这里再看调用点）</h3>
 *
 * <p>★ <b>应用层覆盖尚未接线</b>：{@code as_assistant.chat_*} 五列已在 {@code V39} 落库， 但 {@code
 * AppBindingPort.AppBinding} 还没带上它们。本类把应用覆盖做成一个**入参** （{@link
 * AppOverrides}），接线时只需在调用点把它填上，<b>不必改解析规则</b>。 ⇒ 现在传 {@code null} 等价于"应用未覆盖"，与未来的行为一致。
 */
@Component
public class ChatPolicyResolver {

  private static final Logger log = LoggerFactory.getLogger(ChatPolicyResolver.class);

  private final ChatSettingsService settings;
  private final ChatStreamProperties fallback;

  public ChatPolicyResolver(ChatSettingsService settings, ChatStreamProperties fallback) {
    this.settings = settings;
    this.fallback = fallback;
  }

  /**
   * 解析一次对话实际生效的策略。
   *
   * <p>★ 逐项覆盖（不是"整层覆盖"）：应用只想改 idle 时，心跳仍可来自通用会话。 这与"两层各一份完整配置、二选一"不同 —— 后者会让用户被迫复制 5 个数字。
   *
   * @param overrides 应用级覆盖（可空 = 该应用没有覆盖）；★ 本批调用点传 {@code null}
   * @return 生效策略（{@code origin} 说明每个数来自哪一层）
   */
  public ChatStreamPolicy resolve(AppOverrides overrides) {
    ChatSettingsView base = settings.get();

    boolean streamEnabled =
        overrides != null && overrides.streamEnabled() != null
            ? overrides.streamEnabled()
            : base.streamEnabled();
    long idle =
        firstNonNull(overrides == null ? null : overrides.idleTimeoutMs(), base.idleTimeoutMs());
    long heartbeat =
        firstNonNull(
            overrides == null ? null : overrides.heartbeatIntervalMs(), base.heartbeatIntervalMs());
    long firstToken =
        firstNonNull(
            overrides == null ? null : overrides.firstTokenBudgetMs(), base.firstTokenBudgetMs());
    Integer maxTokens =
        overrides != null && overrides.maxOutputTokens() != null
            ? overrides.maxOutputTokens()
            : base.maxOutputTokens();

    // ★ 兜底层只用于"通用会话这一行读不到"的极端情况；正常永远走不到（V39 已插入该行）。
    if (idle <= 0) {
      idle = fallback.idleTimeoutMs();
      heartbeat = fallback.heartbeatIntervalMs();
      log.warn("通用会话设置的 idle 非法（{}），回落到全局 app.chat.* 兜底", idle);
    }

    String origin = overrides == null ? "通用会话（应用未覆盖）" : "应用覆盖 + 通用会话补齐";

    try {
      return new ChatStreamPolicy(streamEnabled, idle, heartbeat, firstToken, maxTokens, origin);
    } catch (IllegalArgumentException invalid) {
      // ★ 继承可能把两层各自合法的值拼成非法组合 —— 必须在这里翻译成可读的 400，
      //   而不是让第一次对话在写 SSE 时抛 IllegalArgumentException（那时响应已开始）。
      throw ChatStreamPolicy.asBadRequest(invalid);
    }
  }

  private static long firstNonNull(Long preferred, long fallbackValue) {
    return preferred != null ? preferred : fallbackValue;
  }

  /**
   * 应用级覆盖（对应 {@code as_assistant.chat_*} 五列，全部可空）。
   *
   * @param streamEnabled 是否流式（空 = 继承）
   * @param idleTimeoutMs 空闲超时（空 = 继承）
   * @param heartbeatIntervalMs 心跳间隔（空 = 继承）
   * @param firstTokenBudgetMs 首字预算（空 = 继承）
   * @param maxOutputTokens 单轮最大输出（空 = 继承）
   */
  public record AppOverrides(
      Boolean streamEnabled,
      Long idleTimeoutMs,
      Long heartbeatIntervalMs,
      Long firstTokenBudgetMs,
      Integer maxOutputTokens) {

    /**
     * @return 一个什么都没覆盖的应用（等价于"该应用全跟通用会话"）
     */
    public static AppOverrides none() {
      return new AppOverrides(null, null, null, null, null);
    }
  }

  /**
   * 诊断用：把策略压成一行（写日志/trace 时用，避免每次手拼）。
   *
   * @param policy 策略
   * @return 一行描述
   */
  public static String describe(ChatStreamPolicy policy) {
    return "stream=%s idle=%dms heartbeat=%dms firstTokenBudget=%dms maxTokens=%s 来源=%s"
        .formatted(
            policy.streamEnabled(),
            policy.idleTimeoutMs(),
            policy.heartbeatIntervalMs(),
            policy.firstTokenBudgetMs(),
            policy.maxOutputTokens() == null ? "不限" : policy.maxOutputTokens(),
            policy.origin());
  }

  /**
   * @return 通用会话当前的默认模型（可空 = 交给 pickChatModel）
   */
  public UUID defaultModelId() {
    return settings.get().defaultModelId();
  }

  /**
   * 通用问答的初始提示词。
   *
   * <p>★ 只对**通用问答**（{@code appId} 为空）生效：应用有自己的 {@code
   * as_assistant.role_prompt}，两者叠加会变成「两条口径同时约束一个回答」， 冲突时谁赢没人说得清。
   *
   * @return 提示词；{@code null}/空白 = 不加前缀
   */
  public String systemPrompt() {
    String prompt = settings.get().systemPrompt();
    return prompt == null || prompt.isBlank() ? null : prompt.strip();
  }
}
