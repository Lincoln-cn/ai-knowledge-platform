package cn.lincoln.aiplatform.platform.access;

/**
 * 数据范围与密级的<strong>判定端口</strong>（{@code 08 §4.4.2}）。
 *
 * <p>★ 接口放 {@code platform}、实现放 {@code identity}，是这份设计的核心取舍：
 *
 * <table>
 *   <tr><th>选择</th><th>结果</th></tr>
 *   <tr><td>端口在 platform</td><td>所有模块已依赖 platform ⇒ {@code 01} 的依赖矩阵<strong>零改动</strong></td></tr>
 *   <tr><td>实现在 identity</td><td>"可见性判定只在后端一处实现"有<strong>一个具体的类</strong>来承担，可评审、可测试</td></tr>
 * </table>
 *
 * <p>★ 实现必须<strong>唯一</strong>：判据见 {@code AccessScopeAdapter} 的类注释与 {@code
 * AccessScopeUniquenessTest} 的金丝雀。第二处实现出现时，构建会红 —— 因为"两处判定"意味着迟早有一处不更新，而它不会报错。
 */
public interface AccessScopePort {

  /**
   * 当前请求的可见范围。
   *
   * <p>★ 调用方应把返回的范围<strong>拼进查询</strong>（{@code Specification}）， 而不是查完再筛 —— 后者会让 {@code total}
   * 与分页数全错： 用户看到"共 100 条"却只翻得出 3 条（{@code 08 §4.4.3} 纪律 1）。
   */
  ScopeFilter currentScope();

  /** 单条资源是否可见。★ 看不见的资源要返回 <strong>404</strong> 而不是 403（纪律 2）。 */
  boolean canSee(OwnedResource resource);
}
