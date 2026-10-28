package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.net.http.HttpRequest;

/**
 * 出站认证头的<strong>唯一</strong>构造点（{@code #221}）。
 *
 * <p>★ 为什么要有它：{@code HttpRequest.Builder.header(name, value)} 对非法值抛的是 {@code
 * IllegalArgumentException: invalid header value: "Bearer &lt;key&gt;"} ——
 * 这句话<strong>把密钥原文回显进了异常消息</strong>， 而它既不是 {@code BusinessException}，也不在两条出站路径的 {@code catch}
 * 里（{@code UpstreamGateway} 只 catch {@code
 * IOException|InterruptedException|JacksonException}，{@code ChatModelAdapter} 只 catch {@code
 * UpstreamUnreachableException}）⇒ 症状是「填了一个含中文的密钥 ⇒ 接口 500 ⇒ 日志和响应里各留一份密钥」。
 *
 * <p>★ 为什么放在一处而不是两条路径各写一遍：同一个值经过两个构造器（{@link NativeSpringAiChatModel} 与 {@link
 * UpstreamGateway}），任何"只在一处校验"的写法都会变成不对称 —— 本项目已经栽过一次不对称（{@code effectiveKey} 只在非流式路径生效 ⇒「流式
 * 401、非流式正常」）。所以校验必须与"拼头"这个动作<strong>同址</strong>。
 *
 * <p>★ 口径：头值只允许<strong>可打印 ASCII</strong>（{@code 0x20}–{@code 0x7E}）。CR/LF 是头注入；非 ASCII 是 JDK 直接拒收；
 * 制表符虽然在 RFC 里允许，但它出现在密钥里一定是粘贴错误，早失败比晚 401 好排查。报错只说<strong>位置与类别</strong>， 不回显字符本身 —— 否则"修好了
 * 500"却换成了"在错误消息里泄露密钥的一段"。
 */
final class AuthHeader {

  private AuthHeader() {}

  /**
   * 按服务商声明的 {@link AuthStyle} 把密钥放进请求头；{@code style} 不要头值或密钥为空时**什么都不做**。
   *
   * <p>★ "什么都不做"是既有语义，不是遗漏：{@code NONE} 与未配密钥的上游本来就该不带认证（{@code UpstreamGateway.effectiveKey} 负责
   * BEARER 的回落，回落结果作为 {@code key} 传进来）。
   *
   * @param builder 正在构造的请求
   * @param style 服务商声明的认证方式；{@code null} 按无认证处理
   * @param key 已经过回落计算的密钥（可为 {@code null}）
   * @throws BusinessException 密钥含不能放进 HTTP 头的字符（消息里只有位置与类别，没有字符本身）
   */
  static void apply(HttpRequest.Builder builder, AuthStyle style, String key) {
    if (style == null || style.headerName() == null || key == null || key.isBlank()) {
      return;
    }
    builder.header(style.headerName(), style.prefix() + requireHeaderSafe(key));
  }

  private static String requireHeaderSafe(String key) {
    for (int index = 0; index < key.length(); index++) {
      char c = key.charAt(index);
      if (c >= 0x20 && c <= 0x7E) {
        continue;
      }
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "认证密钥含不能放进 HTTP 头的字符（第 " + (index + 1) + " 个字符是" + describe(c) + "）",
          "请检查该模型供应商的密钥：只能含可打印 ASCII 字符，不要带空格、换行或中文");
    }
    return key;
  }

  private static String describe(char c) {
    return switch (c) {
      case '\r' -> "回车";
      case '\n' -> "换行";
      case '\t' -> "制表符";
      default -> c < 0x20 || c == 0x7F ? "控制字符" : "非 ASCII 字符（例如中文）";
    };
  }
}
