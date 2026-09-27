package cn.lincoln.aiplatform.identity.application;

/**
 * 一张图形验证码。
 *
 * @param id 验证码 id。登录时要把它一起回传 —— ★ 校验靠 id 而不是靠服务端会话， 因为我们本来就是无状态的（{@code 08 §4.2.1} 的 STATELESS）。
 * @param image {@code data:image/png;base64,...}，前端直接塞进 {@code <img src>}
 */
public record CaptchaView(String id, String image) {}
