package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 把安全层的失败写成响应包络（{@code 08 §4.1}）。
 *
 * <p>★ 两个刻意的选择：
 *
 * <ol>
 *   <li><strong>HTTP 状态码与包络里的 code 一起给</strong>。这不是重复：前端 {@code lib/http.ts:84} 是看 <em>HTTP</em>
 *       401 才决定"静默刷新并重放一次"的。 只把 401 放进 body 里，那条逻辑永远不会触发 —— 表现为"登录过期后被直接踢回登录页"，
 *       而这属于最难查的一类问题：功能看起来"能用"，只是体验不对。
 *   <li><strong>手工拼 JSON，不注入 ObjectMapper</strong>。这两个响应体只有固定几个字段， 消息文本由本包自己控制；为此引入一个序列化器（且 Boot 4
 *       已切到 Jackson 3、 包名与 2 不同）不值得。{@link #escape} 是为了万一将来有人改了文案。
 * </ol>
 */
final class ErrorResponses {

  private ErrorResponses() {}

  static void unauthorized(HttpServletResponse response) throws IOException {
    write(
        response,
        HttpServletResponse.SC_UNAUTHORIZED,
        ErrorCode.UNAUTHORIZED,
        "未认证",
        "登录已过期或令牌无效，请重新登录");
  }

  static void forbidden(HttpServletResponse response, boolean authenticated) throws IOException {
    if (!authenticated) {
      // 没认证就是没认证 —— 不要用 403 混淆两种情形
      unauthorized(response);
      return;
    }
    write(
        response,
        HttpServletResponse.SC_FORBIDDEN,
        ErrorCode.FORBIDDEN,
        "无权限",
        "请联系管理员为你的角色勾选该权限点");
  }

  private static void write(
      HttpServletResponse response, int httpStatus, ErrorCode code, String message, String hint)
      throws IOException {
    response.setStatus(httpStatus);
    response.setContentType("application/json; charset=utf-8");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"code\":"
                + code.code()
                + ",\"message\":\""
                + escape(message)
                + "\",\"data\":null,\"hint\":\""
                + escape(hint)
                + "\"}");
  }

  private static String escape(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(raw.length() + 8);
    for (char c : raw.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
