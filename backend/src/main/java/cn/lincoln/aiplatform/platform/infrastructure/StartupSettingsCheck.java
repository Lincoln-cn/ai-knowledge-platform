package cn.lincoln.aiplatform.platform.infrastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时的**配置自检**（`#227` / `#65` 各自暴露过一次"配错了却在第一次入库才炸"）。
 *
 * <p>★ 为什么要有它：本仓库的配置项分两类危险 ——
 *
 * <ol>
 *   <li><strong>配错就静默降级</strong>：`app.ingestion.embedding-concurrency` 大于连接池时，
 *       一批文档一起入库会让多出来的虚拟线程拿不到连接，表现为<em>与文档内容无关</em>的"向量化失败"。 这正是 `#227` 现场那 12 份假失败的形状 ——
 *       排查时人会先去怀疑模型、网络、语料。
 *   <li><strong>配了但永远用不上</strong>：`app.segmentation.context-window` 在向量预算极小时会被裁空，
 *       于是配置看起来生效了、实际一条窗口都没进来（`#65` 的预算是让路的）。
 * </ol>
 *
 * <p>★ 判据的设计原则：**只报"能推出后果"的项**，不报"看起来奇怪"的项。一条启动警告说清"什么配置、 会怎么坏、怎么改"，比十条泛泛的"建议检查配置"有用；后者会训练人忽略启动日志。
 *
 * <p>★ 这里是纯函数（不碰 Spring），所以判据能不起容器就断言 —— 见 {@code StartupSettingsCheckTest}。
 */
final class StartupSettingsCheck {

  /** 分段前缀的最坏长度：`SearchText.prefix(...)` 的 45+ 字 + 分隔符，工程上按 80 字留。 */
  static final int WORST_CASE_PREFIX_CHARS = 80;

  /** 主块至少要留下的字数：低于这个数，分段几乎只剩前缀。 */
  static final int MIN_BODY_CHARS = 120;

  private StartupSettingsCheck() {}

  /**
   * 逐项检查，返回**一条一条能改的话**（空列表 = 没问题）。
   *
   * @param embeddingConcurrency 同时向量化的文档份数（`app.ingestion.embedding-concurrency`）
   * @param poolSize 连接池上限（Hikari `maximum-pool-size`）
   * @param contextWindow 上下文窗口字符数（`0` = 关闭）
   * @param segmentMaxChars 向量预算（`app.segmentation.max-chars`）
   */
  static List<String> problems(
      int embeddingConcurrency, int poolSize, int contextWindow, int segmentMaxChars) {
    // 老的四参版本：保留窗口与清理窗口取同一个数（等于"没配"），因此不产生第七条告警。
    return problems(embeddingConcurrency, poolSize, contextWindow, segmentMaxChars, 7, 7);
  }

  /**
   * 带回收站两个窗口的版本（`#259`）。
   *
   * <p>★ 为什么单独报这一条：`restore-window-days` 与 `cleanup.retain-days` 是**两个键**， 默认都是 7
   * 天。运维改一个忘一个，就会出现两个方向都看得见的错 —— 清理窗口更短 ⇒ 用户手里的 「恢复」按钮亮着，点下去 404/400（内容已经被删）；清理窗口更长 ⇒
   * 按钮灰了，内容却还占着空间。 都不是崩溃，都是"看起来能用"。这正是启动自检该报的那一类。
   */
  static List<String> problems(
      int embeddingConcurrency,
      int poolSize,
      int contextWindow,
      int segmentMaxChars,
      int restoreWindowDays,
      int cleanupRetainDays) {
    List<String> problems = new ArrayList<>();

    if (embeddingConcurrency < 1) {
      problems.add(
          "app.ingestion.embedding-concurrency="
              + embeddingConcurrency
              + "：向量化并发下限是 1 —— 配成 0 或负数，等于把向量化关掉，且不会有任何报错"
              + "（文档会停在『待向量化』）。改成 ≥1。");
    } else if (embeddingConcurrency > poolSize) {
      problems.add(
          "app.ingestion.embedding-concurrency="
              + embeddingConcurrency
              + " 大于连接池上限 "
              + poolSize
              + "：一批文档同时入库时，多出来的任务会拿不到数据库连接，表现为『与内容无关的向量化失败』"
              + "（#227 现场就是 69 份一起跑、10 条 Failed to obtain JDBC Connection）。"
              + "改成小于池上限（建议 ≤ 池上限的一半，给请求线程留余量）。");
    }

    if (contextWindow < 0) {
      problems.add(
          "app.segmentation.context-window="
              + contextWindow
              + "：窗口不能为负 —— 入库时会直接抛 IllegalArgumentException（预览同样会失败）。改成 0（关闭）或正数。");
    } else if (contextWindow > 0 && segmentMaxChars - WORST_CASE_PREFIX_CHARS < MIN_BODY_CHARS) {
      problems.add(
          "app.segmentation.max-chars="
              + segmentMaxChars
              + " 太小：扣掉前缀（最坏 "
              + WORST_CASE_PREFIX_CHARS
              + " 字）后连 "
              + MIN_BODY_CHARS
              + " 字的主块都留不下，context-window="
              + contextWindow
              + " 会被裁空 —— 窗口看起来开着、实际一条都没进来。建议 max-chars ≥ "
              + (WORST_CASE_PREFIX_CHARS + MIN_BODY_CHARS + contextWindow)
              + "。");
    }

    if (cleanupRetainDays < restoreWindowDays) {
      problems.add(
          "app.knowledge.cleanup.retain-days="
              + cleanupRetainDays
              + " 小于 app.knowledge.restore-window-days="
              + restoreWindowDays
              + "：回收站里还会显示「恢复」的文档，内容可能已经被清理作业删掉了"
              + "（点下去得到 400/404）。两者要么同值，要么清理窗口不小于恢复窗口。");
    }

    return problems;
  }
}
