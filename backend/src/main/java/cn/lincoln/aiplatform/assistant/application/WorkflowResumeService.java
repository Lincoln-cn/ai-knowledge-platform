package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowPause;
import cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 从暂停点恢复一次执行。
 *
 * <p>★ 它要回答的问题：一个停在「提问 / 表单」节点的执行，拿到用户的补充信息之后能不能接着跑完。
 *
 * <p>★ 与 {@link WorkflowDebugService} 是一对：那个负责「停下来并存住」，这个负责「读回来接着跑」。
 * 分成两个类而不是一个：它们的输入不同（一个是图与问题、一个是执行 id 与答案）， 硬塞进一个类会让「恢复」必须依赖「编译」的全部参数。
 */
@Service
public class WorkflowResumeService {

  private final WorkflowPauseRepository pauses;
  private final WorkflowRecordRepository records;
  private final LinearWorkflowExecutor executor;
  private final WorkflowCompiler compiler;
  private final NodeTypeCatalog catalog;

  public WorkflowResumeService(
      WorkflowPauseRepository pauses,
      WorkflowRecordRepository records,
      LinearWorkflowExecutor executor,
      NodeTypeCatalog catalog) {
    this.pauses = pauses;
    this.records = records;
    this.executor = executor;
    this.catalog = catalog;
    this.compiler = new WorkflowCompiler(catalog);
  }

  /**
   * 结果。
   *
   * <p>★ 四档而不是两档：{@code notFound} 与 {@code rejected} 是「这次恢复根本没开始」， 而 {@code ok=false}
   * 是「开始了但跑挂了」。调用方对它们的处置不同 —— 前者让用户刷新， 后者要让用户看图。
   */
  public record Result(
      boolean ok,
      boolean notFound,
      List<String> errors,
      String finalOutput,
      int elapsedMs,
      UUID executionId,
      boolean pausedAgain,
      String pausedAtNode) {

    static Result notFoundResult() {
      return new Result(false, true, List.of("这次执行已经不存在了，或者已经被恢复过"), null, 0, null, false, null);
    }

    static Result rejectedResult(List<String> errors) {
      return new Result(false, false, errors, null, 0, null, false, null);
    }
  }

  /**
   * 接着跑。
   *
   * <p>★ 三个前置条件都必须在**开跑之前**查完，不能让「跑了一半才发现图改过」这种事发生：
   *
   * <ol>
   *   <li>暂停快照还在
   *   <li>它还没被恢复过
   *   <li><b>当前的图与暂停时是同一份计划</b> —— 用 {@code stepsDone} 比：暂停时走了 N 步， 现在编译出来的计划第 N
   *       步必须仍存在，**且仍是快照里那个中断点**（{@code #338}：只比步数等于「位置还在」，比不出「那个位置换了节点」）。 图被改过之后同一个 N 会指向另一个节点，
   *       硬跑等于把状态灌进一张陌生的图。
   * </ol>
   *
   * <p>★ 恢复点传的是 {@code stepsDone + 1}：暂停点那一步就是中断点本身，用户这一次的答复把它「走完」了。 传 {@code stepsDone}
   * 原值会让执行器从同一个中断点重来，答复永远到不了下游。
   */
  public Result resume(
      UUID assistantId, UUID executionId, WorkflowCompiler.Graph graph, String answer) {
    Optional<WorkflowPause> found = pauses.find(executionId);
    if (found.isEmpty()) {
      return Result.notFoundResult();
    }
    WorkflowPause pause = found.get();
    if (!pause.waiting()) {
      // 已经恢复过 ⇒ 同一份快照不许用第二次：那会让一次执行被算成两次。
      return Result.rejectedResult(List.of("这次执行已经恢复过了，不能重复恢复"));
    }
    if (!pause.assistantId().equals(assistantId)) {
      // 快照属于另一个应用 ⇒ 换一张图去接着跑它，等于把 A 的变量灌进 B。
      return Result.rejectedResult(List.of("这次执行不属于当前应用"));
    }

    WorkflowCompiler.CompileResult compiled = compiler.compile(graph);
    if (!compiled.ok()) {
      return Result.rejectedResult(compiled.errors());
    }
    List<WorkflowCompiler.PlanStep> steps = compiled.plan().steps();
    if (pause.stepsDone() >= steps.size()) {
      return Result.rejectedResult(
          List.of(
              "图改过了：这次执行停在走了 "
                  + pause.stepsDone()
                  + " 步的位置，而当前这张图只有 "
                  + steps.size()
                  + " 步。"
                  + "换一份计划去接着跑会把状态灌进一张陌生的图，所以拒绝。"));
    }
    // ★ 第 stepsDone 步必须仍是快照里那个中断点（#338）：比步数只证明「位置还在」，不证明「那个位置还是
    //   同一个节点」—— 图被改过之后步数相同、节点不同是常有的事，而那样接下去跑等于把答复灌给另一个节点。
    WorkflowCompiler.PlanStep interruptStep = steps.get(pause.stepsDone());
    if (!interruptStep.nodeId().equals(pause.nodeId())) {
      return Result.rejectedResult(
          List.of(
              "图改过了：这次执行停在第 "
                  + pause.stepsDone()
                  + " 步「"
                  + pause.nodeId()
                  + "」上，而当前这张图那一步是「"
                  + interruptStep.nodeId()
                  + "」。换一份计划接着跑会把状态灌进一张陌生的图，所以拒绝。"));
    }

    FlowState state = new FlowState();
    pause.state().forEach((k, v) -> state.write(k, v, KeyUpdate.REPLACE));
    // ★ 把用户这次的回答也灌进去 —— 中断点要的就是它。键名与 QUESTION_NODE 的约定一致（见下）。
    String key = answerKey(pause.nodeType());
    if (key != null && answer != null && !answer.isBlank()) {
      state.write(key, answer, KeyUpdate.REPLACE);
    }

    Instant began = Instant.now();
    String failReason = null;
    String output = null;
    boolean pausedAgain = false;
    String pausedAt = null;
    try {
      // ★ +1：暂停点那一步就是中断点本身，用户这一次的答复把它「走完」了。
      //   传 stepsDone 原值会让执行器从同一个中断点重来 ⇒ 立刻又抛一次 WorkflowInterrupted，
      //   答复永远到不了下游（#338：原来那四条测试只覆盖「还没开始跑就被挡下」的路径，
      //   所以这条一直没被发现 —— 恢复一次，图上什么都不会发生，只是又多了一张暂停快照）。
      FlowState finished =
          executor.resumeAndReturn(
              executionId, compiled.plan(), answer, pause.stepsDone() + 1, state);
      output = finished.read("reply");
    } catch (WorkflowInterrupted again) {
      // 图里还有第二个人工介入节点 ⇒ 停在新位置，把新快照覆盖上去（同一次执行）。
      pausedAgain = true;
      pausedAt = again.nodeId();
      pauses.save(
          new WorkflowPause(
              executionId,
              assistantId,
              again.nodeId(),
              again.nodeType(),
              again.stateSnapshot(),
              again.stepsDone(),
              Instant.now(),
              null));
    } catch (RuntimeException ex) {
      failReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    int elapsedMs = (int) Duration.between(began, Instant.now()).toMillis();
    records.save(
        new WorkflowRecord(
            UUID.randomUUID(),
            assistantId,
            null,
            answer,
            "[]",
            output == null ? "" : output,
            pausedAgain ? "PAUSED" : (failReason == null ? "SUCCEEDED" : "FAILED"),
            failReason,
            elapsedMs,
            began,
            Instant.now()));

    // ★ 只有「跑完了」才标 resumed；「又停了」要留着 waiting 让下一次恢复能继续用它。
    if (!pausedAgain) {
      pauses.save(pause.resumed(Instant.now()));
    }

    return new Result(
        failReason == null && !pausedAgain,
        false,
        failReason == null ? null : List.of(failReason),
        output,
        elapsedMs,
        executionId,
        pausedAgain,
        pausedAt);
  }

  /**
   * 中断点的回答写进哪个键。
   *
   * <p>★ 两类节点各一个键，且必须是**下游真会读**的那个：{@code QUESTION_NODE} 的下游用 {@code query}
   * （与入口输入同一格，因为「追问」在语义上就是"把问题换成一个更具体的"）；{@code FORM_NODE} 用 {@code form_data}（一组字段，而不是一个问题）。
   *
   * <p>未知类型返回 {@code null} —— 不硬塞一个没人读的键。
   */
  static String answerKey(String nodeType) {
    if ("QUESTION_NODE".equals(nodeType)) {
      return "query";
    }
    if ("FORM_NODE".equals(nodeType)) {
      return "form_data";
    }
    return null;
  }

  /** 单入口图的起点：一次恢复只带一个答案。 */
  static final Set<String> KNOWN_TYPES = Set.of("QUESTION_NODE", "FORM_NODE");

  /** 便于测试：把快照读成一份普通 map。 */
  static Map<String, String> snapshotOf(WorkflowPause pause) {
    return pause.state();
  }
}
