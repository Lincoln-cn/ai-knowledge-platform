package cn.lincoln.aiplatform.assistant.domain;

import java.util.Optional;
import java.util.UUID;

/** 编排草稿仓储。★ 没有删除方法：草稿随应用级联删除（外键 {@code on delete cascade}）。 */
public interface WorkflowDraftRepository {

  Optional<WorkflowDraft> findByAssistantId(UUID assistantId);

  WorkflowDraft save(WorkflowDraft draft);
}
