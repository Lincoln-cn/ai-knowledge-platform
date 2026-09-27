package cn.lincoln.aiplatform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 密码强度策略（等保 · 三级：口令长度与复杂度要求）。
 *
 * <p>★ 断言用 {@code violationOf(...).orElseThrow()} 取出消息再查子串 —— 而不是 {@code
 * assertThat(optional).contains("某片段")}：后者在 AssertJ 里是 "<em>Optional
 * 的值等于这个字符串</em>"，不是"含这个片段"。用它写会全部失败， 而失败信息只说"expecting present"、不告诉你判断逻辑错了。
 */
class PasswordPolicyTest {

  @Test
  void 太短被拒并说清要求() {
    assertThat(PasswordPolicy.violationOf("Ab1!", "admin").orElseThrow()).contains("至少 8 位");
  }

  @Test
  void 空密码被拒() {
    assertThat(PasswordPolicy.violationOf(null, "admin").orElseThrow()).contains("不能为空");
    assertThat(PasswordPolicy.violationOf("   ", "admin").orElseThrow()).contains("不能为空");
  }

  @Test
  void 字符类别不足被拒() {
    // 只有小写 —— 1 类
    assertThat(PasswordPolicy.violationOf("abcdefgh", "admin").orElseThrow()).contains("至少 3 类");
    // 小写 + 数字 —— 2 类
    assertThat(PasswordPolicy.violationOf("abcdefg1", "admin").orElseThrow()).contains("至少 3 类");
  }

  @Test
  void 三类字符即可通过() {
    assertThat(PasswordPolicy.violationOf("abcdefg1A", "admin")).isEmpty(); // 小写+数字+大写
    assertThat(PasswordPolicy.violationOf("abcdefg1!", "admin")).isEmpty(); // 小写+数字+特殊
    assertThat(PasswordPolicy.violationOf("ABCdefg!", "admin")).isEmpty(); // 大写+小写+特殊
  }

  /** ★ "不含账号名"要**大小写不敏感**地判：攻击者的字典里不会只放小写那一种写法。 */
  @Test
  void 含账号名被拒且大小写不敏感() {
    assertThat(PasswordPolicy.violationOf("Admin@2026x", "admin").orElseThrow()).contains("账号名");
    assertThat(PasswordPolicy.violationOf("ADMIN@2026x", "admin").orElseThrow()).contains("账号名");
  }

  /**
   * ★ 上限存在的理由不是"长口令不好"，而是 BCrypt 在 72 字节处会**静默截断** —— 若不拦，用户输 200 位时真实强度只到第 72 字节，而界面会告诉他"很长很安全"。
   */
  @Test
  void 超过上限被拒并说明原因() {
    String tooLong = "A1!" + "x".repeat(PasswordPolicy.MAX_LENGTH);

    assertThat(PasswordPolicy.violationOf(tooLong, "admin").orElseThrow()).contains("不能超过");
  }

  @Test
  void 恰好达到长度边界时通过() {
    assertThat(PasswordPolicy.violationOf("Abc123!x", "someone-else")).isEmpty();
    assertThat(
            PasswordPolicy.violationOf(
                "A1!" + "x".repeat(PasswordPolicy.MAX_LENGTH - 3), "someone-else"))
        .isEmpty();
  }

  @Test
  void 账号名为空时跳过该条检查() {
    assertThat(PasswordPolicy.violationOf("Str0ng!Pass", null)).isEmpty();
    assertThat(PasswordPolicy.violationOf("Str0ng!Pass", "  ")).isEmpty();
  }

  /** 类别计数本身也值得单测：它是"复杂度"这条规则的唯一实现。 */
  @Test
  void 类别计数按四类统计() {
    assertThat(PasswordPolicy.characterKinds("abcd")).isEqualTo(1);
    assertThat(PasswordPolicy.characterKinds("abcd1234")).isEqualTo(2);
    assertThat(PasswordPolicy.characterKinds("abcdABCD")).isEqualTo(2);
    assertThat(PasswordPolicy.characterKinds("abcd1234!")).isEqualTo(3);
    assertThat(PasswordPolicy.characterKinds("abcdABCD1234!")).isEqualTo(4);
    // 中文字符归入"其他"，算一类 —— 中文口令是合法的，不该被当成"没有特殊字符"
    assertThat(PasswordPolicy.characterKinds("密码密码密码")).isEqualTo(1);
  }
}
