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

  /**
   * 请求参数不合法。
   *
   * <p>★ 它是 401/403/404 那三个"与 HTTP 对齐"的码的<strong>同族补全</strong>：请求体校验失败总得有个码。
   * 前端不针对它分支（会走通用错误渲染），所以加它不破坏任何东西。
   *
   * <p>⚠️ 曾经这里写着"契约里还没有这个码" —— <strong>那句话已经过时了</strong>：契约 {@code frontend/src/api/types.ts} 的
   * {@code ErrorCode} 里现在有 {@code BAD_REQUEST: 400}。 留这段是因为它踩过一次：mock 曾按这句判断自造了一套 4001–4006
   * 的"影子编号"， 而后端一律返 400 ⇒ 同一份契约两种取值（issue #8）。 ⇒ <strong>契约与后端任一侧新增码，都必须同批核对另一侧</strong>。
   */
  BAD_REQUEST(400, "请求参数不合法"),

  /**
   * 登录失败次数超限，账号被临时锁定（等保 · 三级："应对登录失败做出处理"）。
   *
   * <p>★ 它是一个<strong>独立码</strong>而不是并入 401：前端需要对它做不同的呈现 —— 显示"还剩多少分钟"并禁用登录按钮，而不是提示"账号或密码不正确"。
   */
  ACCOUNT_LOCKED(1001, "账号已被锁定"),

  /** 新密码不满足强度要求（等保："口令应有复杂度要求"）。message 里带具体哪一条不满足。 */
  PASSWORD_TOO_WEAK(1002, "密码强度不足"),

  /**
   * 验证码缺失、已过期或填写不正确（等保：登录带验证码）。
   *
   * <p>★ 三者共用一个码是有意的 —— 对使用者来说下一步动作完全一样（刷新验证码重填）。
   */
  CAPTCHA_INVALID(1003, "验证码不正确"),

  /**
   * 改密时提供的老密码不正确。
   *
   * <p>★★ 它<strong>必须有自己的码，不能用 {@link #UNAUTHORIZED}</strong>：后者的语义是
   * 「令牌缺失、过期、验签失败」，而前端**据此触发静默刷新并重放一次**（{@code 08 §4.0} 第 2 行）。 「表单里填错了老密码」不是会话失效 ⇒ 用 401
   * 会让前端白跑一次刷新 + 一次重放， 排障时也分不清"哪个 401 是会话、哪个是字段"。
   *
   * <p>★ 这个码不是 4xx 码值 ⇒ 由 {@code httpStatusOf} 的默认分支落到 HTTP <b>400</b>。
   */
  OLD_PASSWORD_MISMATCH(1004, "原密码不正确"),

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
  /** 上游模型服务不可用（连不上 / 响应不合规）—— 与 400 分开：用户改不动请求，下一步是查服务或重试。 */
  UPSTREAM_UNAVAILABLE(2002, "上游模型服务不可用"),

  /** 知识库已有向量化内容，向量模型被锁定（knowledge · 不变量 {@code KB-I4}）。 */
  EMBEDDING_LOCKED(3001, "向量模型已锁定"),

  /** 分段超长（knowledge · 不变量 {@code SEG-I1}）。 */
  SEGMENT_TOO_LONG(3002, "分段超长"),

  /** 应用发布前校验不通过（assistant · 不变量 {@code AS-I2~I5}）。 */
  PUBLISH_CHECK_FAILED(6001, "应用发布前校验不通过"),

  /**
   * ★★ 公开面四态（免登录的访问凭证 / 分享链接）—— 一个数字一族，<strong>不可合并</strong>。
   *
   * <p>这四态<strong>不是同一个错误的四种措辞</strong>：它们指向不同的<strong>下一步动作</strong>，
   * 合并成一句"链接无效"会让人按错的假设去等（已停用可以恢复、已撤销不能）。 四态的界面文案在 {@code frontend/src/utils/public-gate.ts}（唯一出口）。
   *
   * <p>★ 这一段是 <strong>2026-10-13（第 16 周末）</strong>补的：那之前这组码<strong>只活在 mock 与前端</strong> ——
   * 契约与后端枚举两侧都没有它， 而第 16 周交付的前端 F3 已经按它分四态了（{@code 13 §8.3} 留下的两格之一，见 {@code ROADMAP §11.2} 第 17
   * 周）。 ★ 顺序照 {@code ErrorCode} 的类注释：<strong>先改契约、再改这里</strong>（契约 {@code
   * frontend/src/api/types.ts} 的 {@code ErrorCode} 是唯一真源），由 {@code frontend/check_error_codes.py}
   * 每轮比对两侧。
   *
   * <p>★ 与 {@code GlobalExceptionHandler.httpStatusOf} 的关系：它们的码值（{@code 40001–59999}）与 HTTP
   * 状态<strong>不重合</strong> （{@code HttpStatus.resolve} 在 {@code 5xx} 之上返回 {@code null}）⇒ 会落进 {@code
   * GlobalExceptionHandler} 的 {@code default} 分支，**HTTP 仍是 404 / 400，业务码只在包络里**。这不是缺陷，是这组码的用法：
   * <strong>判据取包络的 {@code code}，不取 HTTP 状态</strong>（前端 {@code lib/http.ts} 在无包络时才回落 HTTP 状态）。
   *
   * <p>★ 为什么没有 {@code 40405}（内容升密 ⇒ 原链接立即失效）：按 {@code 13 §5.3} 的 <strong>(a)</strong> 口径，
   * 升密后<strong>凭证仍然有效、链接仍打得开</strong>，只是那份内容被同一个谓词滤掉 ⇒ <strong>不需要第五个码</strong>。 那一档若将来真要做（(b)
   * 自动撤销凭证），届时<strong>先改契约再加这里</strong>。
   */
  /** 公开面：认不出这个 token（链接本身不对，重试无意义）。 */
  PUBLIC_TOKEN_UNKNOWN(40401, "访问凭证无效"),
  /** 公开面：已撤销（★ 终态，不能重新启用）。 */
  PUBLIC_TOKEN_REVOKED(40402, "访问凭证已被撤销"),
  /** 公开面：已过期（有效期创建时定下、之后不能改）。 */
  PUBLIC_TOKEN_EXPIRED(40403, "访问凭证已过期"),
  /** 公开面：已停用（★ 可恢复：重新启用即可）。 */
  PUBLIC_TOKEN_DISABLED(40404, "访问凭证已被停用");

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
