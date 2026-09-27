package cn.lincoln.aiplatform.platform.access;

/**
 * 共享主体的种类（{@code 07 §8.1}）。
 *
 * <p>MVP 只会真正填 {@link #user} 与 {@link #role}；{@code org} / {@code group} / {@code link}
 * 保留在枚举里，因为"共享给部门"是**一定会来**的需求，而多一个枚举值比多一次契约变更便宜。
 */
public enum SubjectKind {
  user,
  role,
  org,
  group,
  link
}
