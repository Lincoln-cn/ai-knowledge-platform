package cn.lincoln.aiplatform.platform.domain;

/**
 * 业务错误码（{@code 08 §4.1} 的"错误码是代码常量，不是散落的字面量"）。
 *
 * <p>★ <strong>唯一真源是前端冻结契约</strong> {@code frontend/src/api/types.ts} 的 {@code ErrorCode}。
 * 本枚举只收录契约里<strong>已经写死</strong>的值 —— 新增一个码的正确顺序是 <em>先改契约，再改这里</em>，不是反过来。契约注释写明"每模块 1000 段"，
 * 所以尚未占用的段位不在这里预埋（未用到的常量会把"契约与实现一致"这件事变模糊）。
 *
 * <p>分段现状（按契约）：{@code 4xx} 与 HTTP 对齐 · {@code 2000} 段 modelcatalog · {@code 3000} 段 knowledge ·
 * {@code 6000} 段 assistant。
 */
public enum ErrorCode {

  /** 成功。包络里 {@code code} 为 0 才代表成功（契约 {@code ApiResult} 的约定）。 */
  OK(0, "ok"),

  /** 未认证：令牌缺失、过期、验签失败。前端据此触发<em>静默刷新并重放一次</em>（{@code 08 §4.0} 第 2 行）。 */
  UNAUTHORIZED(401, "未认证"),

  /** 已认证但无权限。★ 可见范围之外的资源<strong>不返这个码</strong>，返 {@link #NOT_FOUND}（{@code 08 §4.0} 第 4 行）。 */
  FORBIDDEN(403, "无权限"),

  /**
   * 资源不存在<strong>或不可见</strong>。
   *
   * <p>★ "看不见"与"不存在"共用同一个码，是刻意的：区分它们等于告诉越权者"这个 ID 真实存在"。
   */
  NOT_FOUND(404, "资源不存在"),

  /** 模型尚未加载完成等可重试状态（modelcatalog）。 */
  MODEL_NOT_READY(2001, "模型未就绪"),

  /** 知识库已有向量化内容，向量模型被锁定（knowledge · 不变量 {@code KB-I4}）。 */
  EMBEDDING_LOCKED(3001, "向量模型已锁定"),

  /** 分段超长（knowledge · 不变量 {@code SEG-I1}）。 */
  SEGMENT_TOO_LONG(3002, "分段超长"),

  /** 应用发布前校验不通过（assistant · 不变量 {@code AS-I2~I5}）。 */
  PUBLISH_CHECK_FAILED(6001, "应用发布前校验不通过");

  private final int code;
  private final String defaultMessage;

  ErrorCode(int code, String defaultMessage) {
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  /** 契约里的数值码。 */
  public int code() {
    return code;
  }

  /** 兜底文案。调用点通常给更具体的 message，这个只在没给的时候用。 */
  public String defaultMessage() {
    return defaultMessage;
  }

  /** 按契约数值码反查。未知码返回 {@link #FORBIDDEN} 之前先返回 null —— 由调用点决定怎么兜底。 */
  public static ErrorCode fromCode(int code) {
    for (ErrorCode each : values()) {
      if (each.code == code) {
        return each;
      }
    }
    return null;
  }
}
