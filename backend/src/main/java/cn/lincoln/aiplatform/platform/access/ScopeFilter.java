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
 */
public record ScopeFilter(
    UUID me,
    DataScope scope,
    Set<String> myRoles,
    ClassificationLevel maxClassification,
    Set<String> clLabels) {

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
      default -> false;
    };
  }
}
