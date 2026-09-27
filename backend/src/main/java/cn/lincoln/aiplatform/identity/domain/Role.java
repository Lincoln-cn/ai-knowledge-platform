package cn.lincoln.aiplatform.identity.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 角色（聚合根）。
 *
 * <p>它承担**两个正交维度**（{@code 08 §4.4.1}）：
 *
 * <table>
 *   <tr><th>维度</th><th>字段</th><th>回答的问题</th></tr>
 *   <tr><td>操作权限</td><td>{@code code} → {@link Permissions} 常量</td><td>能不能做这个<strong>动作</strong></td></tr>
 *   <tr><td>数据范围</td><td>{@link #dataScope}</td><td>能看到<strong>哪些</strong>数据</td></tr>
 *   <tr><td>密级</td><td>{@link #maxClassification} + {@link #labels}</td><td>能看到<strong>多敏感</strong>的内容</td></tr>
 * </table>
 *
 * <p>★ 权限点<strong>不在这里</strong>：它是"代码常量 + 启动时幂等同步"（{@code 08 §4.3.1}）， 由 {@link Permissions} 按
 * {@code code} 映射出来。把权限点做成本实体的一个字段， 就会回到"权限来自数据库"那条老路 —— 而那正是"新增功能忘配权限 ⇒ 功能隐形"的成因。
 *
 * <p>JPA 注解直接用在聚合上，理由见 {@code 01 §6.3} 的务实折中。
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
   * <p>★ 它是"角色 → 权限点"映射的键，也是 JWT 里的一个声明 —— 库里全局唯一，不做软删复用。
   */
  @Column(name = "code", nullable = false, length = 32)
  private String code;

  @Enumerated(EnumType.STRING)
  @Column(name = "data_scope", nullable = false, length = 16)
  private DataScope dataScope;

  /** 范围规则的逃生口（{@code 07 §8.3}）。★ MVP 只存不解析。 */
  @Column(name = "data_scope_rule", length = 512)
  private String dataScopeRule;

  /**
   * 密级上限（clearance）—— 这个角色**能看到**的最高密级。
   *
   * <p>⚠️ 与知识库的 {@code maxClassification}（ceiling，"这个库允许装多密的"）**正交**。 两者混成一个字段会让"库允许装、但我不该看"无处表达。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "max_classification", nullable = false, length = 16)
  private ClassificationLevel maxClassification;

  /** 可涉的密级标签（{@code text[]}）。★ 空表示不按标签限制。 */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "labels")
  private List<String> labels;

  protected Role() {}

  /**
   * 便利构造器：**默认一律收窄**（{@code 08 §4.4.4} 的纪律 —— 数据权限放开容易、收回难）。
   *
   * <p>它的用途是测试与临时装配，不要把"默认值"当成"合理值"用在真实角色上。
   */
  public Role(UUID id, String name, String code) {
    this(id, name, code, DataScope.own, null, ClassificationLevel.公开, List.of());
  }

  public Role(
      UUID id,
      String name,
      String code,
      DataScope dataScope,
      String dataScopeRule,
      ClassificationLevel maxClassification,
      List<String> labels) {
    this.id = id;
    this.name = name;
    this.code = code;
    this.dataScope = dataScope;
    this.dataScopeRule = dataScopeRule;
    this.maxClassification = maxClassification;
    this.labels = labels == null ? List.of() : List.copyOf(labels);
  }

  /**
   * 改数据范围（{@code PUT /roles/:id/data-scope}）。
   *
   * <p>与权限点**分开保存**是有意的（{@code 07 §3.2} 末尾）：两者语义不同、受众不同 —— 勾权限点是"这个岗位能做什么"，调范围是"这个岗位能看到谁的东西"。
   * 合成一次提交会让"只想调范围"的人不得不重新确认一遍全部权限点。
   */
  public void changeDataScope(DataScope scope, String rule) {
    this.dataScope = scope;
    this.dataScopeRule = rule;
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

  public DataScope dataScope() {
    return dataScope;
  }

  public String dataScopeRule() {
    return dataScopeRule;
  }

  public ClassificationLevel maxClassification() {
    return maxClassification;
  }

  public List<String> labels() {
    return labels == null ? List.of() : labels;
  }
}
