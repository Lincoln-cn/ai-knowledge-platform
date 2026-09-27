package cn.lincoln.aiplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 角色（聚合根）。
 *
 * <p>★ 这里在聚合上直接用了 JPA 注解 —— 这是 {@code 01 §6.3} 明确记录的**务实折中**： 维护"领域模型 + 持久化模型"两套并在中间写转换代码，在 MVP
 * 阶段是纯粹的额外成本， 而转换代码本身容易出错。仓储<em>接口</em>仍然定义在 {@code domain} 包（依赖倒置）， 领域服务保持无框架类型。
 *
 * <p>权限点不在这里 —— 它是"代码常量 + 启动时幂等同步"（{@code 08 §4.3.1}）， 由 {@code Permissions} 按 {@link #code} 映射出来，第
 * 3 周才落库。
 */
@Entity
@Table(name = "iam_role")
public class Role {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  /**
   * 角色代码，如 {@code ADMIN} / {@code KB_OPERATOR}。
   *
   * <p>★ 它是"角色 → 权限点"映射的键，也是 JWT 里的一个声明 —— 所以它在库里是全局唯一的（不加 {@code deleted_at} 条件），复用会让旧数据指向语义已变的角色。
   */
  @Column(name = "code", nullable = false, length = 32)
  private String code;

  /** JPA 要求的无参构造。不要直接用它 —— 用静态工厂。 */
  protected Role() {}

  public Role(UUID id, String name, String code) {
    this.id = id;
    this.name = name;
    this.code = code;
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String code() {
    return code;
  }
}
