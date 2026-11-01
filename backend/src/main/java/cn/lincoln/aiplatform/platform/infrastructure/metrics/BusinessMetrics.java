package cn.lincoln.aiplatform.platform.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 业务指标的<b>唯一出口</b>（{@code #73} 留缝的第一格）。
 *
 * <p>★ 为什么要有这一层，而不是各处直接注入 {@link MeterRegistry}：
 *
 * <ul>
 *   <li><b>指标名与标签是口径</b>，不是实现细节。散落在调用点上，名字就会漂成三种写法， 而仪表盘对不上名字时表现为"这条曲线一直是 0" ——
 *       与"真的没有失败"读数完全一样（本项目头号病）。
 *   <li>标签的<b>基数</b>要一处把关：应用名、会话 id、模型 id 这类高基数字段一旦进标签， Prometheus
 *       的序列数会随用户量线性增长。这里只允许<b>闭集</b>标签（{@code outcome} / {@code stage}）。
 * </ul>
 *
 * <h3>三条指标（就是 {@code ROADMAP §二 2.3} 里"只能测一次"的那几项）</h3>
 *
 * <table border="1">
 *   <caption>指标口径</caption>
 *   <tr><th>Micrometer 名</th><th>Prometheus 名</th><th>回答什么问题</th><th>标签</th></tr>
 *   <tr>
 *     <td>{@code aiplatform.upstream.calls}</td>
 *     <td>{@code aiplatform_upstream_calls_total}</td>
 *     <td>上游不可达率 = {@code outcome="unreachable"} / 全部</td>
 *     <td>{@code outcome="ok"|"unreachable"}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code aiplatform.chat.first-token}</td>
 *     <td>{@code aiplatform_chat_first_token_seconds}</td>
 *     <td>首字延迟（{@code B4} 的"≤10s"从此是持续读数，不是一次性验收）</td>
 *     <td>无</td>
 *   </tr>
 *   <tr>
 *     <td>{@code aiplatform.ingestion.failures}</td>
 *     <td>{@code aiplatform_ingestion_failures_total}</td>
 *     <td>入库失败率（分子；分母用 {@code aiplatform_documents_*} 那几条或库里计数）</td>
 *     <td>{@code stage="parse"|"segment"|"embedding"}</td>
 *   </tr>
 * </table>
 *
 * <h3>★ 两条纪律</h3>
 *
 * <ol>
 *   <li><b>只记"发生了什么"，不记"谁"</b>：本类没有任何以用户/应用/模型为标签的入口。 按应用/用户看用量是<b>配额与成本报表</b>的事（{@code #73}
 *       的第二段），落在数据库里，不落在指标里。
 *   <li><b>记录失败不许抛异常</b>：指标是旁路。Micrometer 自己不会抛，但这里的取值逻辑也不许 （例如 {@code outcome}
 *       用枚举而不是裸字符串，避免调用点拼错导致两条曲线）。
 * </ol>
 */
@Component
public class BusinessMetrics {

  /** 上游调用总数（{@code outcome} 区分成功与不可达）。 */
  public static final String UPSTREAM_CALLS = "aiplatform.upstream.calls";

  /** 首字延迟（从"发出上游请求"到"收到第一个内容块"）。 */
  public static final String CHAT_FIRST_TOKEN = "aiplatform.chat.first-token";

  /** 入库失败次数（{@code stage} 区分解析 / 分段 / 向量化）。 */
  public static final String INGESTION_FAILURES = "aiplatform.ingestion.failures";

  /** 上游调用的两种结局。★ 只允许这两个值 —— 它们是曲线的两条腿，拼错等于少一条。 */
  public enum UpstreamOutcome {
    OK("ok"),
    UNREACHABLE("unreachable");

    private final String tag;

    UpstreamOutcome(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }
  }

  /** 入库流水线里的三段。与 {@code DocumentStatus} 的失败态一一对应，不含业务态。 */
  public enum IngestionStage {
    PARSE("parse"),
    SEGMENT("segment"),
    EMBEDDING("embedding");

    private final String tag;

    IngestionStage(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }
  }

  private final MeterRegistry registry;

  /** 计数器按标签缓存：Micrometer 的 {@code registry.counter} 每次都会查 map，热路径上没必要重复查。 */
  private final Map<String, Counter> counters = new ConcurrentHashMap<>();

  private final Timer firstToken;

  /**
   * 不导出的实例：给<b>测试</b>与那些"手工 new 出来、没有 Spring 上下文"的构造器用。
   *
   * <p>★ 它故意仍然是一个真的 {@link MeterRegistry}（{@link SimpleMeterRegistry}），不是空实现：
   * 这样"记账有没有发生"在单测里可读（{@code registry.get("…").counter().count()}）， 而"能不能被 Prometheus 抓到"由集成测试覆盖 ——
   * 两件事分别验，才不会用一个假的空实现把两件都验成"通过"。
   */
  public static BusinessMetrics noop() {
    return new BusinessMetrics(new SimpleMeterRegistry());
  }

  public BusinessMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.firstToken =
        Timer.builder(CHAT_FIRST_TOKEN)
            .description("上游第一个内容块到达的耗时（首字延迟）")
            // ★ 客户端分位数是"本实例近似"，多实例部署时要靠 Prometheus 的 histogram_quantile 汇总；
            //   这里同时发布分位数是为了单实例部署（今天的默认形态）与离线排查能直接读。
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
  }

  /**
   * 记一次上游调用。
   *
   * <p>★ 调用点必须<b>成对</b>：成功路径与 {@code UpstreamUnreachableException} 路径各记一次。 只记失败会让"不可达率"的分母缺失 ——
   * 那算不出率，只能算出"次数"，而次数在流量上涨时也会涨。
   */
  public void upstreamCall(UpstreamOutcome outcome) {
    counter(UPSTREAM_CALLS, "outcome", outcome.tag()).increment();
  }

  /** 记一次首字延迟。{@code nanos} 必须是"发出上游请求 → 收到第一个内容块"。 */
  public void firstToken(long nanos) {
    if (nanos >= 0) {
      firstToken.record(Duration.ofNanos(nanos));
    }
  }

  /** 记一次入库失败。 */
  public void ingestionFailed(IngestionStage stage) {
    counter(INGESTION_FAILURES, "stage", stage.tag()).increment();
  }

  private Counter counter(String name, String tagKey, String tagValue) {
    return counters.computeIfAbsent(
        name + '|' + tagKey + '=' + tagValue,
        ignored ->
            Counter.builder(name)
                .description(describe(name))
                .tag(tagKey, tagValue)
                .register(registry));
  }

  private static String describe(String name) {
    return switch (name) {
      case UPSTREAM_CALLS -> "上游调用次数（按结局分）";
      case INGESTION_FAILURES -> "入库失败次数（按阶段分）";
      default -> name;
    };
  }
}
