package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 编排运行记录的<strong>只读</strong>查询（{@code #198} 剩余端点之一；契约 {@code GET
 * /assistants/{appId}/workflow/records}）。
 *
 * <h3>★★ 这条端点在真实系统里今天<strong>产生不了一行数据</strong></h3>
 *
 * <p>运行记录的唯一写入方是"把用户画的图真的跑一遍"的执行引擎，而 {@code 08 §二} 与 {@code 09 §5.3} 把它排在 <strong>V1.0</strong>；今天
 * {@code workflow} 模块里只有一个 {@code package-info}（准入判据见 {@code caliber/WorkflowEngineAdmissionTest}）。
 *
 * <p>★ 也<strong>不是</strong>画布调试：{@code POST …/workflow/debug} 刻意不写这张表（设计册与 mock 同一口径 ——
 * 调试不产生正式运行记录，否则每点一次「试运行」都会污染真实账）。
 *
 * <p>所以今天这一页<strong>永远是 200 + 空列表</strong>。这不是缺陷的隐写，而是如实状态： 端点、表、契约、隔离与分页都在位，等执行引擎落地就是同一个端点、同一张表。
 * 这句话必须留在代码里 —— 否则下一个人看到"端点有了、表有了"，会以为差的是一个页面。
 *
 * <h3>三条判定都落在本类，而不是控制器</h3>
 *
 * <p>应用存不存在、状态取值域合不合法、跨应用隔离，全部在这里：控制器只负责 HTTP 形状（路径段 → 方法、返回值 → 包络）。判定写在控制器里，换一个入口就会漏一遍（同 {@link
 * WorkflowVersionQueryService} 的分工）。
 */
@Service
public class WorkflowRecordQueryService {

  /** 分页上限：与全项目列表端点同口径（{@code AssistantService} / {@code ChatHistoryService} 等都是 100）。 */
  private static final int MAX_PAGE_SIZE = 100;

  /** 默认每页条数：与其他列表端点一致（20）。 */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /**
   * 终态取值域 = {@code 14 §2.4} 对 {@code WorkflowRecord.status} 的定案。
   *
   * <p>★ 只有两个：<strong>没有</strong> {@code RUNNING}。正在跑的那一次还没有落成记录 ——
   * 记录是"跑完了的结果账"，不是"运行中的进度条"（进度属于执行引擎的运行时状态，V1.0）。
   */
  private static final Set<String> ALLOWED_STATUS = Set.of("SUCCEEDED", "FAILED");

  private final WorkflowDraftService drafts;
  private final WorkflowRecordRepository records;
  private final ObjectMapper json;

  public WorkflowRecordQueryService(
      WorkflowDraftService drafts, WorkflowRecordRepository records, ObjectMapper json) {
    this.drafts = drafts;
    this.records = records;
    this.json = json;
  }

  /**
   * 某个应用的一页运行记录，按 {@code startedAt} <strong>倒序</strong>（新的在前）。
   *
   * <p>★ 从没有过记录 ⇒ <strong>200 + 空列表 + total 0</strong>，既不是 404 也不是 null： 「这个应用还没跑过」是正常状态；404
   * 的语义是"这个应用不存在"，把空历史说成 404 会让人去查"应用是不是被删了"。
   *
   * @param status 可选，只看 {@code SUCCEEDED} / {@code FAILED}；给别的值 ⇒ 400（而不是静默返回空列表 —— 那会被读成"没有记录"）
   * @throws BusinessException 404 应用不存在；400 状态取值不在契约里
   */
  @Transactional(readOnly = true)
  public PageResult<View> page(UUID assistantId, String status, Integer page, Integer size) {
    // ★ 先判应用：应用不存在时，任何分页/状态参数都不该被求值（否则 400 会把 404 盖掉）
    drafts.requireAssistant(assistantId);
    String normalizedStatus = normalizeStatus(status);
    int safePage = page == null || page < 1 ? 1 : page;
    int safeSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    long total = records.count(assistantId, normalizedStatus);
    List<View> rows =
        records.page(assistantId, normalizedStatus, safePage, safeSize).stream()
            .map(this::view)
            .toList();
    return PageResult.of(rows, total, safePage, safeSize);
  }

  /**
   * 状态筛选归一：空白当"不筛"，非法值当场拒绝。
   *
   * <p>★ 为什么不把非法值当"没有匹配"：界面传错一个值（例如老版本的 {@code RUNNING}）时，空列表与"这个应用没跑过" 长得一模一样 ——
   * 用户会去排查看不出任何异常的页面。400 让它当场说出来。
   */
  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String trimmed = status.strip();
    if (!ALLOWED_STATUS.contains(trimmed)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "运行记录状态取值不合法：" + trimmed,
          "只能是 SUCCEEDED 或 FAILED（运行记录是跑完后的结果账，没有「运行中」这一态）");
    }
    return trimmed;
  }

  /** 行 → 契约视图：{@code nodeTrail} 解析成 JSON 数组（库里存的是原样快照串）。 */
  private View view(WorkflowRecord row) {
    return new View(
        row.id(),
        row.assistantId(),
        row.conversationId(),
        row.input(),
        json.readTree(row.nodeTrail()),
        row.finalOutputPreview(),
        row.status(),
        row.failReason(),
        row.elapsedMs(),
        row.startedAt());
  }

  /**
   * 一条运行记录（契约 {@code WorkflowRecord}）。
   *
   * <p>★ {@code nodeTrail} 是<strong>执行当时</strong>的轨迹快照，不是回查当前图 —— 流程改版后回查会把历史记录的节点名
   * 换成新版的名字，那是在篡改历史（契约注释同口径）。
   *
   * <p>★ {@code elapsedMs} 是整条记录的总耗时，界面<strong>不要</strong>把 trail 的各项加起来：两者口径会漂移（契约注释）。
   *
   * <p>★ {@code finalOutputPreview} 在失败的那次是<strong>空串</strong>（不是 null），{@code failReason} 在成功的那次是
   * {@code null}（不是空串）—— 与契约的 {@code string} / {@code string | null} 逐一对齐。
   */
  public record View(
      UUID id,
      UUID appId,
      UUID conversationId,
      String input,
      JsonNode nodeTrail,
      String finalOutputPreview,
      String status,
      String failReason,
      int elapsedMs,
      Instant startedAt) {}
}
