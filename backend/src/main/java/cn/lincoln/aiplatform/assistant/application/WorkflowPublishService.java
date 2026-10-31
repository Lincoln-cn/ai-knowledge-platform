package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 发布编排版本（{@code #198} 第四块，契约 {@code POST /assistants/{appId}/workflow/publish}）。
 *
 * <p>★ 三步的顺序是<strong>判据</strong>，不是习惯：① 应用存在 ② 校验通过 ③ 才落库。 校验不过时连取号都不做 ——
 * 否则"校验失败"也会在唯一约束的序列里留下痕迹（虽然取号本身不落库，但"先写后验" 的写法迟早会有一个分支把行写进去）。
 *
 * <p>★ 版本号是<strong>乐观取号 + 唯一约束兜底</strong>：{@code coalesce(max(version),0)+1} 只是预期值， 真正的权威是 {@code
 * unique (assistant_id, version)}。冲突时重试<strong>恰好一次</strong>（重新取号），仍冲突就报 409 ——
 * 不静默返回旧版本，因为"我发布成功了"与"你看到的是上一版"在界面上是完全不同的两句话。
 */
@Service
public class WorkflowPublishService {

  /** 图缺失时的空图：交给校验器判，而不是在这里自己拼一句 400（校验器才知道该怎么说）。 */
  private static final String EMPTY_GRAPH_JSON = "{\"nodes\":[],\"edges\":[]}";

  private static final WorkflowValidationService.Graph NO_GRAPH =
      new WorkflowValidationService.Graph(List.of(), List.of());

  private final WorkflowDraftService drafts;
  private final WorkflowValidationService validations;
  private final WorkflowVersionStore store;

  public WorkflowPublishService(
      WorkflowDraftService drafts,
      WorkflowValidationService validations,
      WorkflowVersionStore store) {
    this.drafts = drafts;
    this.validations = validations;
    this.store = store;
  }

  /**
   * 发布一版。
   *
   * @param graphJson 图原文（落库存快照，原样不改写）
   * @param graph 同一张图解析出的校验视图（只含校验要判的字段）
   * @param note 发布说明，可空
   * @return 新版本（契约 {@code WorkflowVersion}）
   * @throws BusinessException 404 应用不存在 / 400 校验不过 / 409 并发发布冲突
   */
  public Published publish(
      UUID assistantId,
      String graphJson,
      WorkflowValidationService.Graph graph,
      String note,
      UUID actorId) {
    drafts.requireAssistant(assistantId);

    WorkflowValidationService.Graph checked = graph == null ? NO_GRAPH : graph;
    WorkflowValidationService.Result result = validations.validate(checked);
    if (!result.passed()) {
      // ★ 400 + 校验明细：不引 assistant 域类型进 platform 的 handler（那会被模块边界闸门抓红），
      //   明细进 message/hint 文本 —— 界面照样能把每条 issue 显示出来。
      throw new BusinessException(ErrorCode.PUBLISH_CHECK_FAILED, summary(result), hint(result));
    }

    int nodeCount = checked.nodes() == null ? 0 : checked.nodes().size();
    String snapshot = graphJson == null || graphJson.isBlank() ? EMPTY_GRAPH_JSON : graphJson;

    try {
      return published(store.append(assistantId, snapshot, note, actorId, nodeCount), nodeCount);
    } catch (DataIntegrityViolationException first) {
      if (!isVersionConflict(first)) {
        // 不是版本号撞车（外键、check 约束…）⇒ 重试一次没有意义，原样抛给全局处理器。
        throw first;
      }
      try {
        return published(store.append(assistantId, snapshot, note, actorId, nodeCount), nodeCount);
      } catch (DataIntegrityViolationException second) {
        if (!isVersionConflict(second)) {
          throw second;
        }
        throw new BusinessException(
            ErrorCode.CONFLICT,
            "发布失败：有人同时在发布这个应用",
            "取号之后撞上了唯一约束（同一版本号已经有人写入），已自动重试一次仍然冲突 —— 说明并发发布还在继续，请稍后重试");
      }
    }
  }

  private static Published published(WorkflowVersion row, int nodeCount) {
    return new Published(
        row.id(), row.version(), row.publishedAt(), row.publishedBy(), row.note(), nodeCount);
  }

  /** 一句话说清"为什么不能发"。只数 ERROR —— 警告不拦发布，混进计数会让人以为要全改完。 */
  private static String summary(WorkflowValidationService.Result result) {
    long errors = result.issues().stream().filter(i -> "ERROR".equals(i.level())).count();
    long warnings = result.issues().size() - errors;
    return "发布前校验没通过：" + errors + " 个错误" + (warnings > 0 ? "（另有 " + warnings + " 个警告，不拦发布）" : "");
  }

  /** 把每条 ERROR 摊开：界面要能直接照着改，而不是回去再点一次「校验」。 */
  private static String hint(WorkflowValidationService.Result result) {
    StringBuilder out = new StringBuilder("按顺序处理这几条再发：");
    for (WorkflowValidationService.Issue issue : result.issues()) {
      if (!"ERROR".equals(issue.level())) {
        continue;
      }
      out.append('[').append(issue.code()).append("] ").append(issue.message());
      if (issue.nodeId() != null) {
        out.append("（节点 ").append(issue.nodeId()).append("）");
      }
      if (issue.hint() != null) {
        out.append(" → ").append(issue.hint());
      }
      out.append('；');
    }
    return out.toString();
  }

  /**
   * 这个异常是不是"版本号撞车"。
   *
   * <p>★ 必须区分：只有唯一约束冲突才值得重试一次。把外键失败也重试，会让一个必然失败的请求多跑一遍； 把外键失败也报成 409，则会告诉用户"稍后重试就好"——而那条路永远好不了。
   */
  private static boolean isVersionConflict(Throwable error) {
    for (Throwable each = error; each != null; each = each.getCause()) {
      String message = each.getMessage();
      if (message == null) {
        continue;
      }
      if (message.contains("uq_workflow_version")
          || message.contains("duplicate key value")
          || message.contains("重复键")
          || message.contains("唯一约束")) {
        return true;
      }
    }
    return false;
  }

  /** 发布结果（契约 {@code WorkflowVersion}）。 */
  public record Published(
      UUID id, int version, Instant publishedAt, UUID publishedBy, String note, int nodeCount) {}
}
