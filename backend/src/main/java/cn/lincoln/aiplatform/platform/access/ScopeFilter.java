package cn.lincoln.aiplatform.platform.access;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 当前请求的**可见范围**（{@code 08 §4.4.2}）。
 *
 * <p>★ 它是"范围"，不是"动作权限"：{@link #scope} 回答"能看到<em>哪些</em>"， {@link #maxClassification}
 * 回答"能看到<em>多敏感的</em>"。两者正交，且都<strong>进查询谓词</strong>。
 *
 * <p>★ 密级字段与 {@code dataScope} 放在同一个 record 里是刻意的（{@code 13 §8.3}）：
 * 它们最终由<strong>同一个谓词构建器</strong>消费 —— 分两次做等于同一个函数被改两遍， 而第二次改的人未必知道第一次的约定。
 *
 * @param me 当前用户
 * @param scope 数据范围三档
 * @param myRoles 当前用户的角色代码（共享判定要按角色比对）
 * @param maxClassification 密级上限（clearance）：能看到"不高于它"的资源
 * @param clLabels 可涉的密级标签；★ 空集表示**不按标签限制**（而不是"什么标签都看不到"）
 * @param myOrgIds ★ <strong>我所属部门 + 其全部下级</strong>的 id 集合（{@code 07 §十一} 的第三个消费位：用户身份）。
 *     <p>为什么是"集合"而不是"我一个部门 id"：共享给某部门时口径是<strong>含下级</strong>（用户口径）， 而含下级这件事**在构造范围时**就展开好（一次
 *     {@code path} 前缀查询），而不是在 {@link #canSee} 里查 —— {@code canSee} 是对**每条资源**调用的，在那里查库就是 N+1。
 *     <p>★ 空集 = **看不见任何部门共享**（fail-closed）。这个方向是刻意的：漏配只会"少看见"， 不会"多看见"。
 */
public record ScopeFilter(
    UUID me,
    DataScope scope,
    Set<String> myRoles,
    ClassificationLevel maxClassification,
    Set<String> clLabels,
    Set<String> myOrgIds) {

  /**
   * 不带部门归属的范围（{@code myOrgIds} 为空 ⇒ 看不见任何部门共享）。
   *
   * <p>★ 这个重载是为了让既有调用点不必全改；它的默认值是 <strong>fail-closed</strong> 的 （看不见，而不是看得见）。要验 ORG 分支请用规范构造器显式传第
   * 6 个参数。
   */
  public ScopeFilter(
      UUID me,
      DataScope scope,
      Set<String> myRoles,
      ClassificationLevel maxClassification,
      Set<String> clLabels) {
    this(me, scope, myRoles, maxClassification, clLabels, Set.of());
  }

  /**
   * 归属与可见性维度：这条资源对我可见吗（{@code 07 §3.3} 的唯一判定）。
   *
   * <p>★ 这里<strong>不检查"我有没有 view 权限点"</strong>：那是"动作权限"， 由 {@code @PreAuthorize}
   * 在方法级管。两件事混在一个方法里，会让"范围判定"与"授权声明" 互相纠缠，最后两处都难改。
   *
   * <p>★ 时限：{@code SHARED} 的授权会带 {@code expiresAt}，必须带上当前时刻判断， 否则会写出一条永远不会过期的共享。
   */
  public boolean canSee(OwnedResource resource, Instant now) {
    if (me == null) {
      // ★ 未认证：什么都看不见。注意它**不是**"看得见 public 的资源" ——
      //   免登录面走的是公开凭证那条独立链路（07 §3.1：public 是"登录用户里人人可见"）
      return false;
    }
    if (scope == DataScope.ALL) {
      return true;
    }
    if (me.equals(resource.ownerId())) {
      return true;
    }
    Visibility visibility = resource.visibility();
    if (visibility == Visibility.PUBLIC) {
      // ★ public = "登录用户里人人可见"。免登录是另一条链路（公开凭证），不在这里
      return true;
    }
    if (visibility == Visibility.SHARED) {
      // ★ `own` 是**个人沙箱**（07 §3.2）：连"别人分享给我的"也不看。
      //   少了这一行，own 与 shared 就退化成同一档 —— 而那正是 07 册特意保留两档的理由
      //   （"我只管我建的"与"我用别人给我的"是两个不同的人）。
      if (scope == DataScope.OWN) {
        return false;
      }
      return resource.grants().stream()
          .filter(grant -> grant.isEffectiveAt(now))
          .anyMatch(this::grantCoversMe);
    }
    return false;
  }

  /**
   * 密级维度：这个密级与标签我能看吗（{@code 13 §1}）。
   *
   * <p>★ 与 {@link #canSee} 是<strong>两条独立的轴</strong>：一条资源可以归属上看得见、 但密级上不该看。把它们合成一个返回值会让人以为"看得见就够了"。
   */
  public boolean canSeeClassification(ClassificationLevel level, Set<String> labels) {
    if (maxClassification == null) {
      return false;
    }
    if (!level.within(maxClassification)) {
      return false;
    }
    // ★ 空集 = 不按标签限制。若写成"必须相交"，那么没配任何标签的角色会看不到一切 ——
    //   而那正是绝大多数角色的状态（标签是可选字段）
    if (clLabels == null || clLabels.isEmpty() || labels == null || labels.isEmpty()) {
      return true;
    }
    return labels.stream().anyMatch(clLabels::contains);
  }

  /** 无限制的范围（系统内部任务、定时任务用；★ 不要用在请求路径上）。 */
  public static ScopeFilter unrestricted(UUID me, Set<String> myRoles) {
    return new ScopeFilter(me, DataScope.ALL, myRoles, ClassificationLevel.CONFIDENTIAL, Set.of());
  }

  /**
   * 什么都看不见（未认证）。
   *
   * <p>★ 它不是 {@link #unrestricted} 的对偶、也不该当兜底随手用： "无限制"必须是<strong>显式选择</strong>，不能是"没解析出来"的默认值 ——
   * 否则任何一次令牌结构变更都会静默地把所有人变成全平台可见。
   */
  public static ScopeFilter nobody() {
    return new ScopeFilter(null, DataScope.OWN, Set.of(), null, Set.of());
  }

  private boolean grantCoversMe(Grant grant) {
    Subject subject = grant.subject();
    if (subject == null) {
      return false;
    }
    // ★ kind 与 id 一起比 —— 部门「3」不是角色「3」
    return switch (subject.kind()) {
      case USER -> me != null && me.toString().equals(subject.id());
      case ROLE -> myRoles != null && myRoles.contains(subject.id());
      // ★ 部门（`07 §十一` 消费位③ → ①`kb_knowledge_base_grant.subject_id`）：
      //   比的是"我所属部门**及其全部下级**"这个集合 —— 它是**在构造范围时展开好的**（见 record 的 javadoc），
      //   ★ 含下级这一条是用户口径，谁把它改成"只比我自己那一个"就会让"共享给上级部门的库，
      //     下级部门的人看不见" —— `ScopeFilterTest` 里那条金丝雀就是守它的。
      case ORG -> myOrgIds != null && myOrgIds.contains(subject.id());
      // ★ 这两个是枚举里的**预留值**（契约早就留了位，至今零消费方）：显式 false 而不是 default ——
      //   于是将来谁给 SubjectKind 加第 6 个值，**编译器会强迫他在这里做决定**，
      //   而不是让它静默地掉进 default（那正是 ORG 这次被抓出来之前的样子，见 Issue #38）。
      case GROUP, LINK -> false;
    };
  }
}
