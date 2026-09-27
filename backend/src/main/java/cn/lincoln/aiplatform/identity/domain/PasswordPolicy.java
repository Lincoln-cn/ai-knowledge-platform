package cn.lincoln.aiplatform.identity.domain;

import java.util.Optional;

/**
 * 密码强度策略（等保 · 三级要求）。
 *
 * <p>等保对"身份鉴别"的口径大致是：口令应有足够长度、并由多种字符类型组成。 本项目落成三条硬规则（{@code app.security.password.*} 可调）：
 *
 * <ol>
 *   <li>长度 ≥ 8
 *   <li>大写字母 / 小写字母 / 数字 / 特殊字符 <strong>四类中至少命中三类</strong>
 *   <li>不含账号名（等保还常提"不含易猜信息"，账号名是最典型的那一个）
 * </ol>
 *
 * <p>★ 返回 {@code Optional<String>} 而不是 {@code boolean}：违规原因必须能被人读懂。
 * 一个只说"密码不符合要求"的校验等于让用户自己猜，而他会去试那些更弱的密码。
 *
 * <p>★ 这里**不做**"定期更换口令""不得与最近 N 次重复"—— 那两条要存口令历史， 而"历史哈希"本身是一个新的攻击面（且 NIST SP 800-63B 对强制轮换持保留态度）。
 * 若等保测评明确要求，再按需求方的口径补，并同时确定历史保留轮数。
 */
public final class PasswordPolicy {

  /** 最短长度。等保三级的口径是 ≥8。 */
  public static final int MIN_LENGTH = 8;

  /**
   * 最长长度。
   *
   * <p>★ 设上限不是为了安全（长口令是好东西），而是因为 BCrypt 在 72 字节处会**静默截断**： 若不限制，用户输入 200 位密码时真正的强度只到第 72
   * 字节，而界面会告诉他"很长很安全"。 这里直接拒绝，让"长度"这件事对用户是诚实的。
   */
  public static final int MAX_LENGTH = 64;

  /** 至少命中的字符类别数。 */
  public static final int MIN_CHARACTER_KINDS = 3;

  private PasswordPolicy() {}

  /**
   * 检查口令是否合格。
   *
   * @param rawPassword 明文口令
   * @param username 账号名（用于"不含账号名"这一条）；可为 null
   * @return 违规原因；合格时为空
   */
  public static Optional<String> violationOf(String rawPassword, String username) {
    if (rawPassword == null || rawPassword.isBlank()) {
      return Optional.of("密码不能为空");
    }
    if (rawPassword.length() < MIN_LENGTH) {
      return Optional.of("密码长度至少 " + MIN_LENGTH + " 位");
    }
    if (rawPassword.length() > MAX_LENGTH) {
      return Optional.of("密码长度不能超过 " + MAX_LENGTH + " 位");
    }
    if (characterKinds(rawPassword) < MIN_CHARACTER_KINDS) {
      return Optional.of("密码需包含大写字母、小写字母、数字、特殊字符中的至少 " + MIN_CHARACTER_KINDS + " 类");
    }
    if (username != null
        && !username.isBlank()
        && rawPassword.toLowerCase().contains(username.toLowerCase())) {
      return Optional.of("密码不能包含账号名");
    }
    return Optional.empty();
  }

  /** 命中的字符类别数（大写 / 小写 / 数字 / 特殊）。 */
  static int characterKinds(String rawPassword) {
    boolean upper = false;
    boolean lower = false;
    boolean digit = false;
    boolean other = false;

    for (char c : rawPassword.toCharArray()) {
      if (Character.isUpperCase(c)) {
        upper = true;
      } else if (Character.isLowerCase(c)) {
        lower = true;
      } else if (Character.isDigit(c)) {
        digit = true;
      } else {
        other = true;
      }
    }
    return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
  }
}
