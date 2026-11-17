package cn.lincoln.aiplatform.analytics.application;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「风险提示」的只读体检（`#354`）。
 *
 * <h3>★ 它不是告警系统</h3>
 *
 * <p>`#73` 记的是"零指标零告警零配额"这笔账：真正的告警要有时序库、阈值管理、通知渠道与静默期。 这一页只回答"现在有什么值得看一眼"，<b>不推送、不落历史、不判 SLA</b>。
 * 写清这一点是因为"看到有风险提示页 ⇒ 以为有人盯着"比没有这一页更危险。
 *
 * <h3>为什么只有四条</h3>
 *
 * <p>四条都是<b>读一下就知道</b>的：磁盘、堆、慢回答、失败回答。要读时序数据的（QPS 趋势、错误率拐点） 得先有 `#73` 那套东西，这里硬做出来就是假读数。
 */
@Service
public class RiskService {

  /** 慢回答阈值：10 秒。与 `B4 首字延迟 ≤10s` 同一条线，超过它用户已经在怀疑"是不是卡住了"。 */
  private static final long SLOW_ANSWER_MS = 10_000L;

  /** 磁盘告警线。85% 是"还来得及搬数据"的位置，95% 是"已经在丢写入了"。 */
  private static final double DISK_WARN = 0.85;

  private static final double DISK_CRITICAL = 0.95;

  /** 堆使用率告警线。JVM 自己会在接近满时频繁 GC，85% 已是可观察的征兆。 */
  private static final double HEAP_WARN = 0.85;

  private final JdbcTemplate jdbc;

  /** 文件存储所在目录 —— 磁盘读数取它所在的分区，而不是 JVM 的工作目录（两者可能不是同一个盘）。 */
  private final String storageRoot;

  public RiskService(JdbcTemplate jdbc, @Value("${app.file.root:./data}") String storageRoot) {
    this.jdbc = jdbc;
    this.storageRoot = storageRoot;
  }

  /** 跑一遍体检。 */
  @Transactional(readOnly = true)
  public Snapshot snapshot() {
    List<Item> items = new ArrayList<>();
    items.add(disk());
    items.add(heap());
    items.add(slowAnswers());
    items.add(failedAnswers());
    return new Snapshot(Instant.now(), items);
  }

  private Item disk() {
    File path = new File(storageRoot);
    // ★ 目录可能还不存在（第一次部署、还没上传过文件）。向上找到存在的那一级再读 ——
    //   这里绝不能因为"目录不在"就报 CRITICAL：磁盘没满，只是还没建。
    File probe = path.getAbsoluteFile();
    while (probe != null && !probe.exists()) {
      probe = probe.getParentFile();
    }
    if (probe == null) {
      return new Item("disk", "磁盘", Level.UNKNOWN.name(), "—", "读不到存储路径所在的分区：" + storageRoot, null);
    }
    long total = probe.getTotalSpace();
    long usable = probe.getUsableSpace();
    if (total <= 0) {
      return new Item("disk", "磁盘", Level.UNKNOWN.name(), "—", "分区大小读不出来：" + probe, null);
    }
    double used = 1.0 - (double) usable / (double) total;
    String level =
        used >= DISK_CRITICAL
            ? Level.CRITICAL.name()
            : used >= DISK_WARN ? Level.WARN.name() : Level.OK.name();
    String hint = used >= DISK_WARN ? "先清 `_archive/` 与旧向量表，再考虑扩盘；数据库就在这个分区上，写满会直接拒写" : null;
    return new Item(
        "disk",
        "磁盘",
        level,
        percent(used),
        "可用 " + gb(usable) + " / 共 " + gb(total) + "（" + probe + "）",
        hint);
  }

  private Item heap() {
    MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long max = usage.getMax();
    long used = usage.getUsed();
    if (max <= 0) {
      return new Item("heap", "内存（JVM 堆）", Level.UNKNOWN.name(), "—", "堆上限读不出来", null);
    }
    double ratio = (double) used / (double) max;
    String level = ratio >= HEAP_WARN ? Level.WARN.name() : Level.OK.name();
    return new Item(
        "heap",
        "内存（JVM 堆）",
        level,
        percent(ratio),
        "已用 " + mb(used) + " / 上限 " + mb(max),
        ratio >= HEAP_WARN ? "近满时会频繁 GC，表现为整体变慢；先看是不是某个批处理在跑" : null);
  }

  private Item slowAnswers() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.role = 'ASSISTANT' and m.duration_ms > ?"
                + " and m.created_at >= ?",
            Long.class,
            SLOW_ANSWER_MS,
            Timestamp.from(Instant.now().minusSeconds(86_400)));
    long n = count == null ? 0 : count;
    return new Item(
        "slow-answers",
        "慢回答（近 24 小时）",
        n > 0 ? Level.WARN.name() : Level.OK.name(),
        n + " 条",
        "耗时超过 " + (SLOW_ANSWER_MS / 1000) + " 秒的回答",
        n > 0 ? "先看重排窗口（默认 10）与检索模式：重排窗口 10 在全量读数里是 8.5 秒" : null);
  }

  private Item failedAnswers() {
    Long count =
        jdbc.queryForObject(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.role = 'ASSISTANT' and m.status <> 'SUCCEEDED'"
                + " and m.created_at >= ?",
            Long.class,
            Timestamp.from(Instant.now().minusSeconds(86_400)));
    long n = count == null ? 0 : count;
    return new Item(
        "failed-answers",
        "失败回答（近 24 小时）",
        n > 0 ? Level.WARN.name() : Level.OK.name(),
        n + " 条",
        "状态不是「成功」的回答",
        n > 0 ? "去「错误日志」看逐条原因：多半是上游模型超时或向量化失败" : null);
  }

  private static String percent(double ratio) {
    return String.format(java.util.Locale.ROOT, "%.1f%%", ratio * 100);
  }

  private static String gb(long bytes) {
    return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
  }

  private static String mb(long bytes) {
    return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0);
  }

  /** 体检等级。第四个 `UNKNOWN` 是"读不出来" —— 它不等于 CRITICAL，但更不等于 OK。 */
  public enum Level {
    OK,
    WARN,
    CRITICAL,
    UNKNOWN
  }

  /** 一条读数。`value` 是给人看的短值（「62.3%」），`detail` 是算出来的明细，`hint` 只在非 OK 时给。 */
  public record Item(
      String key, String label, String level, String value, String detail, String hint) {}

  /** 一次体检的全量读数。 */
  public record Snapshot(Instant at, List<Item> items) {}
}
