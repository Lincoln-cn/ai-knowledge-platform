package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 引用快照的<strong>读侧收窄</strong>：读历史消息时，把当前读到它的人看不见的那几条引用<strong>静默去掉</strong>。
 *
 * <h3>★★ 为什么要单独一个组件（{@code #27} 判据 ③）</h3>
 *
 * <p>写的时候引用是"回答那一刻的检索结果"，读的时候是<strong>另一个人、另一个时刻的权限</strong>：
 *
 * <ul>
 *   <li>会话可以共享（{@code dataScope = SHARED}）⇒ 读的人和问的人不是一个人；
 *   <li>同一个人的密级也会变（换角色、改 {@code iam_role.max_classification}）。
 * </ul>
 *
 * <p>所以"当时能引用"不等于"现在能读"。而 {@code SourceRef} 里带着 {@code full}（那一段正文）与 {@code classification} ——
 * 不过滤就等于<strong>把高密级的正文原样发给低 clearance 的读者</strong>，且页面照常渲染、不报错。
 *
 * <h3>★ 静默去掉，不做"（已隐藏）"占位</h3>
 *
 * <p>{@code 13 §2.2} 与 {@code #27} 判据 ③ 同一条：占位本身也是一种信息 ——
 * 它告诉对方"这里本来有东西、只是你看不到"，于是可以据此推断出自己不该知道的事（存在性与条数）。这是侧信道， 与"看不见的资源回 404 而不是 403"同源（{@code 07 §3.3}
 * 纪律 2）。
 *
 * <h3>★ 三个不许省的分支</h3>
 *
 * <ul>
 *   <li>{@code refs == null} ⇒ 返回 {@code null}（不是空列表）：{@code null} = "这一批引用从来没到过"， {@code []} =
 *       "明确的零命中"，两者在契约上是两件事（{@code Message#references()} 的注释）；
 *   <li>全部被过滤掉 ⇒ 返回<strong>空列表</strong>而不是 {@code null}：它确实"到过且零条可看"， 换成 {@code null}
 *       会让前端把"我一条都看不到"读成"这次没检索"；
 *   <li>判定走 {@link ScopeFilter#canSeeClassification}（{@code 13 §1} 那一条轴）—— ★ 不在这里重写一遍比较逻辑，
 *       否则两处口径迟早漂开（{@code #40} 的形状）。
 * </ul>
 */
@Component
public class ReferenceVisibility {

  private final AccessScopePort accessScope;

  public ReferenceVisibility(AccessScopePort accessScope) {
    this.accessScope = accessScope;
  }

  /**
   * 过滤掉当前读者看不见的引用。
   *
   * @param refs 写的时候落下的引用快照（可为 {@code null}）
   * @return 看得见的那几条；{@code null} 原样返回 {@code null}（语义见类注释）
   */
  public List<SourceRef> visible(List<SourceRef> refs) {
    return filter(refs, accessScope.currentScope());
  }

  /**
   * 纯函数版本：给定范围与引用，返回看得见的那几条。
   *
   * <p>★ 为什么要拆出一个 static：范围本身是一个<strong>值</strong>（{@link ScopeFilter}）， 把它当参数传进来，这条判据就能在没有
   * Spring、没有线程上下文的单测里被确定性地断言。 依赖"从容器里取当前范围"的写法会让单测要么起栈、要么造假端口 —— 两者都会把一条比较逻辑测得很重。
   *
   * @param refs 引用快照（{@code null} 原样返回 {@code null}）
   * @param scope 读者的范围
   * @return 看得见的那几条
   */
  static List<SourceRef> filter(List<SourceRef> refs, ScopeFilter scope) {
    if (refs == null) {
      return null;
    }
    // ★ 标签那一维这里传 null：引用快照只冻结了等级、没有标签（SourceRef 的 11 个字段里没有它）。
    //   传 null 在 canSeeClassification 里等于"不按标签限制"，这是**当前**的正确语义 ——
    //   若将来 SourceRef 加了标签，这里必须跟着改（否则标签越权会从这个洞漏过去）。
    return refs.stream()
        .filter(ref -> scope.canSeeClassification(ref.classification(), null))
        .toList();
  }

  /**
   * 一条消息的引用收窄（{@code Message#references()} 为 {@code null} 时仍是 {@code null}）。
   *
   * @param message 消息
   * @return 看得见的引用
   */
  public List<SourceRef> visibleRefs(Message message) {
    return visible(message.references());
  }
}
