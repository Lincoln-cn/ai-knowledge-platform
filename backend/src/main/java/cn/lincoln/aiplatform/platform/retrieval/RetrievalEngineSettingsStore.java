package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 检索引擎的读写（表 {@code pf_retrieval_settings}，单行 · 迁移 {@code V100}）。
 *
 * <h3>★ 为什么用 JDBC + 一个 volatile 快照，而不是 JPA 实体</h3>
 *
 * <p>同 {@code ChatSettingsStore} 的判据：**没有不变量要守的聚合不值得做成实体**。这里的行为只有 "读一行 / 写一行"，不变量（范围）在 {@link
 * RetrievalEngineSettings#checked()}。
 *
 * <p>★ 但比 {@code ChatSettingsStore} 多一层**内存快照**，理由是可测的：{@link #current()} 会被重排的每次前向调用（{@code
 * batchSize()}）与检索的每次请求读到。若每次都查库， 一次检索会多出十几次 round-trip。快照在 {@link #save} 之后立刻替换 ⇒ **改完即时生效**， 且不引入
 * {@code @RefreshScope}（那会改掉 15 处构造器签名，见迁移注释）。
 *
 * <h3>★ 多副本的边界（写下来，不假装没有）</h3>
 *
 * <p>快照是**进程内**的：多副本部署时，A 副本保存后 B 副本要等到重启才会读到新值。这一条不进本版 （单副本部署），但必须在配置页的说明里写清 —— 否则"改了没生效"会被读成 bug。
 *
 * <h3>★ 读不到库时回落到 {@code application.yml}</h3>
 *
 * <p>回落而不是抛，与 {@code ChatSettingsStore} 相反。差别在于**后果不同**：那边读不到意味着库没
 * 迁到位，界面会显示一套不生效的数字；这里读不到只影响"能不能改"，检索本身仍按 yml 正常工作， 回落并打 WARN 说清"现在用的是哪一套"更不容易让整个检索链起不来。
 *
 * <p>★ 但**保存**时读不到库要抛：那是"用户按了保存但没落盘"，静默成功比失败更糟。
 */
@Component
public class RetrievalEngineSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(RetrievalEngineSettingsStore.class);

  /** 单行表的主键恒为 true。 */
  private static final boolean SINGLETON = true;

  private final JdbcTemplate jdbc;
  private final RerankProperties rerankDefaults;
  private final RetrievalBudgetProperties budgetDefaults;

  /**
   * 进程内快照。{@code null} = 还没读过（懒加载）。
   *
   * <p>★ 懒加载而不是 {@code @PostConstruct}：Flyway 的初始化与 bean 的构造没有强制顺序， 在构造期查表会撞上"表还没建"。
   */
  private volatile RetrievalEngineSettings snapshot;

  public RetrievalEngineSettingsStore(
      JdbcTemplate jdbc,
      RerankProperties rerankDefaults,
      RetrievalBudgetProperties budgetDefaults) {
    this.jdbc = jdbc;
    this.rerankDefaults = rerankDefaults;
    this.budgetDefaults = budgetDefaults;
  }

  /**
   * 当前生效值（内存快照，零查询；首次调用时才读库）。
   *
   * @return 当前生效值
   */
  public RetrievalEngineSettings current() {
    RetrievalEngineSettings value = snapshot;
    if (value == null) {
      value = loadOrFallback();
      snapshot = value;
    }
    return value;
  }

  /**
   * 从库重读（配置页的"重新读取"，或怀疑快照过期时用）。
   *
   * @return 读到的值
   */
  public RetrievalEngineSettings reload() {
    RetrievalEngineSettings value = loadOrFallback();
    snapshot = value;
    return value;
  }

  /**
   * 整行覆盖写，并把快照换成新值（即时生效）。
   *
   * @param settings 已校验的新值
   * @param operator 操作人
   * @return 落库后的值（含 {@code updatedAt}）
   * @throws IllegalStateException 库里没有那一行（= 迁移没跑）
   */
  public RetrievalEngineSettings save(RetrievalEngineSettings settings, UUID operator) {
    int affected =
        jdbc.update(
            "update pf_retrieval_settings set query_understanding_enabled = ?,"
                + " query_understanding_retry = ?, rerank_enabled = ?, rerank_top_k = ?,"
                + " rerank_forward_batch = ?, rerank_max_length = ?,"
                + " listwise_enabled = ?, listwise_top_k = ?,"
                + " updated_at = now(), updated_by = ?"
                + " where id = ?",
            settings.queryUnderstandingEnabled(),
            settings.queryUnderstandingRetry(),
            settings.rerankEnabled(),
            settings.rerankTopK(),
            settings.rerankForwardBatch(),
            settings.rerankMaxLength(),
            settings.listwiseEnabled(),
            settings.listwiseTopK(),
            operator,
            SINGLETON);
    if (affected == 0) {
      throw new IllegalStateException(
          "pf_retrieval_settings 没有那一行，无法更新 —— 请先跑迁移 V100" + "（★ 不要在这里 insert，否则「单行」这条约束会变成两行）");
    }
    RetrievalEngineSettings saved = loadOrFallback();
    snapshot = saved;
    log.info(
        "检索引擎配置已更新：qu={} retry={} rerank={} 窗口={} 批={} 长度={} listwise={}",
        saved.queryUnderstandingEnabled(),
        saved.queryUnderstandingRetry(),
        saved.rerankEnabled(),
        saved.rerankTopK(),
        saved.rerankForwardBatch(),
        saved.rerankMaxLength(),
        saved.listwiseEnabled());
    return saved;
  }

  /**
   * 读库；读不到就回落到 yml 并打 WARN。
   *
   * @return 库里的值，或 yml 的值
   */
  private RetrievalEngineSettings loadOrFallback() {
    try {
      List<RetrievalEngineSettings> found =
          jdbc.query(
              "select query_understanding_enabled, query_understanding_retry, rerank_enabled,"
                  + " rerank_top_k, rerank_forward_batch, rerank_max_length,"
                  + " listwise_enabled, listwise_top_k,"
                  + " updated_at from pf_retrieval_settings where id = ?",
              (rs, rowNum) ->
                  new RetrievalEngineSettings(
                      rs.getBoolean("query_understanding_enabled"),
                      rs.getBoolean("query_understanding_retry"),
                      rs.getBoolean("rerank_enabled"),
                      rs.getInt("rerank_top_k"),
                      rs.getInt("rerank_forward_batch"),
                      rs.getInt("rerank_max_length"),
                      rs.getBoolean("listwise_enabled"),
                      rs.getInt("listwise_top_k"),
                      rs.getObject("updated_at", java.time.OffsetDateTime.class) == null
                          ? null
                          : rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
              SINGLETON);
      if (found.isEmpty()) {
        return fallback("pf_retrieval_settings 没有那一行");
      }
      return found.get(0);
    } catch (RuntimeException unreadable) {
      return fallback("读 pf_retrieval_settings 失败：" + unreadable.getMessage());
    }
  }

  /**
   * 用 yml 的值拼一份，并说清"现在用的是哪一套"。
   *
   * <p>★ 打 WARN 不说原因是不够的：这条日志要能回答"为什么我在页面上保存了、检索行为没变"。
   */
  private RetrievalEngineSettings fallback(String reason) {
    log.warn(
        "{} —— 检索引擎配置回落到 application.yml：qu={} retry={} rerank={} 窗口={} 批={} 长度={} listwise={}",
        reason,
        budgetDefaults.queryUnderstandingEnabled(),
        budgetDefaults.queryUnderstandingRetryOnParseFailure(),
        rerankDefaults.enabled(),
        rerankDefaults.topK(),
        rerankDefaults.forwardBatch(),
        rerankDefaults.maxLength(),
        rerankDefaults.listwiseEnabled());
    return new RetrievalEngineSettings(
        budgetDefaults.queryUnderstandingEnabled(),
        budgetDefaults.queryUnderstandingRetryOnParseFailure(),
        rerankDefaults.enabled(),
        rerankDefaults.topK(),
        rerankDefaults.forwardBatch(),
        rerankDefaults.maxLength(),
        rerankDefaults.listwiseEnabled(),
        rerankDefaults.listwiseTopK(),
        null);
  }
}
