package cn.lincoln.aiplatform.conversation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 「通用会话」设置的对外视图（契约 {@code ChatSettings}）。
 *
 * <p>★ 字段与 {@code cv_chat_settings} 一一对应，**不含** created_* 之类的运维列 —— 界面要的是"能改什么、现在是多少"，不是审计字段。
 *
 * @param defaultModelId 通用问答默认对话模型；★ {@code null} = 沿用「挑一个可用的」（旧行为，不是"没配"）
 * @param streamEnabled 是否流式输出
 * @param idleTimeoutMs 多久没字节写出判吊死（★ SSE 唯一超时权威）
 * @param heartbeatIntervalMs 心跳间隔
 * @param firstTokenBudgetMs 首字延迟预算（B4：≤10s）
 * @param systemPrompt 通用问答的初始提示词（★ 空 = 不加前缀；应用会话不读它）
 * @param retrievalEnabled 通用问答是否走召回（★ false = 一条引用都不检索）
 * @param retrievalKbIds 召回范围（知识库 id）；★ 空 = 该用户可见的全部库
 * @param retrievalThreshold 相似度下限，低于它的命中丢弃
 * @param retrievalTopN 召回条数上限
 * @param historyMaxMessages 多轮带的历史消息条数上限（20 ≈ 10 轮；0 = 不带历史）
 * @param maxOutputTokens 单轮最大输出 tokens；{@code null} = 不限
 * @param updatedAt 最后修改时间（界面显示"上次改动"，便于确认改没改上）
 */
public record ChatSettingsView(
    UUID defaultModelId,
    boolean streamEnabled,
    long idleTimeoutMs,
    long heartbeatIntervalMs,
    long firstTokenBudgetMs,
    String systemPrompt,
    boolean retrievalEnabled,
    List<UUID> retrievalKbIds,
    double retrievalThreshold,
    int retrievalTopN,
    int historyMaxMessages,
    Integer maxOutputTokens,
    Instant updatedAt) {

  /**
   * 转成一次对话实际生效的策略（来源标为"通用会话"）。
   *
   * @return 生效策略
   * @throws IllegalArgumentException 数值非法（由 {@link ChatStreamPolicy} 构造器判）
   */
  public ChatStreamPolicy toPolicy() {
    return new ChatStreamPolicy(
        streamEnabled,
        idleTimeoutMs,
        heartbeatIntervalMs,
        firstTokenBudgetMs,
        maxOutputTokens,
        "通用会话");
  }
}
