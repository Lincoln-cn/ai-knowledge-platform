package cn.lincoln.aiplatform.knowledge.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 标签冗余的一致性读数：**分段标签与所属文档标签不一致的分段条数**（`#26` 判据 ④）。
 *
 * <p>为什么需要它：`kb_segment.labels` 是冗余列（谓词作用在分段表上，不能 join 回文档）。冗余总有 一条会漂的路径 ——
 * 今天只有两个写入点（建档时写文档列、分块时继承到分段列），将来加"单篇改标签"时 很容易只改文档列。**漂了之后的表现是"低密级／无标签的角色检索到了不该看到的内容"**，而那是静默的：
 * 没有异常、没有日志、页面也不会报错，只有一条已经越权命中的召回（`#26` 原文的危害第 1 条）。
 *
 * <p>三条刻意的设计：
 *
 * <ul>
 *   <li><b>用 Gauge 而不是定时任务</b>：Gauge 在**每次抓取时**求值，不占调度、不留缓存，也就不会出现 "任务没跑起来所以读数一直是 0"那种假绿（本项目头号病）；
 *   <li><b>查询失败返回 {@code -1}，不是 0</b>：0 表示"查过了、一致"；-1 表示"这一次没查成"。把两者 混成一个值，等于把"没测到"伪装成"没问题"（`#228`
 *       的形状）；
 *   <li><b>比较前排序</b>：标签是集合不是序列，`['a','b']` 与 `['b','a']` 是同一份标签，直接比数组会误报。
 * </ul>
 *
 * <p>★ 它只做**读数**，不做修复：自动"对齐"会把一次写入缺陷变成一条静默的数据改动，而缺陷本身还在。
 *
 * @see cn.lincoln.aiplatform.knowledge.domain.Document#labels()
 * @see cn.lincoln.aiplatform.knowledge.domain.Segment#labels()
 */
@Component
public class SegmentLabelMismatchMetric {

  /** 指标名（Micrometer 名 → Prometheus 名为 `aiplatform_segment_label_mismatch`）。 */
  public static final String NAME = "aiplatform.segment.label-mismatch";

  /**
   * 分段标签与文档标签不一致的条数。
   *
   * <p>{@code coalesce(... , '{}')} 把 NULL 与空数组归一：两者语义相同（"没打标签"）， 但 PostgreSQL 里 {@code null <>
   * '{}'} 是 NULL 而不是 true —— 归一之前这条查询会漏掉一半的漂移。
   */
  private static final String MISMATCH_SQL =
      """
      select count(*)
        from kb_segment s
        join kb_document d on d.id = s.document_id
       where coalesce(array(select unnest(s.labels) order by 1), '{}')
             <> coalesce(array(select unnest(d.labels) order by 1), '{}')
      """;

  private static final Logger log = LoggerFactory.getLogger(SegmentLabelMismatchMetric.class);

  private final JdbcTemplate jdbc;

  SegmentLabelMismatchMetric(MeterRegistry registry, JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    Gauge.builder(NAME, this, SegmentLabelMismatchMetric::countMismatchedSegments)
        .description("分段标签与所属文档标签不一致的分段条数（应为 0；-1 表示这次查询没成功）")
        .register(registry);
  }

  /**
   * 现算一次不一致条数。
   *
   * @return ≥0 = 实际条数；{@code -1} = 这一次查询失败（已记日志）
   */
  long countMismatchedSegments() {
    try {
      List<Long> rows = jdbc.queryForList(MISMATCH_SQL, Long.class);
      return rows.isEmpty() || rows.get(0) == null ? -1 : rows.get(0);
    } catch (RuntimeException failure) {
      // ★ 不往上报：抓取端点抛异常会让整次 scrape 失败，于是"一个读数坏了"会把其余读数一起带走。
      log.warn("标签一致性读数查询失败（本值记为 -1，不是 0）", failure);
      return -1;
    }
  }
}
