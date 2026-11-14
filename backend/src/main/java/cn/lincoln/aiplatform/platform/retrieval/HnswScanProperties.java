package cn.lincoln.aiplatform.platform.retrieval;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HNSW 扫描参数（{@code app.retrieval.hnsw.*}）—— 向量那一路的两个会话级旋钮。
 *
 * <h3>为什么这两个值要可配、也要能读出来</h3>
 *
 * <p>它们是 pgvector 的<strong>会话参数</strong>，不在索引定义里：同一个索引，{@code ef_search} 从 40 改到 100 会扫更多候选。今天仓里
 * {@code iterative_scan} 是写死的、{@code ef_search} <strong>从未被设置</strong> （跑的是 pgvector 默认 40），而"默认 40
 * 够不够"没有任何读数 —— 这正是 0.8.0 要收掉的那一类： 配置写了但看不出来，等于没配。
 *
 * <h3>★ {@code iterativeScan} 会被拼进 SQL</h3>
 *
 * <p>{@code SET LOCAL} 不支持占位符（PostgreSQL 的 {@code SET} 不是可预编译语句），所以这两个值只能拼进
 * 字符串。拼字符串就必须<strong>白名单校验</strong>：{@code iterativeScan} 只认 {@code off} / {@code relaxed_order} /
 * {@code strict_order}，其余一律抛 —— 否则一个被改过的配置文件就能把任意 SQL 塞进检索路径。{@code efSearch} 是 {@code int}
 * 且校验范围，没有拼接面。
 *
 * @param efSearch 候选扫描条数（pgvector 默认 40）；调大只增加扫描完整性，<strong>不增加质量</strong>
 * @param iterativeScan 迭代扫描模式：高选择性过滤下是否继续多扫（见 {@link
 *     cn.lincoln.aiplatform.knowledge.infrastructure.JdbcSegmentSearchQuery} 的向量那一路）
 */
@ConfigurationProperties("app.retrieval.hnsw")
public record HnswScanProperties(Integer efSearch, String iterativeScan) {

  /** pgvector 的默认值 —— "没配"与"配成 40"是同一件事，写成常量是为了能打印"今天用的是哪个值"。 */
  public static final int DEFAULT_EF_SEARCH = 40;

  /** 合法模式。{@code off} 是"不迭代"，{@code strict_order} 是"需要时继续多扫且保持距离序"。 */
  public static final Set<String> SCAN_MODES = Set.of("off", "relaxed_order", "strict_order");

  public HnswScanProperties {
    efSearch = efSearch == null ? DEFAULT_EF_SEARCH : efSearch;
    if (efSearch <= 0 || efSearch > 10000) {
      throw new IllegalArgumentException(
          "app.retrieval.hnsw.ef-search 要在 1..10000 之间，现在是 " + efSearch);
    }
    iterativeScan =
        iterativeScan == null || iterativeScan.isBlank() ? "strict_order" : iterativeScan.trim();
    if (!SCAN_MODES.contains(iterativeScan)) {
      throw new IllegalArgumentException(
          "app.retrieval.hnsw.iterative-scan 只能是 " + SCAN_MODES + " 之一，现在是 " + iterativeScan);
    }
  }
}
