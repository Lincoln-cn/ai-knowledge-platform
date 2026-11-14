package cn.lincoln.aiplatform.assistant.domain;

import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 应用的仓储（领域端口）—— 实现在 {@code assistant.infrastructure}。
 *
 * <p>★ 与 {@code KnowledgeBaseRepository} 同一条口径：领域用一个**窄接口**表达需要什么， Spring Data / JPA 的类型 （{@code
 * Pageable} / {@code Specification} / {@code EntityManager}）留在适配器里。 理由不是洁癖：那些类型一旦进来， 领域类就没法在不起
 * Spring 的情况下被测。
 */
public interface AssistantRepository {

  /** 新增或整体保存（{@code @ElementCollection} 的子表由 Hibernate 处理）。 */
  Assistant save(Assistant assistant);

  Optional<Assistant> findById(UUID id);

  /**
   * 幂等创建用：按 {@code (client_request_id, owner_id)} 找既有应用。
   *
   * <p>★ 与知识库同一条口径（{@code V12}）：幂等是"同一个调用方的重试"，不是"全平台一个命名空间"。
   */
  Optional<Assistant> findByClientRequestIdAndOwnerId(String clientRequestId, UUID ownerId);

  /**
   * 列表：范围 + 筛选。
   *
   * <p>★ 范围（{@code ScopeFilter}）必须<strong>拼进查询</strong>而不是查完再筛 —— 否则 {@code total} 与页内条数是两个口径
   * （用户看到"共 100 条"却只翻得出 3 条，{@code 08 §4.4.3} 纪律 1）。
   */
  List<Assistant> search(ScopeFilter scope, Filter filter, int offset, int limit);

  /** 与 {@link #search} <strong>共用同一份谓词</strong>的计数（两处各拼一遍必然漂移）。 */
  long countSearch(ScopeFilter scope, Filter filter);

  /**
   * 全部可见标签（市场筛选用，契约 {@code listAssistantTags}）。
   *
   * <p>★ 与 {@link #search} <strong>共用同一份范围谓词</strong>：标签是从"我看得见的应用"里长出来的，不是全表 {@code distinct} ——
   * 否则市场下拉框会泄露别人应用上的标签（看不见的应用，其标签也不该出现）。
   *
   * <p>★ 去重且按库侧排序（同一标签被 N 个应用用过只出现一次）；不按 {@code published} 过滤 —— 与 mock 的语义一致 （{@code
   * frontend/src/mock/handlers/assistants.ts} 只按可见性筛）。
   */
  List<String> listTags(ScopeFilter scope);

  /**
   * 到期的定时触发（{@code #295} / 0.7.3）。
   *
   * <p>★ 按 {@code trigger_next_at} 取而不是每次全表算 cron："到期"要是可索引的谓词，服务停机后
   * 也才回答得出"这一班错过了没有"。已软删的应用不算（删了还在后台跑是一类最难查的问题）。
   *
   * @param now 判定"到期"的时刻
   * @param limit 单轮上限 —— 停机很久之后可能有大量应用同时到期，一次全拉出来会同时起一堆模型调用
   */
  List<Assistant> findDueTriggers(java.time.Instant now, int limit);

  /**
   * 抢占一次触发（乐观锁）。
   *
   * <p>★ 条件是 {@code trigger_next_at = expected}（<b>精确相等</b>，不是 {@code <=}）：调用方刚读到
   * 的那个值就是基准，谁先改谁赢。多副本部署时这是唯一能保证"同一个周期只跑一次"的机制 —— 分布式锁要引入外部依赖，而这里的争用粒度只是"一个应用一秒一次"。
   *
   * <p>★ 抢到之后立刻顺延 {@code trigger_next_at}：执行期间不会被下一轮扫描再抢一次。代价是 "抢到但执行崩了"同样会顺延 ——
   * 那是刻意接受的（失败后等下个周期重跑，而不是原地立即重试， 否则一个坏图会以每秒一次的频率打外部模型）。
   *
   * @return {@code true} 表示抢到了（本次真的跑），{@code false} 表示别人先改了
   */
  boolean claimTrigger(
      UUID id,
      java.time.Instant expectedNextAt,
      java.time.Instant firedAt,
      java.time.Instant nextAt,
      int missed);

  /**
   * 列表筛选条件（契约 {@code AssistantQuery}）。
   *
   * <p>★ 为什么收成一个 record 而不是多个位置参数：里面有两个 {@code Boolean}（{@code published} / {@code mine}）
   * 和一个排序键，按位置传参时把两个布尔写反<strong>编译器不会报错</strong>。
   *
   * @param origin {@code OFFICIAL}（内置）/ {@code SHARED}（他人共享）/ {@code MINE}（我建的）；null = 不过滤
   * @param sort {@code POPULAR} / {@code LATEST} / {@code NAME}；null = {@code LATEST}
   * @param favorite 只看<strong>我收藏的</strong>（{@code favorite=true}）。★ 与 {@code mine} 正交：{@code mine}
   *     是"我建的"，{@code favorite} 是"我收藏的" —— 自己建的应用不自动算收藏
   */
  record Filter(
      AssistantType type,
      Boolean published,
      boolean mine,
      String origin,
      String tag,
      String keyword,
      String sort,
      boolean favorite) {}
}
