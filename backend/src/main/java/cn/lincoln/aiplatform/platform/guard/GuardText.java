package cn.lincoln.aiplatform.platform.guard;

/**
 * 围栏的归一化口径（{@code ROADMAP §11.53.4}）。
 *
 * <h3>★ 为什么必须归一化，而不是直接 {@code String.contains}</h3>
 *
 * <p>同一条规则写在纸上和打在输入框里会长得不一样：全角逗号、中间的空格、大小写、句末的问号。 不归一化的话，运营加一条"某某某"就要再加 "某某 某"、"某某某？"、"某某某。"——
 * 而且永远补不全。
 *
 * <h3>★ 为什么落库的是归一化结果</h3>
 *
 * <p>匹配发生在**每次请求**上。把归一化留在代码里意味着"怎么归一化"这一件事有两个副本（写规则时一次、 匹配时一次），而它们一旦漂移，症状是"规则在库里
 * 长得对、就是不生效"。落库让两边看到的是同一份字符串。
 *
 * <p>★ 但**升级归一化规则时要重算全表**：{@code normalized} 是派生数据，改了算法旧行就落后 —— 这条与 {@code ROADMAP
 * §11.47}（切分规则是代码，代码升级后旧分段落后）是同一个坑，运维动作写在 {@code GuardService#renormalizeAll}。
 */
public final class GuardText {

  private GuardText() {}

  /**
   * 归一化：全角转半角 · 去空白 · 转小写；{@link GuardRuleKind#QUESTION} 另去标点。
   *
   * @param raw 原始文本（{@code null} 当空串）
   * @param kind 规则类型（决定要不要去标点）
   * @return 归一化结果
   */
  public static String normalize(String raw, GuardRuleKind kind) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    boolean dropPunctuation = kind == GuardRuleKind.QUESTION;
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = toHalfWidth(raw.charAt(i));
      // ★ 用 isWhitespace + isSpaceChar 两个：前者管 \t\n，后者管全角空格（U+3000 转半角后已经是普通空格，
      //   但别处可能直接进来 U+00A0）。
      if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        continue;
      }
      // ★ 问题集只保留「字母与数字」：标点、符号、引号、空格一律不进匹配串。
      //   不用 Character.isPunctuation（判 Unicode 类别要一串常量，而"非字母数字"这个口径
      //   恰好就是我们要的：用户写下标点的方式千变万化，规则里那份不该被标点绑死）。
      if (dropPunctuation && !Character.isLetterOrDigit(c)) {
        continue;
      }
      out.append(Character.toLowerCase(c));
    }
    return out.toString();
  }

  /**
   * 内容哈希前缀（8 位）。审计里记它而不是记词面 —— 与 {@code SafeGuardChatAdvisor.REFUSAL} 不复述命中词
   * 同一个理由：留痕本身不能变成"哪些话会被拦"的清单。
   *
   * @param normalized 归一化后的规则内容
   * @return 16 进制前 8 位
   */
  public static String hashPrefix(String normalized) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(8);
      for (int i = 0; i < 4; i++) {
        hex.append(String.format("%02x", hash[i]));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException never) {
      // SHA-256 是 JDK 必备算法；真拿不到就退回长度信息，不静默成功一个空串
      throw new IllegalStateException("JDK 不提供 SHA-256", never);
    }
  }

  private static char toHalfWidth(char c) {
    if (c == '\u3000') {
      return ' ';
    }
    if (c >= '\uFF01' && c <= '\uFF5E') {
      return (char) (c - 0xFEE0);
    }
    return c;
  }
}
