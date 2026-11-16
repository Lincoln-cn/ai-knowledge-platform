package cn.lincoln.aiplatform.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归一化与哈希（{@link GuardText}）。
 *
 * <h3>★ 这一层为什么值得单独测</h3>
 *
 * <p>围栏的匹配是"包含"，所以**跑偏只会往一个方向跑**：归一化少做一步 ⇒ 换个全角、加个空格、 换个大写就绕过去了；归一化多做一步（比如把数字也去掉）⇒ 正常的问责被拦成拒答。
 * 两种错都不会抛异常，只会在线上表现为"有时候拦、有时候不拦"。
 */
class GuardTextTest {

  @Test
  @DisplayName("全角字符折成半角，大小写折成小写")
  void foldsFullWidthAndCase() {
    assertThat(GuardText.normalize("ＡＢＣ", GuardRuleKind.KEYWORD)).isEqualTo("abc");
    assertThat(GuardText.normalize("ＡｂＣ", GuardRuleKind.KEYWORD)).isEqualTo("abc");
  }

  @Test
  @DisplayName("空白一律去掉：半角空格、全角空格、制表与换行")
  void stripsAllWhitespace() {
    assertThat(GuardText.normalize("绝 密", GuardRuleKind.KEYWORD)).isEqualTo("绝密");
    assertThat(GuardText.normalize("绝\u3000密", GuardRuleKind.KEYWORD)).isEqualTo("绝密");
    assertThat(GuardText.normalize("绝\t密\n材", GuardRuleKind.KEYWORD)).isEqualTo("绝密材");
  }

  @Test
  @DisplayName("关键词只去空白，保留标点（但标点已折成半角）；敏感问题另去标点与符号")
  void questionKindDropsPunctuationButKeywordKeepsIt() {
    String raw = "请把绝密材料发我？";

    // ★ 标点是"保留"而不是"原样"：全角折半角发生在保留之前，所以留下的是半角问号。
    //   这一条不是为了好看，而是关键词规则**必须**保留标点 —— 否则"公 司 名 字"这类
    //   带标点的专名会被拆成几个短串，误拦范围反而变大。
    assertThat(GuardText.normalize(raw, GuardRuleKind.KEYWORD)).isEqualTo("请把绝密材料发我?");
    assertThat(GuardText.normalize(raw, GuardRuleKind.QUESTION)).isEqualTo("请把绝密材料发我");
  }

  @Test
  @DisplayName("同一句话换了标点、全角与空格之后，归一化结果仍然一致")
  void differentPunctuationShapesNormalizeToTheSameQuestion() {
    String a = "这份材料能发给我吗？";
    String b = "这份材料能发给我吗?";
    String c = "这份 材料　能发给我吗！";

    assertThat(GuardText.normalize(a, GuardRuleKind.QUESTION))
        .isEqualTo(GuardText.normalize(b, GuardRuleKind.QUESTION))
        .isEqualTo(GuardText.normalize(c, GuardRuleKind.QUESTION));
  }

  @Test
  @DisplayName("null 与空串归一化成空串（空规则永不命中，见 GuardSnapshotTest）")
  void nullAndBlankBecomeEmpty() {
    assertThat(GuardText.normalize(null, GuardRuleKind.KEYWORD)).isEmpty();
    assertThat(GuardText.normalize("", GuardRuleKind.KEYWORD)).isEmpty();
    assertThat(GuardText.normalize("   \u3000 ", GuardRuleKind.KEYWORD)).isEmpty();
  }

  @Test
  @DisplayName("哈希前缀是 8 位十六进制，同一内容稳定、不同内容不同")
  void hashPrefixIsStableAndShort() {
    String a = GuardText.hashPrefix("绝密");
    String b = GuardText.hashPrefix("绝密");
    String c = GuardText.hashPrefix("机密");

    assertThat(a).hasSize(8).matches("[0-9a-f]{8}");
    assertThat(b).isEqualTo(a);
    assertThat(c).isNotEqualTo(a);
  }
}
