package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowDraft;
import cn.lincoln.aiplatform.assistant.domain.WorkflowDraftRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 编排草稿的读写（{@code #198} 第一块）。
 *
 * <p>★ 这里<strong>不测</strong>图合不合规：保存本来就允许不合规，那件事归发布前校验。 本类只钉四件事：没草稿时给空图、保存后能读回来、空内容被拒、应用不存在给 404。
 */
class WorkflowDraftServiceTest {

  private static final UUID APP = UUID.randomUUID();

  private WorkflowDraftRepository drafts;
  private AssistantRepository assistants;
  private WorkflowDraftService service;

  @BeforeEach
  void setUp() {
    drafts = mock(WorkflowDraftRepository.class);
    assistants = mock(AssistantRepository.class);
    when(assistants.findById(APP)).thenReturn(Optional.of(mock(Assistant.class)));
    service = new WorkflowDraftService(drafts, assistants);
  }

  @Test
  void absentDraftIsAnEmptyGraphNotA404() {
    when(drafts.findByAssistantId(APP)).thenReturn(Optional.empty());
    WorkflowDraftService.View view = service.get(APP);
    assertThat(view.graph()).as("★ 还没画是正常状态：给空图，不是 404").isEqualTo("{\"nodes\":[],\"edges\":[]}");
    assertThat(view.updatedAt()).as("没有草稿就没有保存时间 —— 页面据此显示「还没有草稿」").isNull();
  }

  @Test
  void savingThenReadingReturnsTheSameGraph() {
    Instant now = Instant.parse("2026-10-31T10:00:00Z");
    String graph = "{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[]}";
    when(drafts.findByAssistantId(APP)).thenReturn(Optional.empty());
    when(drafts.save(any(WorkflowDraft.class))).thenAnswer(inv -> inv.getArgument(0));

    WorkflowDraftService.Saved saved = service.save(APP, graph, now);
    assertThat(saved.savedAt()).isEqualTo(now);

    WorkflowDraft stored = new WorkflowDraft(APP, graph, now);
    when(drafts.findByAssistantId(APP)).thenReturn(Optional.of(stored));
    assertThat(service.get(APP).graph()).as("★ 存进去什么，读出来就是什么（后端这一层不理解节点类型）").isEqualTo(graph);
    assertThat(service.get(APP).updatedAt()).isEqualTo(now);
  }

  @Test
  void blankGraphIsRejected() {
    assertThatThrownBy(() -> service.save(APP, "   ", Instant.now()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("草稿内容为空");
  }

  @Test
  void unknownAssistantIsNotFound() {
    UUID ghost = UUID.randomUUID();
    assertThatThrownBy(() -> service.get(ghost))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("应用不存在");
  }
}
