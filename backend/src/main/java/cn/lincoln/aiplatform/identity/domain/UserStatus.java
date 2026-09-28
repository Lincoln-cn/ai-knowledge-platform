package cn.lincoln.aiplatform.identity.domain;

/**
 * 用户状态。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code ACTIVE / DISABLED / PENDING_REVIEW}），
 * 标识符 = 线上值 = 库存值三者同名。展示文案（"正常 / 停用 / 待审核"）只出现在前端 label 映射里， <strong>不进标识符、不进线上值、不进库</strong> ——
 * 机器码稳定、可比较、可排序；文案随时可改而不影响契约与数据。
 */
public enum UserStatus {
  ACTIVE,
  DISABLED,
  PENDING_REVIEW
}
