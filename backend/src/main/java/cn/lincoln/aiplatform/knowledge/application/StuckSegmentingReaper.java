package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code SEGMENTING} 超期回收（{@code #303}）—— 卡死的份自愈成可重试，不再永远停在「分段中」。
 *
 * <h3>★ 卡死的机理（{@code #303} 取证）</h3>
 *
 * <p>池=2、20 份并发上传时 8 份卡在 {@code SEGMENTING} 十几分钟不动：{@code updated_at} 全停、 {@code pg_stat_activity}
 * 里 {@code active=0/idle=2} —— <b>没有任何线程在跑</b>。机理是两段连环：任务线程在资源不足中死掉（或死在 {@code failParse}
 * 自己也拿不到连接的那一步，{@link ParseRunner#run} 已对后者留痕），状态回写永远不会再发生，而系统里没有任何东西会再看它一眼。
 *
 * <h3>★ 为什么是「定时扫描 + 在跑登记表」，而不是任务内超时</h3>
 *
 * <ol>
 *   <li><b>任务内超时（{@code future.get(timeout)} / {@code orTimeout}）对已死的任务无效</b>：卡死的机理就是"任务已经没了"，
 *       没有人在等那个 future；等 future 的人自己也是个虚拟线程，一起死。
 *   <li><b>任务内超时会误杀慢跑</b>：超时一到就 {@code cancel}，把正在跑的份打断 —— 那比卡住更坏（一份本来能成功的文档变成失败）。
 *       <b>「卡住」和「慢」是两件事</b>：判死的依据不能是"跑得久"，而是"<b>没有人在跑</b>"。
 *   <li><b>启动自检只扫一次不够</b>：进程不重启，运行期新增的卡死就永远没人管；卡死会持续累积。
 * </ol>
 *
 * <p>⇒ 选型：<b>定时扫描</b>（覆盖运行期）+ <b>在跑登记表</b>（{@link
 * ParseRunner#isInFlight}，区分卡/慢的唯一依据）。超期且<b>无主</b>才回收；超期但在登记表里 ⇒ 只记「仍在处理（慢，不判死）」的 info
 * 日志，让"慢"在读数上可见而不被判死。
 *
 * <h3>★ 为什么按 {@code updatedAt} 判超期</h3>
 *
 * <p>活着的解析任务每页都回写进度（{@code reportOcrPage}）⇒ {@code updatedAt} 一直往前走。
 * <b>停摆</b>才是可疑信号；"创建久"只说明它是份大文件（慢）。
 *
 * <h3>★ 已知边界（如实登记）</h3>
 *
 * <ul>
 *   <li><b>只扫 {@code SEGMENTING}，不扫 {@code PENDING_PARSE}</b>：两者的卡死机理不同 —— 这里是「任务已创建、中途死掉」 （{@code
 *       updatedAt} 停摆）；{@code PENDING_PARSE} 的卡死是「任务<em>从未创建</em>」（{@code #304} 机理：{@link
 *       ParseRunner#onUploaded} 曾是事件监听器，池满时 {@code markProcessing} 开新事务抛 {@code
 *       CannotCreateTransactionException}，异常在代理外层、没进方法体 ⇒ 解析任务从未创建）。按 {@code updatedAt} 判分不开
 *       「无字节的正常等待」与「任务没起来」（content 不落库，两者创建时刻同值）。★ {@code #304} 已修：任务排队挪进纯内存的 {@code afterCommit}
 *       回调（见 {@link ParseRunner#onUploaded}，把发生面压到"排队这一步自己也失败"）；那类卡死由 {@link
 *       StuckPendingParseReaper} 按 {@code parse_expected_at} 超期回收，失败文案「解析任务未能启动」与本类的 「处理任务已中断」区分。
 *   <li><b>多实例部署登记表不跨进程</b>：另一个实例正在跑的份，本实例看不见，会被判死回收。单实例部署（当前形态）无此问题； 多实例需要把登记表换成共享存储（如 {@code
 *       pg_try_advisory_lock} 或心跳列），到时与 {@code #73} 的指标一起做。
 *   <li><b>回收与任务收尾有极小竞态窗口</b>：读后判、判后写之间任务恰好完成 ⇒ 一份成功的文档被标成 SEGMENT_FAILED。 窗口是毫秒级（回收路径里每份只做一次
 *       read-modify-write），后果是用户点一次重试（{@code SEGMENT_FAILED → SEGMENTING} 合法）。 用悲观锁关掉这个窗口不值得 ——
 *       它给所有解析路径加锁。
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
    name = "app.ingestion.segmenting-reaper.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class StuckSegmentingReaper {

  private static final Logger log = LoggerFactory.getLogger(StuckSegmentingReaper.class);

  /** 资源不足时的 hint —— 与 {@code DocumentService.failParse} 同一口径：不说文件有问题。 */
  private static final String REAP_HINT = "服务繁忙或处理中断，本次解析已超时，与文件内容无关，请重试；若多次超时请联系管理员";

  private final DocumentRepository documents;
  private final ParseRunner runner;
  private final Duration timeout;

  public StuckSegmentingReaper(
      DocumentRepository documents,
      ParseRunner runner,
      @Value("${app.ingestion.segmenting-timeout-minutes:10}") long timeoutMinutes) {
    this.documents = documents;
    this.runner = runner;
    // ★ 下限 1 分钟：配 0 会让刚转「分段中」的份立刻被判死 —— 那是把所有解析都误杀成超时。
    this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
  }

  /**
   * 扫一遍超期无主的 {@code SEGMENTING}，回收成可重试。
   *
   * <p>fixedDelay 而不是 fixedRate：一轮扫描不该与上一轮重叠（库里卡死多时扫描本身可能变慢）； 间隔可配（{@code
   * app.ingestion.segmenting-reaper.interval-ms}），默认 1 分钟 ⇒ 一份卡死的文档最坏 {@code timeout + 1 分钟} 被回收。
   */
  @Scheduled(
      fixedDelayString = "${app.ingestion.segmenting-reaper.interval-ms:60000}",
      initialDelayString = "${app.ingestion.segmenting-reaper.interval-ms:60000}")
  public void reap() {
    Instant cutoff = Instant.now().minus(timeout);
    for (Document stale : documents.findStaleByStatus(DocumentStatus.SEGMENTING, cutoff)) {
      if (runner.isInFlight(stale.id())) {
        // ★ 「卡住」和「慢」是两件事：有活人管 ⇒ 什么都不动，只留一条可 grep 的痕迹。
        log.info(
            "分段超期但仍在处理（慢，不判死）：文档 {}（{}）已 {} 无进度 —— 任务在跑/在排队，继续等",
            stale.id(),
            stale.name(),
            Duration.between(stale.updatedAt(), Instant.now()));
        continue;
      }
      reapUnowned(stale);
    }
  }

  /** 回收一份无主的超期文档。 */
  private void reapUnowned(Document stale) {
    try {
      // ★ 再读一次新鲜状态做守卫：读后判、判后写之间任务可能恰好收尾 —— 不用过期结论覆盖现状。
      Document fresh = documents.findById(stale.id()).orElse(null);
      if (fresh == null || fresh.status() != DocumentStatus.SEGMENTING) {
        return;
      }
      String reason = "解析超时：超过 " + timeout.toMinutes() + " 分钟没有任何进度（处理任务已中断），已自动标为失败，可重试";
      fresh.recordParseFailure(reason, REAP_HINT, "");
      documents.save(fresh);
      log.warn(
          "回收卡死的解析：文档 {}（{}）停在分段中超过 {} 无进度且无在跑任务 ⇒ 标记为分段失败（可重试）",
          stale.id(),
          stale.name(),
          timeout);
    } catch (RuntimeException e) {
      // ★ 回收自己也可能被资源不足打断（#303 的机理）。下一轮再试：扫描是幂等的，不急在这一轮。
      log.error("回收卡死解析失败（下一轮重试）：文档 {} —— {}", stale.id(), e.toString());
    }
  }
}
