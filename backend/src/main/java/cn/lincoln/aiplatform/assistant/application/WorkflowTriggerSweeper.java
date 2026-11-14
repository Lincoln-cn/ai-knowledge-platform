package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 定时触发的扫描与执行（{@code #295} / 0.7.3）。
 *
 * <p>★ <strong>一次扫描只做一件确定的事</strong>：找到期 → 抢 → 跑。三步各自可失败、互不吞掉，
 * 也不在一个大事务里（跑图要调外部模型，把数据库连接按秒甚至按分钟占着是另一类故障）。
 *
 * <h3>★ 三个刻意接受的设计代价</h3>
 *
 * <ul>
 *   <li><b>停机跨过的周期只补跑一次</b>：{@code 0 9 * * *} 停了三天之后，正确答案是"现在跑一次 + 记下
 *       错过了两次"，不是把三天的都补跑。补跑会在启动瞬间起三倍的模型调用，而那三天的结果对用户早已过期。
 *   <li><b>失败不立即重试</b>：{@code trigger_next_at} 在抢占时就顺延了，所以一张坏图是每天失败一次， 而不是每秒。失败会落一行 {@code FAILED}
 *       记录 —— 那才是给人看的信号，重试不是。
 *   <li><b>不按用户身份跑，按应用所有者的身份跑</b>：定时触发没有"当前用户"。检索的权限范围由 {@code ConversationService}
 *       取会话所属者，而这里根本没有会话 —— 所以可视化编排里的检索节点 在定时路径上用的是应用所有者的范围。这不是疏漏，是"没有人的请求"这件事的必然结果，写在 {@code
 *       ROADMAP §11.42.3} 的已知边界里。
 * </ul>
 */
@Service
public class WorkflowTriggerSweeper {

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowTriggerSweeper.class);

  /** 单轮最多处理几个到期的应用 —— 停机很久后可能有几十个同时到期，一次全跑会同时起一堆模型调用。 */
  private static final int BATCH = 20;

  /** "错过几次"的计数上限：停机三年以上时，"很多"与"精确值"对用户没有区别。 */
  private static final int MISSED_COUNT_LIMIT = 5000;

  /** 定时触发没有入口输入。★ 用空串而不是占位文案：占位文案会进流程变量、被模型当成问题。 */
  private static final String NO_INPUT = "";

  private final AssistantRepository assistants;
  private final WorkflowVersionRepository versions;
  private final NodeTypeCatalog catalog;
  private final WorkflowRunRecorder recorder;

  /** JSON 读入只用于把 {@code as_workflow_version.graph} 还原成图对象；用项目统一的配置。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public WorkflowTriggerSweeper(
      AssistantRepository assistants,
      WorkflowVersionRepository versions,
      NodeTypeCatalog catalog,
      WorkflowRunRecorder recorder) {
    this.assistants = assistants;
    this.versions = versions;
    this.catalog = catalog;
    this.recorder = recorder;
  }

  /**
   * 扫一轮。
   *
   * @return 这一轮真的跑起来的应用数（0 是常态，不是异常）
   */
  public int sweep() {
    return sweepAt(Instant.now());
  }

  /**
   * 扫一轮（时刻由调用方给）。
   *
   * <p>★ 拆出 {@code now} 参数是为了让"错过了几轮"这类判断可以被<strong>确定地</strong>测到 —— 若这里自己取 {@code
   * Instant.now()}，测试只能算出一个大概数，然后被迫把断言写成范围。
   *
   * <p>★ {@code public} 是为了跨包可测（测试在 {@code cn.lincoln.aiplatform.assistant}，本类在 {@code
   * …assistant.application}）；生产调用方只有 {@link #sweep()}。
   */
  public int sweepAt(Instant now) {
    List<Assistant> due = assistants.findDueTriggers(now, BATCH);
    if (due.isEmpty()) {
      return 0;
    }
    int ran = 0;
    for (Assistant assistant : due) {
      try {
        if (triggerOne(assistant, now)) {
          ran++;
        }
      } catch (RuntimeException ex) {
        // ★ 一个应用触发失败不能带停整轮：下一轮它还会到期，而其它应用不该被它连累。
        LOG.warn("#定时触发 处理失败（assistant={}）：{}", assistant.id(), ex.toString());
      }
    }
    return ran;
  }

  /** 抢一次并跑；返回是否真的跑了。 */
  private boolean triggerOne(Assistant assistant, Instant now) {
    String cron = assistant.triggerCron();
    Instant expected = assistant.triggerNextAt();
    if (cron == null || expected == null) {
      // 库里有 chk_as_assistant_trigger_cron 兜着，走到这里说明有人绕过实体直接写了库。
      LOG.warn("#定时触发 半配置（assistant={}，cron={}，nextAt={}），跳过", assistant.id(), cron, expected);
      return false;
    }

    Optional<Instant> next = TriggerCron.next(cron, now);
    if (next.isEmpty()) {
      // 「这个表达式从此不再有下一次」不是错误，是 cron 本身的语义（例如只在某个月份生效）。
      // 保持原状等人工改，而不是偷偷把触发器关掉 —— 关掉之后界面上就看不出它曾经配过。
      LOG.warn("#定时触发 不再有下一次（assistant={}，cron={}），本轮跳过", assistant.id(), cron);
      return false;
    }

    int missed = missedPeriods(cron, expected, now);
    if (!assistants.claimTrigger(assistant.id(), expected, now, next.get(), missed)) {
      // 别人先改了（多副本、或用户刚改了 cron）。不抢、不跑 —— 下一轮用新值。
      return false;
    }

    runGraph(assistant.id(), now);
    return true;
  }

  /**
   * 服务停机跨过了几个周期。
   *
   * <p>★ <strong>逐次推进而不是用平均周期做除法</strong>：{@code 0 9 * * 1-5} 的"周期"是 24 小时还是 72 小时取决于今天星期几 ——
   * 除法在跨周末时会把"错过 1 轮"算成"错过 3 轮"。
   *
   * <p>计数从 {@code expected} <strong>严格之后</strong>开始，所以它本身就是"漏掉的次数"： {@code expected}
   * 那一次不算漏，因为这一轮跑的就是它（只是迟到了）。
   */
  private int missedPeriods(String cron, Instant expected, Instant now) {
    return TriggerCron.countBetween(cron, expected, now, MISSED_COUNT_LIMIT);
  }

  /** 跑这个应用的最新已发布版本。 */
  private void runGraph(UUID assistantId, Instant startedAt) {
    Optional<WorkflowVersion> published = versions.history(assistantId).stream().findFirst();
    if (published.isEmpty()) {
      recorder.recordFailure(assistantId, NO_INPUT, "这个应用还没有已发布版本，定时触发没有东西可跑", startedAt, "CRON");
      return;
    }

    WorkflowCompiler.Graph graph;
    try {
      graph = JSON.readValue(published.get().graph(), WorkflowCompiler.Graph.class);
    } catch (RuntimeException ex) {
      recorder.recordFailure(
          assistantId, NO_INPUT, "已发布版本的图读不出来：" + ex.getMessage(), startedAt, "CRON");
      return;
    }

    WorkflowCompiler.CompileResult compiled = new WorkflowCompiler(catalog).compile(graph);
    if (!compiled.ok()) {
      // ★ 发布时校验过的图仍可能编不过：校验用的是发布那一刻的目录，而目录会变
      //   （新增节点的执行器还没装配、或某个类型被下线）。这种情况必须留下记录，不能静默不跑。
      recorder.recordFailure(
          assistantId,
          NO_INPUT,
          "已发布版本的图编不过：" + String.join("；", compiled.errors()),
          startedAt,
          "CRON");
      return;
    }

    recorder.run(assistantId, compiled.plan(), NO_INPUT, null, "CRON");
  }
}
