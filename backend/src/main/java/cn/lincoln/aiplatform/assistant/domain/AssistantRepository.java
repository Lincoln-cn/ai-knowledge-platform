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
