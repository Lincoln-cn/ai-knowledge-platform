package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.Subject;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一条共享授权的持久化形状（{@code 07 §8.1} 的 {@code Grant}）。
 *
 * <p>★ 用子表而不是 JSON 列：grants 必须能进 {@code EXISTS} 谓词（{@code 07 §8.5}）， 而 {@code 02 §5.1} 明确「JSON
 * 列仅用于不参与查询的结构化数据」。
 *
 * <p>★ 形状取「能长的」那种（subject + actions + expires_at + can_delegate）， 而不是 {@code {userIds,
 * roleCodes}}：加「共享给部门」= 加一个 {@link SubjectKind}， 加「只读/可编辑」= 每条 grant 多一个 action，都不动表结构。
 */
@Entity
@Table(name = "kb_knowledge_base_grant")
public class KnowledgeBaseGrant {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "knowledge_base_id", nullable = false)
  private KnowledgeBase knowledgeBase;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_kind", nullable = false, length = 16)
  private SubjectKind subjectKind;

  /** USER 时是 UUID 字符串；ROLE 时是角色 code。与 {@link #subjectKind} 一起比对。 */
  @Column(name = "subject_id", nullable = false, length = 64)
  private String subjectId;

  /** 授权动作，与权限点同一套词汇。 */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "actions")
  private String[] actions;

  /** 时效；null = 长期有效。判定必须带上时限检查。 */
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "can_delegate", nullable = false)
  private boolean canDelegate;

  protected KnowledgeBaseGrant() {}

  public KnowledgeBaseGrant(
      UUID id,
      KnowledgeBase knowledgeBase,
      SubjectKind subjectKind,
      String subjectId,
      String[] actions,
      Instant expiresAt,
      boolean canDelegate) {
    this.id = id;
    this.knowledgeBase = knowledgeBase;
    this.subjectKind = subjectKind;
    this.subjectId = subjectId;
    this.actions = actions == null ? new String[0] : actions;
    this.expiresAt = expiresAt;
    this.canDelegate = canDelegate;
  }

  /** 映射成平台的 {@link Grant} 值对象（判定只认平台类型，不认本实体）。 */
  public Grant toGrant() {
    return new Grant(new Subject(subjectKind, subjectId), Set.of(actions), expiresAt, canDelegate);
  }

  public SubjectKind subjectKind() {
    return subjectKind;
  }

  public String subjectId() {
    return subjectId;
  }

  public Instant expiresAt() {
    return expiresAt;
  }
}
