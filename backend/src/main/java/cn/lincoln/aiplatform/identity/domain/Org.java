package cn.lincoln.aiplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 组织机构节点（第 19 周 · 形状收口，{@code 07 §十}）。
 *
 * <h3>★ {@code path} 是这个类存在的主要理由</h3>
 *
 * <p>数据权限要在<strong>每一条检索 SQL</strong> 里生效（{@code 13 §三} 的 pre-filter），
 * 而"我的部门及下级"用物化路径就是一次<strong>字符串前缀比较</strong>（planner 用得上索引）； 换递归 CTE
 * 则要把它塞进每一条检索查询，既难读也难被优化。代价是移动部门要重写子树路径 （见 {@link OrgService#move}），而移动是低频操作。
 *
 * <h3>★ {@code code} 与 {@code name} 的分工</h3>
 *
 * <ul>
 *   <li>{@code code}：<strong>稳定机器码</strong>，进共享主体（grant 的 {@code subject_id}）与审计 —— 改名不改码；
 *   <li>{@code name}：展示名，<strong>会进文档的召回前缀与 {@code content_hash}</strong> （{@code 12 §4.2}）⇒ ★
 *       改名等于"该部门下所有文档的前缀变了 ⇒ 需要重新向量化"， 所以界面上必须提示，而不是静默改名。
 * </ul>
 */
@Entity
@Table(name = "iam_org")
public class Org {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 父节点；根为 null。 */
  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  /**
   * 物化路径（形如 {@code /ROOT/PURCHASING/}，<strong>以 / 结尾</strong>）。
   *
   * <p>★ 结尾那个斜杠不是装饰：没有它，{@code /ROOT/AD} 会 match 到 {@code /ROOT/ADMIN/} ——
   * 一次"部门看到别人部门"的越权，而且它<strong>不会报错</strong>。
   */
  @Column(name = "path", nullable = false, length = 512)
  private String path;

  @Column(name = "sort", nullable = false)
  private int sort;

  /** 停用：不再产生新的共享；★ 既有共享**不**因此变成不可见（停用 ≠ 撤销）。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected Org() {}

  /**
   * 新建一个节点。
   *
   * @param parentPath 父节点的物化路径；根节点传 {@code "/"}
   */
  public Org(
      UUID id, UUID parentId, String parentPath, String code, String name, int sort, UUID actorId) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("部门编码不能为空（它是共享主体与审计里的稳定标识）");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("部门名称不能为空");
    }
    this.id = id;
    this.parentId = parentId;
    this.code = code.trim();
    this.name = name.trim();
    this.path = parentPath + this.code + "/";
    this.sort = sort;
    this.enabled = true;
    this.createdBy = actorId;
    this.updatedBy = actorId;
  }

  // ────────────────────────── 行为 ──────────────────────────

  /** 改名。★ 调用方要提示"该部门下的文档需要重新向量化"（名字进前缀与哈希）。 */
  public void rename(String name, UUID actorId) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("部门名称不能为空");
    }
    this.name = name.trim();
    this.updatedBy = actorId;
  }

  public void setSort(int sort, UUID actorId) {
    this.sort = sort;
    this.updatedBy = actorId;
  }

  /**
   * 搬到新父节点之下。
   *
   * <p>★ 子树的 {@code path} 由调用方（{@code OrgService.move}）统一重写 —— 那是"一次 update ... where path like
   * 旧前缀%"，放在这里做会让领域对象持有仓储。
   */
  public void moveTo(UUID parentId, String newPath, UUID actorId) {
    this.parentId = parentId;
    this.path = newPath;
    this.updatedBy = actorId;
  }

  /** 启停。★ 停用后**不再产生新的共享**，但既有共享仍然有效（{@code 07 §10.5} 判据 3）。 */
  public void setEnabled(boolean enabled, UUID actorId) {
    this.enabled = enabled;
    this.updatedBy = actorId;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID parentId() {
    return parentId;
  }

  public String code() {
    return code;
  }

  public String name() {
    return name;
  }

  public String path() {
    return path;
  }

  public int sort() {
    return sort;
  }

  public boolean enabled() {
    return enabled;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
