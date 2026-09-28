package cn.lincoln.aiplatform.platform.access;

/**
 * 共享主体的种类（{@code 07 §8.1}）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code USER / ROLE / ORG / GROUP / LINK}）， 标识符
 * = 线上值 = 库存值三者同名。展示文案不进标识符、不进线上值、不进库。
 *
 * <p>MVP 只会真正填 {@link #USER} 与 {@link #ROLE}；{@code ORG} / {@code GROUP} / {@code LINK}
 * 保留在枚举里，因为"共享给部门"是**一定会来**的需求，而多一个枚举值比多一次契约变更便宜。
 */
public enum SubjectKind {
  USER,
  ROLE,
  ORG,
  GROUP,
  LINK
}
