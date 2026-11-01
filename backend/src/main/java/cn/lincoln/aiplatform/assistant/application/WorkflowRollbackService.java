package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把某一历史版本的图<strong>写回草稿</strong>（契约 {@code rollbackWorkflow}）。
 *
 * <p>★★ 「回滚」在这里只有<strong>一个</strong>含义：<em>让草稿变成那一版的样子</em>。它<strong>不发布、不切换线上</strong> ——
 * 用户回滚完还要自己点一次「发布」才生效。这一句必须写进契约注释与提交消息，理由不是"说清楚"，而是：<strong>写成"回滚到某一版"很容易被读成"线上立刻回到那一版"</strong>，
 * 而这两件事的后果差得很远 —— 前者只是草稿变了（随时可再改），后者是线上行为变了（在跑的应用换了一套图）。
 *
 * <p>★ 与发布只有一处交集：图从 {@code as_workflow_version} 读、写进 {@code as_workflow_draft}。版本行
 * <strong>一行都不动</strong>：版本表是只能追加的账，回滚不新增行、不改旧行 —— 所以回滚之后 {@code GET …/versions}
 * 的长度不变，"回滚"不是"切回旧版"，而是"在草稿里重画旧版"。
 *
 * <p>★ 两个"不做"，都与 {@link WorkflowDraftService#save} 保持一致：
 *
 * <p>① <strong>不校验图</strong> —— 历史版本是<em>当时</em>校验通过才发布出来的；拿今天的校验器去判昨天的图，会把一次本来正确的回滚拒掉（而且它拒的
 * 恰恰是"线上正在跑的那张图"，最不该拒）。草稿保存本来也不校验，回滚只是另一种写草稿。
 *
 * <p>② <strong>不写审计</strong> —— {@code WorkflowDraftService.save} 不写；只给回滚写一次，会让审计里出现"这张草稿被改过"的
 * <em>部分</em>记录，读起来像"只有回滚改过草稿"。真正该留痕的是<strong>发布</strong>（{@code WorkflowVersionStore} 写 {@code
 * WORKFLOW_PUBLISH}），回滚的结果最终要通过发布才影响线上。
 */
@Service
public class WorkflowRollbackService {

  private final WorkflowDraftService drafts;
  private final WorkflowVersionQueryService versions;
  private final ObjectMapper json;

  public WorkflowRollbackService(
      WorkflowDraftService drafts, WorkflowVersionQueryService versions, ObjectMapper json) {
    this.drafts = drafts;
    this.versions = versions;
    this.json = json;
  }

  /**
   * 回滚：把版本 {@code versionKey} 的图原样写进草稿，返回写回后的草稿。
   *
   * <p>★ 顺序是<strong>先确认应用、再确认版本、最后才写</strong>：任何一步 404 都发生在写之前，所以"回滚失败"在数据库里
   * <strong>不留半截副作用</strong>（草稿要么还是原样，要么已经是那一版，不存在"改了一半"）。
   *
   * <p>★ 响应里的 {@code graph} 与 {@code updatedAt} 是<strong>写完之后重新读一次草稿</strong>得来的，不是把入参原样回显：
   * 这样"回滚返回的图"与"紧接着 {@code GET /workflow/draft} 拿到的图"必然一致，页面可以直接用返回值刷新画布， 也不必解释"为什么返回的和刷新出来的不一样"。
   *
   * @param versionKey 版本号（整数）或版本行主键（UUID）；解析与 404 文案与 {@code GET …/versions/{key}} 在 {@link
   *     WorkflowVersionQueryService#require} 同一处
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException 404 应用不存在 / 404 版本不存在或不属于这个应用
   */
  @Transactional
  public RolledBack rollback(UUID assistantId, String versionKey) {
    drafts.requireAssistant(assistantId);
    WorkflowVersion version = versions.require(assistantId, versionKey);
    drafts.save(assistantId, version.graph(), Instant.now());
    WorkflowDraftService.View stored = drafts.get(assistantId);
    return new RolledBack(json.readTree(stored.graph()), stored.updatedAt(), version.version());
  }

  /**
   * 回滚结果（契约 {@code rollbackWorkflow} 的响应）。
   *
   * <p>★ 三个字段各有用途：{@code graph} 供页面直接重绘画布（不必再发一次 GET）、{@code updatedAt} 让页面显示"已保存" 而不必猜本地时间、{@code
   * version} 让页面能写「已回滚到 v3」而<strong>不必再查一次列表去猜</strong>是哪一版。
   *
   * <p>★ {@code graph} 是<strong>对象</strong>不是字符串：与 {@code GET …/workflow/draft} 不同（那边受历史形状所限返回字符串），
   * 这是新端点，没有兼容包袱 —— 让前端拿到就能用，别再多一层 {@code JSON.parse}。
   */
  public record RolledBack(JsonNode graph, Instant updatedAt, int version) {}
}
