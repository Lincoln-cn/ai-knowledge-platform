package cn.lincoln.aiplatform.identity.application;

/**
 * 一张图形验证码。
 *
 * @param id 验证码 id。登录时要把它一起回传 —— ★ 校验靠 id 而不是靠服务端会话， 因为我们本来就是无状态的（{@code 08 §4.2.1} 的 STATELESS）。
 * @param image {@code data:image/png;base64,...}，前端直接塞进 {@code <img src>}
 * @param required 是否需要用户填写（{@code app.security.captcha.enabled} 的镜像）。 ★ 前端据此决定要不要渲染输入框 ——
 *     开关关掉时若还渲染一个"看着在要、实际不认"的框， 就会把「关掉验证码」伪装成产品 bug（本次就是这么被误导的）。 契约侧是可选字段（{@code
 *     frontend/src/api/identity.ts} 的 {@code required?: boolean}）： 未下发时前端仍按"需要"处理，兼容旧后端。
 */
public record CaptchaView(String id, String image, boolean required) {}
