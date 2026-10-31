package cn.lincoln.aiplatform.platform.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 审计的查询端口（`#258`：写进去了还得读得出来）。
 *
 * <p>★ 只读。{@link AuditLogPort} 只有写，这里只有查 —— 没有 update、没有 delete， 连这两个动词都不出现在接口上。`13 §7.2` 第 3
 * 条说的"只追加"，在代码里最直接的表达就是"读侧的端口里没有可写的办法"。
 *
 * <p>★ 过滤条件全部是**等值或区间**，不做全文检索：审计的用途是"按资源/按人/按时间找一件事"，
 * 不是"在审计里搜关键词"。后者会诱使人把明细塞成可搜索的文本，进而把审计变成第二个业务库。
 */
public interface AuditQueryPort {

  /**
   * 按条件查一页，**按时间倒序**（最近的先看到 —— 复盘时人总是从最近的那次变更往前找）。
   *
   * @param query 过滤条件；全部可空，都不给就是"全部"
   * @return 命中总数（过滤之后、分页之前）与这一页的记录
   */
  Result page(Query query);

  /** 查单个资源的全部记录（"这份文档到底被人改过几次" —— 最常见的用法）。 */
  /**
   * 按留存期清理：删掉 {@code created_at < beforeAt} 的记录，返回真正删掉的行数（`#75`）。
   *
   * <p>★ 这个方法出现在**读**端口里是刻意的：审计的删除只有一种合法动作 ——「按时间清旧的」，而且它只能经由库里那个 {@code
   * pf_audit_log_purge(before_at)} 函数执行（迁移 `V58`）；直接 {@code delete from pf_audit_log} 仍被只追加触发器挡住。
   * 所以它不是"给调用方一个删审计的口子"，而是"一个参数被库函数校验过的维护动作"：截止时间不能为空、不能在未来。
   *
   * <p>★ 返回值是为了让调用方**把它写进审计**：清理本身也是一次对审计的变更 —— 谁清的、清到什么时候、删了多少行， 必须自己留痕（{@code
   * AuditAction#AUDIT_PURGE}）。这也解释了为什么不返回 void。
   */
  long purgeOlderThan(Instant beforeAt);

  /** 过滤条件。{@code from} 含、{@code to} 不含（半开区间：相邻两天的查询不会重复算边界那一秒）。 */
  record Query(
      String action,
      String resourceType,
      String resourceId,
      UUID actorId,
      Instant from,
      Instant to,
      int page,
      int size) {}

  /**
   * 一页结果。
   *
   * @param list 这一页
   * @param total 过滤后的总数（不是表里的行数 —— 见 {@link cn.lincoln.aiplatform.platform.web.PageResult}）
   */
  record Result(java.util.List<AuditRecordView> list, long total) {}

  /** 便于调用方写"没有就算了"的读法（本端口不抛"查不到"，只返回空）。 */
  static Optional<AuditRecordView> first(java.util.List<AuditRecordView> list) {
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }
}
