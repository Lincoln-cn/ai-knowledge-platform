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
 * {@code PENDING_PARSE} 超期回收（{@code #304}）—— 解析任务<strong>从未创建</strong>的那一类卡死的兜底闸门。
 *
 * <h3>★ 与 {@code #303}（{@link StuckSegmentingReaper}）的分工：「任务没起来」vs「任务中途死」</h3>
 *
 * <p>#303 的回收器扫 {@code SEGMENTING}（任务已创建、中途死掉，{@code updated_at} 停摆）；本类扫 {@code PENDING_PARSE} 里
 * <b>期望解析却迟迟没等到任务</b>的那一类 —— 任务从未存在，没有进度可停摆，只能按"期望开始的时刻"判。两类的失败原因文案也不同（用户要能读出区别）： #303
 * 写「处理任务已中断」，本类写「解析任务未能启动」。
 *
 * <h3>★ 机理（{@code #304}，javap 取证）</h3>
 *
 * <p>{@link ParseRunner#onUploaded} 曾是 {@code @TransactionalEventListener(AFTER_COMMIT)}：Modulith 的
 * {@code CompletionRegisteringAdvisor} 在 {@code proceed()} 之前先开<b>新事务</b>登记事件（{@code
 * markProcessing}）—— 连接池满时 {@code CannotCreateTransactionException} 抛在代理外层，方法体从未执行 ⇒
 * 解析任务从未创建、{@code failParse} 无从谈起， 文档永远停在 {@code PENDING_PARSE}（界面"解析中"无限转圈）。主修是把任务排队挪进纯内存的 {@code
 * afterCommit} 回调 （不经事件登记代理链，见 {@link ParseRunner#onUploaded}）；本类是<b>外部视角的兜底</b> —— 就算"排队"这一步自己也失败，
 * 文档也不会永久卡住。
 *
 * <h3>★ 为什么按 {@code parse_expected_at} 判超期，以及它怎么区分三类</h3>
 *
 * <p>扫的是 {@code status = PENDING_PARSE} ∧ {@code parse_expected_at} 非空且早于阈值（{@code
 * JpaDocumentRepository#findStalePendingParse}）：
 *
 * <ol>
 *   <li><b>有字节、期望解析</b>（{@code parse_expected_at} 非空）⇒ 超期且无在跑任务 = 任务没起来，本类判成可重试的失败；
 *   <li><b>只登记元数据（无字节）</b>（{@code parse_expected_at} 为 {@code null}）⇒ 永远停在 {@code PENDING_PARSE}
 *       是<strong>正常</strong>的（等字节）， 查询的 {@code is not null} 条件把它们排除在外，绝不会被误杀；
 *   <li><b>已开始解析</b>（状态已离开 {@code PENDING_PARSE}）⇒ 与本类无关，由 #303 那套按 {@code updatedAt} 停摆管。
 * </ol>
 *
 * <p>★ "刚上传还在等"与"等了很久没人管"由阈值分开（{@code app.ingestion.pending-parse-timeout-minutes}，默认 2 分钟 ——
 * 事件投出到任务排队是毫秒级，2 分钟已是百倍余量）；排队等闸门许可的任务在 {@link ParseRunner#isInFlight} 登记表里，
 * 超期只记「慢，不判死」日志（「卡住」和「慢」是两件事，同 #303 口径）。
 *
 * <h3>★ 已知边界（如实登记）</h3>
 *
 * <ul>
 *   <li><b>多实例部署登记表不跨进程</b>：与 #303 同一个边界 —— 另一实例正在跑的份本实例看不见。单实例部署（当前形态）无此问题。
 *   <li><b>回收与任务起跑有极小竞态窗口</b>：读后判、判后写之间任务恰好起跑 ⇒ 一份即将开始的文档被标成 {@code SEGMENT_FAILED}。
 *       窗口毫秒级，后果是用户点一次重试（{@code SEGMENT_FAILED → SEGMENTING} 合法）。判据代价不对称：误杀一次重试 vs 永久卡死。
 *   <li><b>方案 3（事件投递至少一次）不在本类范围</b>：Modulith 的 event publication registry 只对事务性监听器登记， 且"连接满时 {@code
 *       markProcessing} 自己就抛"—— 现状本来就不是至少一次。真正做至少一次要动 {@code spring.modulith.events} 配置与补登记/重投逻辑，超出
 *       #304 写面，由父代理记账。
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
    name = "app.ingestion.pending-parse-reaper.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class StuckPendingParseReaper {

  private static final Logger log = LoggerFactory.getLogger(StuckPendingParseReaper.class);

  /**
   * 判死后的下一步建议 —— 与 {@code DocumentService.failParse} 同一口径：资源类失败与文件内容无关。
   *
   * <p>★ 文案与 #303 的「…本次解析已超时…」刻意不同：那边是"任务跑过但中断了"，这边是"任务根本没起来"。
   */
  private static final String REAP_HINT = "服务繁忙，解析任务没有启动起来，与文件内容无关，请重试；若多次失败请联系管理员";

  private final DocumentRepository documents;
  private final ParseRunner runner;
  private final Duration timeout;

  public StuckPendingParseReaper(
      DocumentRepository documents,
      ParseRunner runner,
      @Value("${app.ingestion.pending-parse-timeout-minutes:2}") long timeoutMinutes) {
    this.documents = documents;
    this.runner = runner;
    // ★ 下限 1 分钟（同 #303）：配 0 会把刚投出事件、还没来得及排队的份立刻判死。
    this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
  }

  /**
   * 扫一遍"期望解析、超期仍无任务"的 {@code PENDING_PARSE}，回收成可重试。
   *
   * <p>fixedDelay 而不是 fixedRate（同 #303）；间隔可配（{@code
   * app.ingestion.pending-parse-reaper.interval-ms}）， 默认 1 分钟 ⇒ 一份卡死的文档最坏 {@code timeout + 1 分钟}
   * 被回收。
   */
  @Scheduled(
      fixedDelayString = "${app.ingestion.pending-parse-reaper.interval-ms:60000}",
      initialDelayString = "${app.ingestion.pending-parse-reaper.interval-ms:60000}")
  public void reap() {
    reap(Instant.now());
  }

  /**
   * 扫一轮（{@code now} 可注入）—— 测试传未来时刻验证"超期即判死"，不用真等阈值。
   *
   * @param now 当前时刻（截止时刻 = {@code now - timeout}）
   */
  void reap(Instant now) {
    Instant cutoff = now.minus(timeout);
    try {
      for (Document stale : documents.findStalePendingParse(DocumentStatus.PENDING_PARSE, cutoff)) {
        if (runner.isInFlight(stale.id())) {
          // ★ 「卡住」和「慢」是两件事（同 #303）：排队等闸门许可的任务也算"有活人管"，不判死。
          log.info(
              "待解析超期但任务在途（慢，不判死）：文档 {}（{}）期望 {} 起解析 —— 任务在跑/在排队，继续等",
              stale.id(),
              stale.name(),
              stale.parseExpectedAt());
          continue;
        }
        reapUnowned(stale);
      }
    } catch (RuntimeException e) {
      // ★ 扫描自己也可能被资源不足打断（#303 的机理）。下一轮再试：扫描是幂等的。
      log.error("回收「待解析」卡死失败（下一轮重试）：{}", e.toString());
    }
  }

  /** 回收一份"任务从未起来"的超期文档。 */
  private void reapUnowned(Document stale) {
    try {
      // ★ 再读一次新鲜状态做守卫：读后判、判后写之间任务可能恰好起跑 —— 不用过期结论覆盖现状。
      Document fresh = documents.findById(stale.id()).orElse(null);
      if (fresh == null || fresh.status() != DocumentStatus.PENDING_PARSE) {
        return;
      }
      String reason = "解析任务未能启动：服务繁忙（数据库连接池被占满）时解析在启动前失败，任务从未开始" + " —— 与文件内容无关，已自动标为失败，可重试";
      fresh.recordParseFailure(reason, REAP_HINT, "");
      documents.save(fresh);
      log.warn(
          "回收未启动的解析：文档 {}（{}）期望 {} 起解析，超过 {} 仍无任务 ⇒ 标记为分段失败（可重试）",
          stale.id(),
          stale.name(),
          stale.parseExpectedAt(),
          timeout);
    } catch (RuntimeException e) {
      // ★ 回收自己也可能被资源不足打断。下一轮再试：扫描是幂等的，不急在这一轮。
      log.error("回收「待解析」卡死失败（下一轮重试）：文档 {} —— {}", stale.id(), e.toString());
    }
  }
}
