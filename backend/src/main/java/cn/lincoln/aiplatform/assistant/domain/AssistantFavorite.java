package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 应用收藏（{@code as_assistant_favorite}，{@code #195} 的「收藏」子项）。
 *
 * <p>★ 这张表<strong>没有聚合行为</strong>（收藏 / 取消收藏都是整行增删），按本仓对 {@code RolePermissionRepository}
 * 的同一条判断，它"不值得建 {@code @Entity}"。这里仍然建，理由只有一个： 列表的 {@code favorite=true} 必须是<strong>下推到 SQL
 * 的谓词</strong>（相关 {@code EXISTS}），而 {@code CriteriaBuilder.subquery(...).from(...)}
 * 需要一个可映射的类型。于是这个实体被刻意压到最小 —— 只映射谓词用到的三列，{@code created_at} 由库默认值填，不参与任何判定。
 *
 * <p>★ <strong>不作为聚合根</strong>：没有服务层之外的写入口，也没有 getter 之外的 setter。写操作走 {@link
 * AssistantFavoriteRepository}（幂等插入 / 整行删除）。
 *
 * <p>★ 为什么不把 {@code favorited} 做成 {@link Assistant} 上的 {@code @OneToMany}：那会把"谁收藏了" 拉进应用聚合的加载图（列表每页
 * 20 个应用就会带出一批无关的用户行）；而列表需要的只是"这 20 个 id 里哪几个被我收藏"， 由 {@link
 * AssistantFavoriteRepository#favoritedAmong} 一次批量回答。
 */
@Entity
@Table(name = "as_assistant_favorite")
public class AssistantFavorite {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assistant_id", nullable = false)
  private UUID assistantId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** JPA 用；业务侧不直接 new（写入口只有仓储的幂等插入）。 */
  protected AssistantFavorite() {}

  public UUID id() {
    return id;
  }

  public UUID assistantId() {
    return assistantId;
  }

  public UUID userId() {
    return userId;
  }
}
