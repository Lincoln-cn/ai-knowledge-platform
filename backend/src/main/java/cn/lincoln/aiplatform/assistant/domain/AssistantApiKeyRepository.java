package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 应用 API Key 的存储端口。
 *
 * <p>★ 三个动作的语义：{@link #add} 只新增（轮换 = 再 add 一条，旧行不动）；{@link #setEnabled} 是唯一的改动作；{@link #list}
 * 按创建时间倒序（管理页顺序）。
 *
 * <p>★ <strong>不做删除</strong>：契约只有 list / rotate / enable 三条，页面也没有删除按钮；"不再通过鉴权"由 {@code
 * enabled=false} 表达。
 */
public interface AssistantApiKeyRepository {

  /** 某个应用的全部 Key，新生成的在前。 */
  List<AssistantApiKey> list(UUID assistantId);

  /** 新增一把 Key（调用方负责生成 {@code keyValue}）；返回落库后的行。 */
  AssistantApiKey add(UUID assistantId, String keyValue);

  /** 按应用 + Key id 找一行；找不到返回空（用于启停时判归属）。 */
  Optional<AssistantApiKey> find(UUID assistantId, UUID keyId);

  /** 启停；Key 不属于这个应用（或不存在）时返回 {@code false}，不静默当成成功。 */
  boolean setEnabled(UUID assistantId, UUID keyId, boolean enabled);
}
