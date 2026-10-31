package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowDraft;
import cn.lincoln.aiplatform.assistant.domain.WorkflowDraftRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排草稿的读写（{@code #198} 第一块）。
 *
 * <p>★ 本层<strong>不校验</strong>图是否合法：保存允许不合规（边画边存的唯一意义就是这个）， 校验发生在发布前（{@code POST
 * /workflow/validate}）。在这里顺手校验，第一次保存半个图就会被拒。
 *
 * <p>★ 但"应用存不存在、我能不能看"必须校验 —— 那是越权问题，与图的形状无关。
 */
@Service
public class WorkflowDraftService {

  /** 没有草稿时的形状：空图。返回它而不是 404，是因为"还没画"是正常状态。 */
  private static final String EMPTY_GRAPH = "{\"nodes\":[],\"edges\":[]}";

  private final WorkflowDraftRepository drafts;
  private final AssistantRepository assistants;

  public WorkflowDraftService(WorkflowDraftRepository drafts, AssistantRepository assistants) {
    this.drafts = drafts;
    this.assistants = assistants;
  }

  /**
   * 读草稿。
   *
   * @return 永不为 null；没保存过时 {@code updatedAt == null}（页面据此显示"还没有草稿"）
   */
  @Transactional(readOnly = true)
  public View get(UUID assistantId) {
    requireAssistant(assistantId);
    return drafts
        .findByAssistantId(assistantId)
        .map(d -> new View(d.graph(), d.updatedAt()))
        .orElseGet(() -> new View(EMPTY_GRAPH, null));
  }

  /** 保存草稿（整体覆盖；自动保存也走这里，所以不写审计 —— 每次按键都留痕会把审计表淹掉）。 */
  @Transactional
  public Saved save(UUID assistantId, String graphJson, Instant now) {
    requireAssistant(assistantId);
    if (graphJson == null || graphJson.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿内容为空", "请把画布上的图带上（body: {graph}）");
    }
    WorkflowDraft saved =
        drafts
            .findByAssistantId(assistantId)
            .map(
                existing -> {
                  existing.replace(graphJson, now);
                  return drafts.save(existing);
                })
            .orElseGet(() -> drafts.save(new WorkflowDraft(assistantId, graphJson, now)));
    return new Saved(saved.updatedAt());
  }

  private void requireAssistant(UUID assistantId) {
    if (assistants.findById(assistantId).isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "应用不存在：" + assistantId, "它可能已被删除");
    }
  }

  /** 草稿视图（契约 {@code getWorkflowDraft}）。 */
  public record View(String graph, Instant updatedAt) {}

  /** 保存结果（契约 {@code saveWorkflowDraft}）。 */
  public record Saved(Instant savedAt) {}
}
