package cn.lincoln.aiplatform.platform.domain;

import java.io.Serial;

/**
 * 业务异常 —— 所有"可预期"的失败都走它，由 Web 层统一映射成响应包络。
 *
 * <p>★ 它与 {@link ErrorCode} 的分工：{@code code} 是<strong>给程序和前端判断</strong>的， 不可读也不可翻译；{@code message}
 * 与 {@code hint} 是<strong>给人看</strong>的。 三者的关系见 {@code 08 §4.1}："只给错误码不给可行动的原因，等于让用户停在原地。"
 *
 * <p>★ {@code hint} 是一等字段（{@code 08 §4.1} 第 3 条），不是可选装饰 —— 所以这里给了带 hint 的构造入口，并在 {@code
 * ApiResponse} 上原样透出。 判断要不要给 hint 的标准是：<em>用户看到 message 之后知道下一步做什么吗</em>。 知道就不必给（如"问题不能为空"），不知道就必须给
 * （如"分段超长" → "把该段拆小，或调大分段上限"）。
 *
 * <p>不变量：{@link #code} 永不为 null。（把 {@code HttpStatus} 或裸 int 传进来是常见错误 —— 那样"看不见的资源返 404 而非
 * 403"这类约定就会在调用点各写一遍。）
 */
public class BusinessException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final String hint;
  private final Object details;

  public BusinessException(ErrorCode code, String message) {
    this(code, message, null, null);
  }

  public BusinessException(ErrorCode code, String message, String hint) {
    this(code, message, hint, null);
  }

  /**
   * 带<strong>结构明细</strong>的业务异常（{@code #260}）。
   *
   * <p>★ 什么时候需要它：失败的原因是<strong>一张清单</strong>而不是一句话时（发布前校验的每条 issue）。 把清单压进 {@code message}/{@code
   * hint} 的文本，界面就只能原样念一遍 —— 它没法高亮到具体节点，也没法点了跳过去。
   *
   * <p>★ 为什么是 {@code Object}：明细的类型属于<strong>抛出它的那一层</strong>（这里是 assistant 域的 {@code
   * WorkflowValidationService.Issue}）。若在这里写成那个类型，{@code platform} 就要依赖 assistant 域 ——
   * 模块边界闸门会当场抓红，而且"平台层认识某个业务域的形状"本来就不该发生。
   *
   * <p>★ 它不是"顺手带点信息"的口袋：没有明确结构就别填，{@code message} + {@code hint} 已经够用。
   */
  public BusinessException(ErrorCode code, String message, String hint, Object details) {
    super(message);
    this.code = code;
    this.hint = hint;
    this.details = details;
  }

  public ErrorCode code() {
    return code;
  }

  /** 可行动的下一步；没有具体建议时为 null。 */
  public String hint() {
    return hint;
  }

  /** 结构化明细（如校验清单）；没有时为 null —— 此时失败仍是"只有话、没有数据体"。 */
  public Object details() {
    return details;
  }
}
