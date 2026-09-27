package cn.lincoln.aiplatform.platform.access;

import java.util.List;
import java.util.UUID;

/**
 * "有归属的资源"—— 所有用户产出的资源都实现它（知识库 / 应用 / 会话）。
 *
 * <p>★ 这个接口存在的意义是让"<strong>可见性判定只有一处实现</strong>"（{@code 07 §3.3}） 有<em>类型基础</em>：没有它，每个模块只能各写一份"看
 * ownerId、再看 visibility、再看 grants" 的字段判断 —— 那就是同一段逻辑复制 N 遍，而这 N 遍一定会慢慢分叉。
 *
 * <p>★ 它放在 {@code platform} 而不是 {@code identity}：所有模块已经依赖 {@code platform}， 于是 {@code 01}
 * 的依赖矩阵<strong>零改动</strong>；若放 {@code identity}， 每个要过滤资源的模块都得新增一条指向 identity 的边（{@code 08 §4.4.2}
 * 的取舍表）。
 *
 * <p>⚠️ {@code ownerId} <strong>不可变</strong>：转让是显式动作，不是改个字段； 且 {@code POST}
 * 时由后端写入，<em>不接受前端传</em>（否则用户能把资源挂到别人名下）。
 */
public interface OwnedResource {

  UUID ownerId();

  Visibility visibility();

  /** 仅 {@link Visibility#SHARED} 时有意义。 */
  List<Grant> grants();
}
