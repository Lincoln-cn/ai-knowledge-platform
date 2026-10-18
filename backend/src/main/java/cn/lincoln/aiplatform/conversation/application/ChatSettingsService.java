package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.infrastructure.ChatSettingsStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「通用会话」设置的用例层。
 *
 * <p>★ 这一层只做两件事：<b>读</b>、<b>改（含校验）</b>。它<b>不</b>决定"某次对话用哪套策略" —— 那是 {@link ChatPolicyResolver}
 * 的事（三层继承）。分开的理由： 前者是配置管理，后者是运行期解析，混在一起会让"改配置"和"跑对话"共享同一段校验分支。
 */
@Service
public class ChatSettingsService {

  private static final Logger log = LoggerFactory.getLogger(ChatSettingsService.class);

  private final ChatSettingsStore store;

  public ChatSettingsService(ChatSettingsStore store) {
    this.store = store;
  }

  /**
   * @return 当前设置
   */
  @Transactional(readOnly = true)
  public ChatSettingsView get() {
    return store.load();
  }

  /**
   * 整行覆盖更新。
   *
   * <p>★★ 先**构造一次策略**再落库：`ChatStreamPolicy` 的构造器是唯一的不变量检查处 （heartbeat×3 ≤ idle、所有值 >
   * 0）。这样"存进去的一定是能跑的组合"， 而不是"存进去、等第一次对话时才炸"。
   *
   * @param command 新设置
   * @param operator 操作人
   * @return 落库后的设置
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 数值非法（400 + 修复建议）
   */
  @Transactional
  public ChatSettingsView update(ChatSettingsCommand command, UUID operator) {
    ChatSettingsView candidate =
        new ChatSettingsView(
            command.defaultModelId(),
            command.streamEnabled(),
            command.idleTimeoutMs(),
            command.heartbeatIntervalMs(),
            command.firstTokenBudgetMs(),
            command.systemPrompt(),
            Boolean.TRUE.equals(command.retrievalEnabled()),
            command.retrievalKbIds(),
            command.retrievalThreshold() == null ? 0.3 : command.retrievalThreshold(),
            command.retrievalTopN() == null ? 5 : command.retrievalTopN(),
            command.maxOutputTokens(),
            null);
    try {
      // ★ 只为触发校验，不保留返回值
      candidate.toPolicy();
    } catch (IllegalArgumentException invalid) {
      throw ChatStreamPolicy.asBadRequest(invalid);
    }
    ChatSettingsView saved = store.save(candidate, operator);
    log.info(
        "通用会话设置已更新：stream={} idle={}ms heartbeat={}ms firstTokenBudget={}ms maxTokens={}（操作人 {}）",
        saved.streamEnabled(),
        saved.idleTimeoutMs(),
        saved.heartbeatIntervalMs(),
        saved.firstTokenBudgetMs(),
        saved.maxOutputTokens(),
        operator);
    return saved;
  }

  /**
   * 一次更新的入参（契约 {@code ChatSettingsCommand}）。
   *
   * <p>★ 与 {@link ChatSettingsView} 分开：入参没有 {@code updatedAt}（那是服务端写的）， 而把两者合并成同一个类型会让"客户端能不能传
   * updatedAt"变成一个靠自觉的问题。
   *
   * @param defaultModelId 默认对话模型（可空）
   * @param streamEnabled 是否流式
   * @param idleTimeoutMs 空闲超时
   * @param heartbeatIntervalMs 心跳间隔
   * @param firstTokenBudgetMs 首字延迟预算
   * @param systemPrompt 通用问答的初始提示词（可空 = 不加前缀）
   * @param maxOutputTokens 单轮最大输出（可空）
   */
  public record ChatSettingsCommand(
      UUID defaultModelId,
      boolean streamEnabled,
      long idleTimeoutMs,
      long heartbeatIntervalMs,
      long firstTokenBudgetMs,
      String systemPrompt,
      Boolean retrievalEnabled,
      java.util.List<UUID> retrievalKbIds,
      Double retrievalThreshold,
      Integer retrievalTopN,
      Integer maxOutputTokens) {}
}
