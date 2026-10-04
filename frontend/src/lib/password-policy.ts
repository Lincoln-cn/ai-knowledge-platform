/**
 * 口令强度口径（前端侧镜像）。
 *
 * ★ **真源在后端**：`backend/.../identity/domain/PasswordPolicy.java`。
 * 这里只是把**同一套规则**提前到表单上，让用户在**提交之前**就知道哪里不合格 ——
 * 而不是填完、提交、被拒、再猜一次。
 *
 * ★ 后端仍是最后一道闸：前端拦下**不等于**去掉后端校验。
 * 前端校验是体验，不是安全边界（与「前端隐藏入口只是体验」同一条纪律）。
 *
 * ⚠️ **这是"两处各写一份"**，天然会漂移（本项目对这类形状一向警惕）。
 * 更好的形状是后端把策略**下发**下来（像 `upload-policy` 那样），届时本文件应退化成"读下发的值"。
 * 在那之前，改后端规则时**必须同批改这里** —— 两处的常量与文案都刻意保持一字不差。
 */

/** 最短长度（等保三级的口径是 ≥8）。 */
export const PASSWORD_MIN_LENGTH = 8

/**
 * 最长长度。
 *
 * ★ 与后端同一条理由：BCrypt 在 72 字节处会**静默截断**，不设上限时用户输入 200 位密码
 * 真正的强度只到第 72 字节，而界面会告诉他"很长很安全"。
 */
export const PASSWORD_MAX_LENGTH = 64

/** 至少命中的字符类别数（大写 / 小写 / 数字 / 特殊）。 */
export const PASSWORD_MIN_CHARACTER_KINDS = 3

/**
 * 检查口令是否合格。
 *
 * @param password 明文口令
 * @param username 账号名（用于"不含账号名"这一条）；可为空
 * @returns 违规原因；合格时返回 `null`
 */
export function passwordViolation(password: string, username?: string | null): string | null {
  if (!password || !password.trim()) {
    return '密码不能为空'
  }
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `密码长度至少 ${PASSWORD_MIN_LENGTH} 位`
  }
  if (password.length > PASSWORD_MAX_LENGTH) {
    return `密码长度不能超过 ${PASSWORD_MAX_LENGTH} 位`
  }
  if (characterKinds(password) < PASSWORD_MIN_CHARACTER_KINDS) {
    return `密码需包含大写字母、小写字母、数字、特殊字符中的至少 ${PASSWORD_MIN_CHARACTER_KINDS} 类`
  }
  if (username && username.trim() && password.toLowerCase().includes(username.trim().toLowerCase())) {
    return '密码不能包含账号名'
  }
  return null
}

/** 命中的字符类别数（大写 / 小写 / 数字 / 特殊）。 */
function characterKinds(password: string): number {
  let kinds = 0
  if (/[A-Z]/.test(password)) kinds += 1
  if (/[a-z]/.test(password)) kinds += 1
  if (/\d/.test(password)) kinds += 1
  if (/[^A-Za-z0-9]/.test(password)) kinds += 1
  return kinds
}
